package com.kbook.service.ai.outline;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.config.ChatModelFactory;
import com.kbook.entity.AiScene;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 回答条目化服务：把"概述/列表型回答"转为结构化 {@link AnswerOutline}（§4.5）。
 * <p>
 * 两级获取策略（对抗异步落库与追问的竞态）：
 * <ol>
 *   <li>DB 优先 — 上一轮 assistant 消息的 {@code answer_outline} 列（流式结束后异步写入）；</li>
 *   <li>缺失即<b>现场条目化</b> — 同步 TOOL 调用（输入 = lastAiAnswer，约 1s 可接受），
 *       结果由调用方写回 DB（回填）。</li>
 * </ol>
 * 失败（模型未配置/输出不可解析/答案无列表结构）一律返回 null，调用方退回单次流式路径——
 * 行为不比现状差（Q4）。
 *
 * @author kbook
 * @since 1.1.0
 */
@Slf4j
@Service
public class AnswerOutlineService {

    /** 条目数硬上限：超过视为误判（正常概述列表远小于此） */
    private static final int MAX_ITEMS_SANITY = 30;

    private final ChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;

    public AnswerOutlineService(ChatModelFactory chatModelFactory) {
        this.chatModelFactory = chatModelFactory;
        this.objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 同步条目化（现场回退路径使用；异步路径请经 executor 调本方法）。
     *
     * @param lastAnswer 上一轮 AI 回答全文
     * @return outline（items ≥ 1）；答案无列表结构或失败时 null
     */
    public AnswerOutline build(String lastAnswer) {
        if (lastAnswer == null || lastAnswer.isBlank()) {
            return null;
        }
        try {
            var model = chatModelFactory.buildForScene(AiScene.ANSWER_OUTLINE);
            if (model == null) {
                log.info("[Outline] TOOL 模型未配置，跳过条目化");
                return null;
            }
            List<ChatMessage> messages = List.of(
                    SystemMessage.from(BUILD_SYSTEM_PROMPT),
                    UserMessage.from("【待条目化的回答】\n" + truncate(lastAnswer, 6000)));
            String text = model.chat(messages).aiMessage().text();
            AnswerOutline outline = parseRaw(text);
            if (outline == null || outline.items() == null || outline.items().isEmpty()) {
                log.info("[Outline] 条目化无结果（答案可能无列表结构）: chars={}", text == null ? 0 : text.length());
                return null;
            }
            if (outline.size() > MAX_ITEMS_SANITY) {
                log.warn("[Outline] 条目数 {} 超过合理性上限 {}，视为误判丢弃", outline.size(), MAX_ITEMS_SANITY);
                return null;
            }
            // 补全缺失 id / 规范化。
            // chunkAnchors 统一置空：锚点是"当时检索现场"的产物，跨轮/切块重建后 chunkIndex 可能漂移
            // 失效，复用反而有害——流水线检索阶段永远重新现场对齐（§3.1 第 1 轮③），
            // 或由关键词向量检索兜底（§4.6 第 2 级）
            List<AnswerOutline.Item> normalized = new ArrayList<>(outline.items().size());
            for (int i = 0; i < outline.items().size(); i++) {
                AnswerOutline.Item it = outline.items().get(i);
                if (it == null || it.title() == null || it.title().isBlank()) continue;
                normalized.add(new AnswerOutline.Item(
                        it.id() != null && it.id() > 0 ? it.id() : i + 1,
                        it.title().trim(),
                        it.gist(),
                        it.keywords() == null ? List.of() : it.keywords(),
                        null));
            }
            if (normalized.size() < 2) {
                return null; // 流水线要求 ≥2 项（§3.4）
            }
            log.info("[Outline] 条目化成功: topic={} items={}", outline.topic(), normalized.size());
            return new AnswerOutline(outline.topic(), normalized);
        } catch (Exception e) {
            log.warn("[Outline] 条目化失败（不影响主流程）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 DB JSON 解析 outline（读 answer_outline 列）。解析失败返回 null（等同"无 outline"）。
     * <p>chunkAnchors 一律丢弃：锚点是"当时检索现场"产物，跨轮/切块重建后可能漂移失效，
     * 复用反而有害——流水线检索阶段永远现场对齐（§3.1 第 1 轮③），关键词向量检索兜底（§4.6 第 2 级）。
     */
    public AnswerOutline parseFromDb(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            AnswerOutline outline = objectMapper.readValue(json, AnswerOutline.class);
            if (outline == null || outline.items() == null || outline.items().size() < 2) return null;
            List<AnswerOutline.Item> clean = new ArrayList<>(outline.items().size());
            for (AnswerOutline.Item it : outline.items()) {
                if (it == null || it.title() == null || it.title().isBlank()) continue;
                clean.add(new AnswerOutline.Item(
                        it.id() != null && it.id() > 0 ? it.id() : clean.size() + 1,
                        it.title().trim(), it.gist(),
                        it.keywords() == null ? List.of() : it.keywords(),
                        null));
            }
            if (clean.size() < 2) return null;
            return new AnswerOutline(outline.topic(), clean);
        } catch (Exception e) {
            log.warn("[Outline] DB outline 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 序列化为 DB JSON；失败返回 null（调用方跳过写回）。 */
    public String toJson(AnswerOutline outline) {
        try {
            return objectMapper.writeValueAsString(outline);
        } catch (Exception e) {
            log.warn("[Outline] 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析模型原始输出：容忍 markdown 代码围栏与前后杂文本（取第一个 { 到最后一个 }）。
     */
    private AnswerOutline parseRaw(String text) {
        if (text == null || text.isBlank()) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        String json = text.substring(start, end + 1);
        try {
            JsonNode root = objectMapper.readTree(json);
            String topic = root.path("topic").asText("");
            List<AnswerOutline.Item> items = new ArrayList<>();
            for (JsonNode n : root.path("items")) {
                Integer id = n.hasNonNull("id") ? n.get("id").asInt() : null;
                String title = n.path("title").asText(null);
                String gist = n.hasNonNull("gist") ? n.get("gist").asText(null) : null;
                List<String> keywords = new ArrayList<>();
                for (JsonNode k : n.path("keywords")) {
                    String s = k.asText(null);
                    if (s != null && !s.isBlank()) keywords.add(s.trim());
                }
                List<Integer> anchors = new ArrayList<>();
                for (JsonNode a : n.path("chunkAnchors")) {
                    if (a.canConvertToInt()) anchors.add(a.asInt());
                }
                items.add(new AnswerOutline.Item(id, title, gist, keywords, anchors));
            }
            return new AnswerOutline(topic.isBlank() ? null : topic, items);
        } catch (Exception e) {
            log.warn("[Outline] JSON 解析失败: {} | head={}", e.getMessage(),
                    json.length() > 120 ? json.substring(0, 120) : json);
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static final String BUILD_SYSTEM_PROMPT = """
            你是回答条目化器。把读者上一轮收到的 AI 回答转换为结构化条目清单，\
            供后续"逐条展开"追问做定向检索与逐节生成的任务骨架。

            【要求】
            1. 抽取回答中的编号/分点条目，保持原文数量与顺序——严禁合并条目、严禁增删条目。
            2. title 为条目短名（≤20 字，尽量用原文措辞）；gist 为一句话摘要（原文要点的压缩，≤40 字）。
            3. keywords 为 1~3 个便于在原书中检索该条目的短语（每个 ≤10 字，名词性优先）。
            4. chunkAnchors 固定输出空数组 []（锚点由后续检索阶段确定）。
            5. 若回答不是列表结构（整段连续散文、或条目少于 2 个），items 输出空数组 []。
            6. 只输出裸 JSON，不要 markdown 代码围栏，不要任何解释文字。

            【输出格式】
            {"topic":"列表主题","items":[{"id":1,"title":"条目名","gist":"一句话摘要","keywords":["关键词"],"chunkAnchors":[]}]}""";
}
