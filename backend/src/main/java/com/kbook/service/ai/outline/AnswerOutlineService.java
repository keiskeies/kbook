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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** 条目化输入上限：超出按条目/句子边界压缩，不再句中硬切 */
    static final int MAX_OUTLINE_INPUT_CHARS = 6000;

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
        return buildWithPrompt(BUILD_SYSTEM_PROMPT,
                "【待条目化的回答】\n" + OutlineTextCompactor.compact(lastAnswer, MAX_OUTLINE_INPUT_CHARS),
                "答案可能无列表结构");
    }

    /**
     * 首轮列表题：没有上轮回答时，从"问题 + 当前检索材料"里提取条目。
     * <p>只提取参考内容中明确出现的条目；没有明确列表时返回 null，退回单次流式。
     * <p>目录提示是书自己的「枚举表」：材料只给前 6000 字时，窗口后部的条目标题
     * （实测六辑只看到前两辑）根本进不了条目化，答案就会漏条目。
     *
     * @param tocHint 与问题相关的目录子树文本；为空表示没有可用提示
     */
    public AnswerOutline buildFromContext(String question, String context, String tocHint) {
        if (question == null || question.isBlank() || context == null || context.isBlank()) {
            return null;
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("【读者问题】\n").append(question).append("\n\n【参考内容】\n")
                .append(OutlineTextCompactor.compact(context, MAX_OUTLINE_INPUT_CHARS));
        if (tocHint != null && !tocHint.isBlank()) {
            prompt.append("\n\n【本书目录（与问题相关的部分）】\n").append(tocHint);
        }
        return buildWithPrompt(CONTEXT_SYSTEM_PROMPT, prompt.toString(),
                "参考内容可能没有明确列表");
    }

    private AnswerOutline buildWithPrompt(String systemPrompt, String userPrompt, String emptyReason) {
        try {
            var model = chatModelFactory.buildForScene(AiScene.ANSWER_OUTLINE);
            if (model == null) {
                log.info("[Outline] TOOL 模型未配置，跳过条目化");
                return null;
            }
            List<ChatMessage> messages = List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt));
            String text = model.chat(messages).aiMessage().text();
            AnswerOutline outline = parseRaw(text);
            if (outline == null || outline.items() == null || outline.items().isEmpty()) {
                log.info("[Outline] 条目化无结果（{}）: chars={}", emptyReason,
                        text == null ? 0 : text.length());
                return null;
            }
            return normalize(outline);
        } catch (Exception e) {
            log.warn("[Outline] 条目化失败（不影响主流程）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 补全缺失 id / 规范化；chunkAnchors 统一置空（锚点是当时检索现场产物，跨轮可能漂移）。
     */
    private AnswerOutline normalize(AnswerOutline outline) {
        if (outline.size() > MAX_ITEMS_SANITY) {
            log.warn("[Outline] 条目数 {} 超过合理性上限 {}，视为误判丢弃",
                    outline.size(), MAX_ITEMS_SANITY);
            return null;
        }
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
            List<AnswerOutline.Item> salvaged = salvageItems(json);
            if (salvaged.size() >= 2) {
                log.warn("[Outline] JSON 整体解析失败，逐条 salvage 保留 {}/{} 条: {} | head={}",
                        salvaged.size(), countOccurrences(json, "{\"id\""), e.getMessage(),
                        json.length() > 120 ? json.substring(0, 120) : json);
                return new AnswerOutline(salvageTopic(json), salvaged);
            }
            log.warn("[Outline] JSON 解析失败（salvage 仅剩 {} 条）: {} | head={}",
                    salvaged.size(), e.getMessage(),
                    json.length() > 120 ? json.substring(0, 120) : json);
            return null;
        }
    }

    /**
     * 逐条抢救条目：模型输出偶尔只坏在个别条上（实测三类：字段名后缺冒号、
     * 摘要里未转义的英文引号、末条被截断没有闭合括号）。整体 readTree 一坏就整份丢弃，
     * 于是流水线退回单次流式——但清单本身已经提取出来了，标题顺序都对，只差一两个字符。
     * <p>按「条目对象起点」切段（提示词固定的字段顺序里 id/title/gist/keywords 任一都能开头），
     * 每段单独解析；坏的那条退化为正则取标题。不用大括号配对切：未转义的引号会让
     * 引号内外的判定错位，正好在最需要 salvage 的输出上失败。
     */
    private List<AnswerOutline.Item> salvageItems(String json) {
        Matcher starts = ITEM_START.matcher(json);
        List<Integer> positions = new ArrayList<>();
        while (starts.find()) {
            positions.add(starts.start());
        }
        if (positions.isEmpty()) {
            return List.of();
        }
        List<AnswerOutline.Item> items = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            int from = positions.get(i);
            int to = i + 1 < positions.size() ? positions.get(i + 1) : json.length();
            String seg = json.substring(from, to).trim();
            if (seg.endsWith(",")) {
                seg = seg.substring(0, seg.length() - 1).trim();
            }
            int before = items.size();
            // 末条自带外层 "]}"：Jackson 默认容忍尾随内容，原样解析即可拿到该条目。
            addIfParseable(items, seg);
            if (items.size() == before) {
                addIfParseable(items, seg + "}"); // 末条被截断没写闭合括号
            }
        }
        return items;
    }

    /**
     * topic 通常在头部且很少是坏点；salvage 时仍取回它。
     * 丢掉它会让流水线分段指令退成「上一条回答」——首轮根本没有上一条回答，措辞会误导模型。
     */
    private String salvageTopic(String json) {
        Matcher tm = TOPIC_FIELD.matcher(json);
        if (!tm.find()) {
            return null;
        }
        String topic = tm.group(1).trim();
        return topic.isEmpty() ? null : topic;
    }

    /**
     * 解析单个条目对象；模型偶尔在字符串里少写转义，Jackson 会报「字段名后缺逗号」——
     * 退一步正则取 title/keywords。
     */
    private void addIfParseable(List<AnswerOutline.Item> items, String objJson) {
        try {
            JsonNode n = objectMapper.readTree(objJson);
            String title = n.path("title").asText(null);
            if (title == null || title.isBlank()) {
                salvageByRegex(items, objJson);
                return;
            }
            Integer id = n.hasNonNull("id") ? n.get("id").asInt() : null;
            List<String> keywords = new ArrayList<>();
            for (JsonNode k : n.path("keywords")) {
                String s = k.asText(null);
                if (s != null && !s.isBlank()) keywords.add(s.trim());
            }
            items.add(new AnswerOutline.Item(id, title,
                    n.hasNonNull("gist") ? n.get("gist").asText(null) : null, keywords, List.of()));
        } catch (Exception ignored) {
            salvageByRegex(items, objJson);
        }
    }

    /** 正则兜底：只要拿得到 "title":"…" 就收下（流水线展开时真正用到的就是标题）。 */
    private void salvageByRegex(List<AnswerOutline.Item> items, String objJson) {
        Matcher m = TITLE_FIELD.matcher(objJson);
        if (!m.find()) {
            m = TITLE_FIELD_LOOSE.matcher(objJson); // "title" "值" —— 冒号也漏了
            if (!m.find()) {
                return;
            }
        }
        String title = m.group(1).trim();
        if (title.isEmpty()) {
            return;
        }
        Integer id = null;
        Matcher idm = ID_FIELD.matcher(objJson);
        if (idm.find()) {
            id = Integer.parseInt(idm.group(1));
        }
        List<String> keywords = new ArrayList<>();
        Matcher km = KEYWORDS_BLOCK.matcher(objJson);
        if (km.find()) {
            Matcher q = QUOTED.matcher(km.group(1));
            while (q.find()) {
                String s = q.group(1).trim();
                if (!s.isEmpty()) keywords.add(s);
            }
        }
        items.add(new AnswerOutline.Item(id, title, null, keywords, List.of()));
    }

    private static int countOccurrences(String s, String needle) {
        int count = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    /** 条目对象起点：提示词固定的字段里任一个都可能是首字段（缺冒号那种也算条目开头） */
    private static final Pattern ITEM_START =
            Pattern.compile("\\{\\s*\"(?:id|title|gist|keywords)\"\\s*:?");
    private static final Pattern TOPIC_FIELD =
            Pattern.compile("\"topic\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern TITLE_FIELD =
            Pattern.compile("\"title\"\\s*:\\s*\"(.*?)(?:\"\\s*,|\"\\s*})");
    /** 字段名与值之间缺冒号时用这条：只取到下一个引号，够当标题用 */
    private static final Pattern TITLE_FIELD_LOOSE =
            Pattern.compile("\"title\"\\s*:?\\s*\"([^\"]+)\"");
    private static final Pattern ID_FIELD = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern KEYWORDS_BLOCK =
            Pattern.compile("\"keywords\"\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    private static final String CONTEXT_SYSTEM_PROMPT = """
            你是列表条目提取器。根据读者问题和参考内容，提取读者要求逐条展开的列表条目。

            【要求】
            1. 只提取参考内容或本书目录中明确出现的条目，保持原文顺序；不要补全、不要合并、不要增删。
            1b. 参考内容或目录若给出明确的枚举或条目总数（如"分为六辑""自然、人类、经济、投资、人生和处世六辑""25 种倾向"），
                必须把枚举的每一项各列为一条，条目数与枚举数一致；title 取枚举项的名称（如"自然""人类"），
                条目自带序数前缀时保留原文写法（如"第一辑 自然"）。
            1c. 本书目录只用于确认读者所问那一部分的条目名称与数量：不要把目录里与问题无关的章节列成条目。
            2. title ≤20 字，尽量用原文措辞；gist 为一句话摘要（≤40 字）。
            3. keywords 为 1~3 个便于回原书检索该条目的短语（每个 ≤10 字，名词性优先）。
            4. chunkAnchors 固定输出空数组 []。
            5. 参考内容没有明确列表、或条目少于 2 个时，items 输出空数组 []。
            6. 只输出裸 JSON，不要 markdown 代码围栏，不要任何解释文字。

            【输出格式】
            {"topic":"列表主题","items":[{"id":1,"title":"条目名","gist":"一句话摘要","keywords":["关键词"],"chunkAnchors":[]}]}""";

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
