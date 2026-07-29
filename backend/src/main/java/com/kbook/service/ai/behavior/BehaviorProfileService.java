package com.kbook.service.ai.behavior;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.dto.user.BehaviorProfileVO;
import com.kbook.entity.UserBehaviorProfile;
import com.kbook.repository.UserBehaviorProfileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 行为画像服务 — 信号收集 + 滑动窗口触发抽取 + 用户编辑。
 *
 * <p>触发规则：
 * <ul>
 *   <li>累积 ≥ {@link #SIGNAL_THRESHOLD} 条有效信号 → 触发抽取</li>
 *   <li>或距上次抽取 ≥ {@link #STALE_DAYS} 天 → 触发抽取</li>
 *   <li>用独立线程池 {@code behaviorExecutor} 异步执行，不阻塞主回答</li>
 * </ul>
 *
 * <p>信号缓冲存 Redis list（key = behavior:signals:{userId}），原因：
 * <ul>
 *   <li>服务重启不丢信号</li>
 *   <li>跨实例可见（多副本部署）</li>
 *   <li>LPUSH+LLEN 天然适合滑动窗口</li>
 * </ul>
 */
@Slf4j
@Service
public class BehaviorProfileService {

    /** 触发抽取的信号阈值 */
    static final int SIGNAL_THRESHOLD = 5;
    /** 距上次抽取多少天触发补偿抽取 */
    static final int STALE_DAYS = 7;
    /** 信号 buffer 最多保留多少条（防止 LLM 输入过长） */
    private static final int SIGNAL_BUFFER_MAX = 20;
    /** Redis key 前缀 */
    private static final String SIGNAL_KEY_PREFIX = "behavior:signals:";
    /** 信号 TTL（30 天未活跃用户自动清理） */
    private static final long SIGNAL_TTL_DAYS = 30;

    private final UserBehaviorProfileRepository profileRepository;
    private final BehaviorProfileExtractor extractor;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService behaviorExecutor;

    public BehaviorProfileService(
            UserBehaviorProfileRepository profileRepository,
            BehaviorProfileExtractor extractor,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Qualifier("behaviorExecutor") ExecutorService behaviorExecutor) {
        this.profileRepository = profileRepository;
        this.extractor = extractor;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.behaviorExecutor = behaviorExecutor;
    }

    // ==================== 事件监听 ====================

    /**
     * 异步监听用户提问事件 — 写入 Redis 信号队列，判断是否触发抽取。
     */
    @Async("taskExecutor")
    @EventListener
    public void onUserSignal(UserBehaviorSignalEvent event) {
        try {
            recordSignal(event);
            maybeTriggerExtraction(event.userId());
        } catch (Exception e) {
            log.warn("信号处理失败 userId={}: {}", event.userId(), e.getMessage());
        }
    }

    // ==================== 信号收集 ====================

    private void recordSignal(UserBehaviorSignalEvent event) {
        String key = signalKey(event.userId());
        try {
            // 信号格式：JSON {"c":"内容","m":true/false,"t":"type","ts":epochSecond}
            String payload = objectMapper.writeValueAsString(new SignalPayload(
                    truncate(event.content(), 500),
                    event.manual(),
                    event.type(),
                    System.currentTimeMillis()));
            redisTemplate.opsForList().leftPush(key, payload);
            // 保留最近 N 条
            redisTemplate.opsForList().trim(key, 0, SIGNAL_BUFFER_MAX - 1);
            redisTemplate.expire(key, Duration.ofDays(SIGNAL_TTL_DAYS));
        } catch (Exception e) {
            log.warn("信号写入 Redis 失败 userId={}: {}", event.userId(), e.getMessage());
        }
    }

    private void maybeTriggerExtraction(Long userId) {
        String key = signalKey(userId);
        Long size = redisTemplate.opsForList().size(key);
        if (size == null || size < SIGNAL_THRESHOLD) {
            // 数量不够，再看时间是否过期
            Optional<UserBehaviorProfile> existing = profileRepository.findByUserId(userId);
            if (existing.isPresent()) {
                UserBehaviorProfile p = existing.get();
                if (p.getLastInferredAt() != null
                        && p.getLastInferredAt().plusDays(STALE_DAYS).isAfter(LocalDateTime.now())) {
                    return; // 未到 stale 阈值
                }
                // stale 触发，但 size 必须 ≥ 1
                if (size == null || size == 0) return;
            } else {
                return; // 首次抽取必须达到 SIGNAL_THRESHOLD
            }
        }
        // 触发异步抽取
        behaviorExecutor.execute(() -> {
            try {
                runExtraction(userId);
            } catch (Exception e) {
                log.warn("异步画像抽取失败 userId={}: {}", userId, e.getMessage());
            }
        });
    }

    // ==================== 抽取入口 ====================

    /**
     * 执行一次抽取。从 Redis 读信号，调 LLM，写入数据库。
     * 失败时旧画像保留，信号不清空，下次重试。
     */
    @Transactional
    public void runExtraction(Long userId) {
        String key = signalKey(userId);
        List<String> rawSignals = redisTemplate.opsForList().range(key, 0, -1);
        if (rawSignals == null || rawSignals.isEmpty()) return;

        // 解析信号（按时间正序：leftPush 后 range(0,-1) 是最新在前，要反转）
        List<BehaviorProfileExtractor.BehaviorSignal> signals = new ArrayList<>();
        List<String> reversed = new ArrayList<>(rawSignals);
        Collections.reverse(reversed);
        for (String raw : reversed) {
            try {
                SignalPayload p = objectMapper.readValue(raw, SignalPayload.class);
                double weight = p.m() ? 1.0 : 0.3;
                signals.add(new BehaviorProfileExtractor.BehaviorSignal(p.c(), weight));
            } catch (Exception e) {
                log.debug("信号解析失败: {}", e.getMessage());
            }
        }
        if (signals.isEmpty()) return;

        UserBehaviorProfile current = profileRepository.findByUserId(userId).orElse(null);
        LocalDateTime lastInferred = current != null ? current.getLastInferredAt() : null;

        UserBehaviorProfile updated = extractor.extract(userId, current, signals, lastInferred);
        if (updated == null) {
            log.info("画像抽取返回 null，保留旧画像 userId={}", userId);
            return;
        }

        // 更新 recentSignals（滚动保留最近 20 条原文）
        updateRecentSignals(updated, signals);

        profileRepository.save(updated);
        // 清空信号队列（已消费）
        redisTemplate.delete(key);
        log.info("画像抽取完成 userId={} totalSignals={}", userId, updated.getTotalSignals());
    }

    // ==================== 查询 ====================

    public Optional<UserBehaviorProfile> getByUserId(Long userId) {
        return profileRepository.findByUserId(userId);
    }

    /**
     * 获取用户行为画像 VO（解析 JSON 为结构化列表，供前端展示）。
     * 画像不存在时返回空 VO（各列表为空）。
     */
    public BehaviorProfileVO getProfileVO(Long userId) {
        BehaviorProfileVO vo = new BehaviorProfileVO();
        vo.setInterestTags(Collections.emptyList());
        vo.setReadingMotivations(Collections.emptyList());
        vo.setKnowledgeGaps(Collections.emptyList());
        vo.setValueOrientation(Collections.emptyList());
        vo.setPersonalityTraits(Collections.emptyList());
        vo.setConfusions(Collections.emptyList());
        vo.setRecentSignals(Collections.emptyList());
        vo.setTotalSignals(0);

        UserBehaviorProfile p = profileRepository.findByUserId(userId).orElse(null);
        if (p == null) return vo;

        BehaviorProfileBuilder builder = new BehaviorProfileBuilder(objectMapper);

        vo.setInterestTags(builder.parseWeightedTags(p.getInterestTags()).stream()
                .map(t -> new BehaviorProfileVO.WeightedItem(t.tag(), t.weight()))
                .toList());
        vo.setReadingMotivations(builder.parseWeightedTags(p.getReadingMotivations()).stream()
                .map(t -> new BehaviorProfileVO.WeightedItem(t.tag(), t.weight()))
                .toList());
        vo.setKnowledgeGaps(builder.parseStringList(p.getKnowledgeGaps()));
        vo.setValueOrientation(builder.parseStringList(p.getValueOrientation()));
        vo.setPersonalityTraits(builder.parseWeightedTags(p.getPersonalityTraits()).stream()
                .map(t -> new BehaviorProfileVO.WeightedItem(t.tag(), t.weight()))
                .toList());
        vo.setConfusions(builder.parseStringList(p.getConfusions()));
        vo.setLifeContext(p.getLifeContext());
        vo.setRecentSignals(builder.parseStringList(p.getRecentSignals()));
        vo.setTotalSignals(p.getTotalSignals() != null ? p.getTotalSignals() : 0);
        vo.setLastInferredAt(p.getLastInferredAt());

        if (p.getCognitiveDepth() != null) {
            vo.setCognitiveDepth(p.getCognitiveDepth().name());
            vo.setCognitiveDepthLabel(switch (p.getCognitiveDepth()) {
                case SURFACE -> "表层";
                case ANALYTICAL -> "分析型";
                case CRITICAL -> "批判型";
            });
        }
        if (p.getEmotionalTone() != null) {
            vo.setEmotionalTone(p.getEmotionalTone().name());
            vo.setEmotionalToneLabel(switch (p.getEmotionalTone()) {
                case SEEKING_VALIDATION -> "寻求认同";
                case EXPLORING -> "探索中";
                case QUESTIONING -> "质疑";
                case RESIGNED -> "无奈";
                case OPTIMISTIC -> "积极求变";
            });
        }
        if (p.getThinkingStyle() != null) {
            vo.setThinkingStyle(p.getThinkingStyle().name());
            vo.setThinkingStyleLabel(switch (p.getThinkingStyle()) {
                case SYSTEMATIC -> "系统型";
                case DIVERGENT -> "发散型";
                case CRITICAL -> "批判型";
                case INTUITIVE -> "直觉型";
                case PRAGMATIC -> "务实型";
            });
        }
        if (p.getReaderArchetype() != null) {
            vo.setReaderArchetype(p.getReaderArchetype().name());
            vo.setReaderArchetypeLabel(switch (p.getReaderArchetype()) {
                case DEEP_DIVER -> "深潜者";
                case EXPLORER -> "探索者";
                case QUESTIONER -> "追问者";
                case CONTEMPLATOR -> "沉思者";
                case SEEKER -> "求索者";
            });
        }
        return vo;
    }

    // ==================== 用户编辑 ====================

    /**
     * 删除指定信号（用户在前端操作）。把信号加入 suppressedSignals，
     * 下次抽取时 LLM 不会再加强它。同时从当前画像中移除。
     *
     * @param field 字段名：interestTags / readingMotivations / knowledgeGaps / valueOrientation
     * @param value 要删除的值
     */
    @Transactional
    public boolean suppressSignal(Long userId, String field, String value) {
        UserBehaviorProfile p = profileRepository.findByUserId(userId).orElse(null);
        if (p == null) return false;

        BehaviorProfileBuilder builder = new BehaviorProfileBuilder(objectMapper);

        switch (field) {
            case "interestTags" -> p.setInterestTags(removeFromWeighted(p.getInterestTags(), value, builder));
            case "readingMotivations" -> p.setReadingMotivations(removeFromWeighted(p.getReadingMotivations(), value, builder));
            case "knowledgeGaps" -> p.setKnowledgeGaps(removeFromStringList(p.getKnowledgeGaps(), value, builder));
            case "valueOrientation" -> p.setValueOrientation(removeFromStringList(p.getValueOrientation(), value, builder));
            default -> {
                return false;
            }
        }

        // 加入 suppressedSignals（防 LLM 重新加回来）
        List<String> suppressed = builder.parseStringList(p.getSuppressedSignals());
        if (!suppressed.contains(value)) {
            suppressed.add(value);
            try {
                p.setSuppressedSignals(objectMapper.writeValueAsString(suppressed));
            } catch (Exception e) {
                log.warn("写 suppressedSignals 失败: {}", e.getMessage());
            }
        }

        profileRepository.save(p);
        log.info("用户 {} 删除信号 {}={}", userId, field, value);
        return true;
    }

    /**
     * 重置整个行为画像（保留 suppressedSignals）。
     */
    @Transactional
    public boolean resetProfile(Long userId) {
        UserBehaviorProfile p = profileRepository.findByUserId(userId).orElse(null);
        if (p == null) return false;
        String suppressed = p.getSuppressedSignals(); // 保留用户禁止列表
        p.setInterestTags("[]");
        p.setReadingMotivations("[]");
        p.setKnowledgeGaps("[]");
        p.setValueOrientation("[]");
        p.setCognitiveDepth(null);
        p.setEmotionalTone(null);
        p.setTotalSignals(0);
        p.setLastInferredAt(null);
        p.setRecentSignals("[]");
        p.setSuppressedSignals(suppressed != null ? suppressed : "[]");
        profileRepository.save(p);
        // 同时清空信号队列
        redisTemplate.delete(signalKey(userId));
        log.info("用户 {} 重置行为画像", userId);
        return true;
    }

    // ==================== 内部 ====================

    private void updateRecentSignals(UserBehaviorProfile profile,
                                     List<BehaviorProfileExtractor.BehaviorSignal> newSignals) {
        BehaviorProfileBuilder builder = new BehaviorProfileBuilder(objectMapper);
        List<String> recent = new ArrayList<>(builder.parseStringList(profile.getRecentSignals()));
        // 旧信号在前，新信号追加
        for (BehaviorProfileExtractor.BehaviorSignal s : newSignals) {
            recent.add(truncate(s.content(), 100));
        }
        // 滚动保留最近 20 条
        while (recent.size() > 20) {
            recent.remove(0);
        }
        try {
            profile.setRecentSignals(objectMapper.writeValueAsString(recent));
        } catch (Exception e) {
            profile.setRecentSignals("[]");
        }
    }

    private String removeFromWeighted(String json, String value, BehaviorProfileBuilder builder) {
        var list = builder.parseWeightedTags(json);
        list.removeIf(t -> t.tag().equals(value));
        try {
            // 重新序列化为 [{tag,weight}] / [{motivation,weight}]
            List<Map<String, Object>> out = new ArrayList<>();
            for (var t : list) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("tag", t.tag());
                m.put("weight", t.weight());
                out.add(m);
            }
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return json;
        }
    }

    private String removeFromStringList(String json, String value, BehaviorProfileBuilder builder) {
        List<String> list = new ArrayList<>(builder.parseStringList(json));
        list.removeIf(value::equals);
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return json;
        }
    }

    private String signalKey(Long userId) {
        return SIGNAL_KEY_PREFIX + userId;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Redis 中存储的信号格式 */
    record SignalPayload(String c, boolean m, String t, long ts) {}
}
