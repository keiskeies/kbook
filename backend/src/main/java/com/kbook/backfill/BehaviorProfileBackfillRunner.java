package com.kbook.backfill;

import com.kbook.entity.UserBehaviorProfile;
import com.kbook.repository.UserBehaviorProfileRepository;
import com.kbook.service.ai.behavior.BehaviorProfileExtractor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 行为画像离线回填 — 扫描存量 ai_conversations 表，批量抽取用户行为画像。
 *
 * <p>仅当启动参数包含 {@code --backfill-behavior} 时触发，正常启动不执行：
 * <pre>
 *   java -jar kbook.jar --backfill-behavior
 *   java -jar kbook.jar --backfill-behavior=123   # 仅回填指定 userId
 * </pre>
 *
 * <p>流程：每个用户取最近 20 条 user 消息 → 调 {@link BehaviorProfileExtractor}
 * 复盘抽取 → 落库。回填前已存在画像会被当作 currentProfile 喂入 LLM 做复盘。
 *
 * <p>注意：回填使用 weight=1.0（无法从历史数据区分 manual/追问），仅用于冷启动验证抽取质量。
 */
@Slf4j
@Component
@Order(200)
@RequiredArgsConstructor
public class BehaviorProfileBackfillRunner implements ApplicationRunner {

    /** 每个用户最多回填的提问数（与线上信号 buffer 一致） */
    private static final int MAX_SIGNALS_PER_USER = 20;
    /** 仅回填提问数 ≥ 此值的用户，避免单条提问噪音 */
    private static final int MIN_SIGNALS = 3;

    private final EntityManager entityManager;
    private final BehaviorProfileExtractor extractor;
    private final UserBehaviorProfileRepository profileRepository;

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("backfill-behavior")) {
            return;
        }
        // 支持 --backfill-behavior=123 指定单用户
        List<String> values = args.getOptionValues("backfill-behavior");
        Long targetUserId = null;
        if (values != null && !values.isEmpty()) {
            try {
                targetUserId = Long.parseLong(values.get(0));
            } catch (NumberFormatException ignored) {
            }
        }

        log.info("[行为画像回填] 开始，targetUserId={}", targetUserId != null ? targetUserId : "全部用户");

        List<Long> userIds = loadUserIds(targetUserId);
        log.info("[行为画像回填] 待处理用户数: {}", userIds.size());

        int success = 0;
        int skip = 0;
        int fail = 0;
        for (Long userId : userIds) {
            try {
                List<String> messages = loadUserMessages(userId);
                if (messages.size() < MIN_SIGNALS) {
                    skip++;
                    continue;
                }
                // 取最近 MAX 条（loadUserMessages 已按时间正序，取尾部）
                int from = Math.max(0, messages.size() - MAX_SIGNALS_PER_USER);
                List<String> recent = messages.subList(from, messages.size());

                List<BehaviorProfileExtractor.BehaviorSignal> signals = new ArrayList<>();
                for (String msg : recent) {
                    signals.add(new BehaviorProfileExtractor.BehaviorSignal(msg, 1.0));
                }

                UserBehaviorProfile current = profileRepository.findByUserId(userId).orElse(null);
                LocalDateTime lastInferred = current != null ? current.getLastInferredAt() : null;

                UserBehaviorProfile updated = extractor.extract(userId, current, signals, lastInferred);
                if (updated == null) {
                    log.warn("[行为画像回填] userId={} 抽取返回 null，跳过", userId);
                    fail++;
                    continue;
                }

                // 回填 recentSignals 便于审计
                updateRecentSignals(updated, recent);
                profileRepository.save(updated);
                success++;
                log.info("[行为画像回填] userId={} 完成 signals={} interestTags={} cognitive={} tone={}",
                        userId, signals.size(), updated.getInterestTags(),
                        updated.getCognitiveDepth(), updated.getEmotionalTone());
            } catch (Exception e) {
                fail++;
                log.error("[行为画像回填] userId={} 失败: {}", userId, e.getMessage());
            }
        }

        log.info("[行为画像回填] 完成。成功={}, 跳过(提问不足)={}, 失败={}", success, skip, fail);
    }

    /** 查询所有有 user 消息的用户 ID（或指定用户） */
    @SuppressWarnings("unchecked")
    private List<Long> loadUserIds(Long targetUserId) {
        String sql = "SELECT DISTINCT c.user_id FROM ai_conversations c " +
                "WHERE c.role = 'user' AND c.type IN ('book_chat', 'assistant')";
        if (targetUserId != null) {
            sql += " AND c.user_id = :uid";
        }
        Query query = entityManager.createNativeQuery(sql);
        if (targetUserId != null) {
            query.setParameter("uid", targetUserId);
        }
        return query.getResultList();
    }

    /** 查询某用户所有 user 消息（按时间正序） */
    @SuppressWarnings("unchecked")
    private List<String> loadUserMessages(Long userId) {
        Query query = entityManager.createNativeQuery(
                "SELECT c.content FROM ai_conversations c " +
                "WHERE c.user_id = :uid AND c.role = 'user' " +
                "AND c.type IN ('book_chat', 'assistant') " +
                "ORDER BY c.created_at ASC");
        query.setParameter("uid", userId);
        return query.getResultList();
    }

    private void updateRecentSignals(UserBehaviorProfile profile, List<String> recent) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<String> truncated = new ArrayList<>();
            for (String s : recent) {
                truncated.add(s.length() > 100 ? s.substring(0, 100) : s);
            }
            while (truncated.size() > 20) {
                truncated.remove(0);
            }
            profile.setRecentSignals(mapper.writeValueAsString(truncated));
        } catch (Exception e) {
            profile.setRecentSignals("[]");
        }
    }
}
