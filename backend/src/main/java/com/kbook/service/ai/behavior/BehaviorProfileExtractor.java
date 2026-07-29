package com.kbook.service.ai.behavior;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.config.ChatModelFactory;
import com.kbook.entity.AiScene;
import com.kbook.entity.UserBehaviorProfile;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 行为画像抽取器 — 把用户最近的提问喂给 LLM，输出精简后的结构化画像。
 *
 * <p>核心设计：每次抽取都是"复盘"而非"追加"。LLM 同时看到旧画像 + 新信号 + 已抑制信号，
 * 决定每条信号是加强、衰减还是删除。这避免了画像无限膨胀——抽取 prompt 里强制写明：
 * <ul>
 *   <li>interestTags 最多 5 项，新主题必须明显比旧主题更突出才能挤掉</li>
 *   <li>超过 30 天未在新信号中出现的旧主题，weight ×0.5</li>
 *   <li>用户已主动删除的信号禁止再加强</li>
 *   <li>总文本长度硬限制（≤ 200 字符 JSON）</li>
 * </ul>
 */
@Slf4j
@Component
public class BehaviorProfileExtractor {

    private final ChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;

    public BehaviorProfileExtractor(ChatModelFactory chatModelFactory, ObjectMapper objectMapper) {
        this.chatModelFactory = chatModelFactory;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行一次抽取。
     *
     * @param userId       用户ID
     * @param currentProfile 当前画像（可为 null）
     * @param signals      新累积的提问信号（已去重，按时间正序）
     * @param lastInferredAt 上次抽取时间（用于 LLM 判断衰减）
     * @return 抽取后的新画像；失败时返回 null，调用方应保留旧画像
     */
    public UserBehaviorProfile extract(Long userId,
                                       UserBehaviorProfile currentProfile,
                                       List<BehaviorSignal> signals,
                                       LocalDateTime lastInferredAt) {
        if (signals == null || signals.isEmpty()) {
            return currentProfile;
        }

        try {
            ChatModel model = chatModelFactory.buildForScene(AiScene.FOLLOW_UP_QUESTION);
            String prompt = buildPrompt(currentProfile, signals, lastInferredAt);
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from(prompt));

            String response = model.chat(prompt);
            return parseResponse(response, userId, currentProfile, signals.size());
        } catch (Exception e) {
            log.warn("行为画像抽取失败 userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    // ==================== Prompt 构造 ====================

    private String buildPrompt(UserBehaviorProfile current, List<BehaviorSignal> signals,
                                LocalDateTime lastInferredAt) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是一个读者行为画像抽取器。从读者最近手动输入的提问中，推断他的兴趣、动机、认知、价值观、性格、思维方式、人生困惑、当前处境。
                这不是追加，而是复盘——你要同时看到旧画像和新信号，决定每条信号是加强、衰减还是删除。

                【核心约束 — 长度优先于完整度】
                - interestTags 最多 5 项；新主题必须明显比旧主题更突出才能挤掉
                - readingMotivations 最多 3 项
                - knowledgeGaps 最多 3 项（指用户"想懂但还不懂"的概念）
                - valueOrientation 最多 5 项
                - personalityTraits 最多 5 项（描述用户的稳定性格倾向，如"内省""理想主义""敏感""务实"等）
                - confusions 最多 3 项（用户想从书中找答案的具体人生问题）
                - 每条 tag/motivation 字数 ≤ 10 字，要具体不要抽象（用"职场女性困境"而非"职场"）
                - lifeContext 一句话 ≤ 30 字
                - 总 JSON 字符长度 ≤ 1000

                【衰减规则 — 不堆砌过期信号】
                - 超过 30 天未在新信号中出现的旧主题，weight ×0.5
                - 超过 90 天未出现视为淡出，直接删除
                - 已在 suppressedSignals 列表中的信号：禁止再加强或重新加入
                - 若新信号明显与旧主题矛盾（如从"追求升职"转向"躺平"），用新主题替换旧主题

                【认知深度判断】
                - SURFACE：复述情节、问基本信息
                - ANALYTICAL：拆解因果、对照前后
                - CRITICAL：质疑结论、反思前提

                【情绪基调判断】
                - SEEKING_VALIDATION：寻求认同（"是不是该这样"）
                - EXPLORING：探索（"还有什么是这样"）
                - QUESTIONING：质疑（"这说得对吗"）
                - RESIGNED：无奈（"道理我都懂但..."）
                - OPTIMISTIC：积极求变（"那我该怎么做"）

                【思维方式判断 — 用户思考问题的风格】
                - SYSTEMATIC：系统型——构建体系、追问根因、关注逻辑自洽
                - DIVERGENT：发散型——跳跃联想、跨界连接、常提"是不是也像..."
                - CRITICAL：批判型——习惯质疑、寻找破绽、常问"这说得对吗"
                - INTUITIVE：直觉型——凭感觉下判断、不喜深究、常问"你怎么看"
                - PRAGMATIC：务实型——关注实用、不纠结理论、常问"那我该怎么做"

                【读者人格判断 — 用户作为读者的人格画像】
                - DEEP_DIVER：深潜者——少量主题反复深挖，问得细
                - EXPLORER：探索者——跨界游走、广度优先，主题分散
                - QUESTIONER：追问者——不断追问、不满足表面答案
                - CONTEMPLATOR：沉思者——慢读、内省、常把书和自身对照
                - SEEKER：求索者——带着人生困惑找答案，阅读是为解惑

                【性格特质抽取 — 从提问风格、关注点、情绪反应中推断】
                - 选 3-5 个最能描述这个人的特质（不是临时兴趣，而是稳定倾向）
                - 例：内省、独立、敏感、理性、好奇心强、理想主义、务实、谨慎、感性、批判、温和、固执、开放、自律、浪漫...
                - weight 反映该特质的显著程度

                【人生困惑抽取 — 人读书本质上是带着困惑找答案】
                - 从提问中识别用户"想从书中找答案"的具体人生问题
                - 最多 3 项，每项 ≤ 15 字，要具体不要空泛
                - 例：「该不该放弃稳定工作追求理想」「如何面对亲人衰老」「人为什么要努力」「怎样与不完美的自己和解」
                - 只抽取真实的困惑，不要编造；若信号中无明确困惑，输出空数组
                - 已在旧画像中且未在最近信号中再次出现的困惑，保留但不动

                【当前处境判断 — 综合所有信号推断用户所处的人生阶段】
                - 用一句话（≤ 30 字）描述用户当前的人生阶段/境遇
                - 例：「职场转型期，对未来方向感到迷茫」「刚经历亲人离世，重新思考人生意义」「在稳定的婚姻里感到自我消失」「退休后寻找新的生活意义」
                - 这是 LLM 综合多信号的整体判断，不是单条提问的复述
                - 若信号不足以推断，输出 null

                【信号过滤】
                - 仅从反映读者认知/兴趣/情绪/价值观/性格的提问中抽取信号
                - 忽略纯操作类请求（"推荐一本书""改我的偏好""这本书讲什么"等）
                - manual=false 的信号权重 0.3，manual=true 权重 1.0
                - 提问内容含 "weight" 字段：0.3 表示点击追问（弱信号），1.0 表示手动输入（强信号）

                """);

        // 旧画像
        if (current != null) {
            sb.append("【旧画像】\n");
            sb.append("interestTags: ").append(safe(current.getInterestTags(), "[]")).append("\n");
            sb.append("readingMotivations: ").append(safe(current.getReadingMotivations(), "[]")).append("\n");
            sb.append("knowledgeGaps: ").append(safe(current.getKnowledgeGaps(), "[]")).append("\n");
            sb.append("valueOrientation: ").append(safe(current.getValueOrientation(), "[]")).append("\n");
            sb.append("personalityTraits: ").append(safe(current.getPersonalityTraits(), "[]")).append("\n");
            sb.append("cognitiveDepth: ").append(current.getCognitiveDepth() != null ? current.getCognitiveDepth().name() : "null").append("\n");
            sb.append("emotionalTone: ").append(current.getEmotionalTone() != null ? current.getEmotionalTone().name() : "null").append("\n");
            sb.append("thinkingStyle: ").append(current.getThinkingStyle() != null ? current.getThinkingStyle().name() : "null").append("\n");
            sb.append("readerArchetype: ").append(current.getReaderArchetype() != null ? current.getReaderArchetype().name() : "null").append("\n");
            sb.append("confusions: ").append(safe(current.getConfusions(), "[]")).append("\n");
            sb.append("lifeContext: ").append(current.getLifeContext() != null ? "\"" + current.getLifeContext() + "\"" : "null").append("\n");
            sb.append("suppressedSignals: ").append(safe(current.getSuppressedSignals(), "[]")).append("\n");
            sb.append("\n");
        } else {
            sb.append("【旧画像】无（首次抽取）\n\n");
        }

        // 上次抽取时间
        if (lastInferredAt != null) {
            sb.append("【上次抽取时间】").append(lastInferredAt.toString()).append("\n\n");
        }

        // 新信号
        sb.append("【最近 ").append(signals.size()).append(" 条提问信号】\n");
        for (int i = 0; i < signals.size(); i++) {
            BehaviorSignal s = signals.get(i);
            sb.append(i + 1).append(". [weight=").append(s.weight()).append("] ")
              .append(s.content().replace("\n", " ")).append("\n");
        }

        sb.append("""

                【输出要求】
                只输出 JSON，不要任何解释、不要 markdown 围栏。格式如下：
                {
                  "interestTags": [{"tag":"...","weight":0.0-1.0}],
                  "readingMotivations": [{"motivation":"...","weight":0.0-1.0}],
                  "knowledgeGaps": ["..."],
                  "valueOrientation": ["..."],
                  "personalityTraits": [{"tag":"...","weight":0.0-1.0}],
                  "cognitiveDepth": "SURFACE|ANALYTICAL|CRITICAL",
                  "emotionalTone": "SEEKING_VALIDATION|EXPLORING|QUESTIONING|RESIGNED|OPTIMISTIC",
                  "thinkingStyle": "SYSTEMATIC|DIVERGENT|CRITICAL|INTUITIVE|PRAGMATIC",
                  "readerArchetype": "DEEP_DIVER|EXPLORER|QUESTIONER|CONTEMPLATOR|SEEKER",
                  "confusions": ["..."],
                  "lifeContext": "...",
                  "removedSignals": ["被本次删除的旧信号tag列表，便于审计"]
                }

                如果新信号全是操作类提问无任何价值，输出空数组 + 全 null，并把旧画像原样保留。
                """);

        return sb.toString();
    }

    // ==================== 响应解析 ====================

    @SuppressWarnings("unchecked")
    private UserBehaviorProfile parseResponse(String response, Long userId,
                                              UserBehaviorProfile current, int signalCount) {
        String json = extractJson(response);
        if (json == null) {
            log.warn("画像抽取响应无 JSON: {}", response.substring(0, Math.min(200, response.length())));
            return current;
        }

        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            UserBehaviorProfile profile = current != null ? current : UserBehaviorProfile.builder().build();
            if (current == null) {
                profile.setUserId(userId);
            }

            // 校验 + 截断
            profile.setInterestTags(serializeWeightedList(
                    truncateWeightedList((List<Map<String, Object>>) map.get("interestTags"), "tag", 5)));
            profile.setReadingMotivations(serializeWeightedList(
                    truncateWeightedList((List<Map<String, Object>>) map.get("readingMotivations"), "motivation", 3)));
            profile.setKnowledgeGaps(serializeStringList(
                    truncateStringList((List<String>) map.get("knowledgeGaps"), 3)));
            profile.setValueOrientation(serializeStringList(
                    truncateStringList((List<String>) map.get("valueOrientation"), 5)));
            profile.setPersonalityTraits(serializeWeightedList(
                    truncateWeightedList((List<Map<String, Object>>) map.get("personalityTraits"), "tag", 5)));
            profile.setCognitiveDepth(parseEnum(map.get("cognitiveDepth"),
                    UserBehaviorProfile.CognitiveDepth.class));
            profile.setEmotionalTone(parseEnum(map.get("emotionalTone"),
                    UserBehaviorProfile.EmotionalTone.class));
            profile.setThinkingStyle(parseEnum(map.get("thinkingStyle"),
                    UserBehaviorProfile.ThinkingStyle.class));
            profile.setReaderArchetype(parseEnum(map.get("readerArchetype"),
                    UserBehaviorProfile.ReaderArchetype.class));
            profile.setConfusions(serializeStringList(
                    truncateStringList((List<String>) map.get("confusions"), 3, 15)));
            Object lc = map.get("lifeContext");
            if (lc instanceof String s && !s.isBlank()) {
                String trimmed = s.trim();
                profile.setLifeContext(trimmed.length() > 50 ? trimmed.substring(0, 50) : trimmed);
            } else {
                profile.setLifeContext(null);
            }

            // 保留 suppressedSignals（用户编辑产生，不在此覆盖）
            if (profile.getSuppressedSignals() == null) {
                profile.setSuppressedSignals("[]");
            }

            // 累计信号数
            int oldCount = profile.getTotalSignals() != null ? profile.getTotalSignals() : 0;
            profile.setTotalSignals(oldCount + signalCount);

            // 滚动保留最近 20 条信号
            profile.setLastInferredAt(LocalDateTime.now());

            log.info("行为画像更新 userId={} signals={} removed={} removedList={}",
                    userId, signalCount, map.get("removedSignals") != null, map.get("removedSignals"));
            return profile;
        } catch (Exception e) {
            log.warn("画像抽取响应解析失败: {}", e.getMessage());
            return current;
        }
    }

    private String extractJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        if (start < 0) return null;
        int end = text.lastIndexOf('}');
        if (end <= start) return null;
        return text.substring(start, end + 1);
    }

    private <T extends Enum<T>> T parseEnum(Object v, Class<T> clazz) {
        if (v == null) return null;
        try {
            return Enum.valueOf(clazz, v.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> truncateWeightedList(List<Map<String, Object>> raw, String key, int max) {
        if (raw == null) return Collections.emptyList();
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> m : raw) {
            Object tag = m.get(key);
            if (tag == null || tag.toString().isBlank()) continue;
            String tagStr = tag.toString();
            if (tagStr.length() > 10) tagStr = tagStr.substring(0, 10);
            Map<String, Object> clean = new java.util.LinkedHashMap<>();
            clean.put(key.equals("motivation") ? "motivation" : "tag", tagStr);
            Object w = m.get("weight");
            double weight = w instanceof Number n ? Math.max(0, Math.min(1, n.doubleValue())) : 0.5;
            clean.put("weight", Math.round(weight * 10) / 10.0);
            filtered.add(clean);
        }
        filtered.sort((a, b) -> {
            double wa = ((Number) a.get("weight")).doubleValue();
            double wb = ((Number) b.get("weight")).doubleValue();
            return Double.compare(wb, wa);
        });
        return filtered.size() > max ? filtered.subList(0, max) : filtered;
    }

    private List<String> truncateStringList(List<String> raw, int max) {
        return truncateStringList(raw, max, 10);
    }

    private List<String> truncateStringList(List<String> raw, int max, int maxLen) {
        if (raw == null) return Collections.emptyList();
        List<String> filtered = new ArrayList<>();
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            String t = s.trim();
            if (t.length() > maxLen) t = t.substring(0, maxLen);
            if (!filtered.contains(t)) filtered.add(t);
        }
        return filtered.size() > max ? filtered.subList(0, max) : filtered;
    }

    private String serializeWeightedList(List<Map<String, Object>> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String serializeStringList(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String safe(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    /** 行为信号 */
    public record BehaviorSignal(String content, double weight) {}
}
