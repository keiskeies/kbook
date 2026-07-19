package com.kbook.service.ai;

import com.kbook.common.util.CommonUtils;
import com.kbook.config.ChatModelFactory;
import com.kbook.entity.AiScene;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 列表型问题检测器 — LLM 判定用户问题是否为"列表枚举型"。
 * <p>
 * 触发场景示例：
 * <ul>
 *   <li>"详细介绍这11项能力" — 明确 N + 名词</li>
 *   <li>"分别讲讲这些能力" — 无 N 但有"分别"+复数</li>
 *   <li>"14种教育艺术具体是什么" — N + 种/项/类</li>
 *   <li>"列出书中的核心方法" — "列出"+列表名词</li>
 * </ul>
 * <p>
 * 检测结果包含列表主题（如"11项能力培养"），用于后续策略选择和 LLM 精筛。
 * <p>
 * 设计原则：宁可漏判（走常规 RAG）不可误判（增加额外开销）。
 */
@Slf4j
@Service
public class ListQueryDetector {

    private final ChatModelFactory chatModelFactory;

    public ListQueryDetector(ChatModelFactory chatModelFactory) {
        this.chatModelFactory = chatModelFactory;
    }

    /**
     * 检测结果。
     */
    public record DetectionResult(
            /** 是否为列表型问题 */
            boolean isListQuery,
            /** 列表主题（如"11项能力培养"），非列表型问题为 null */
            String listTopic
    ) {
        /** 非列表型问题的默认结果 */
        public static DetectionResult notList() {
            return new DetectionResult(false, null);
        }

        /** 列表型问题的构造 */
        public static DetectionResult of(String topic) {
            return new DetectionResult(true, topic);
        }
    }

    /** 系统提示词 — 行式 KV 输出，便于解析且 LLM 不易出格式错误 */
    private static final String DETECT_SYSTEM_PROMPT = """
            你是一个问题类型分类器。判断用户问题是否为"列表枚举型问题"。

            【列表型问题定义】
            用户想了解某个列表中各项的具体内容。常见模式：
            1. 明确 N + 名词："详细介绍这11项能力"、"14种教育艺术具体是什么"
            2. 复数 + 枚举动词："分别讲讲这些能力"、"列举书中的方法"
            3. 指代词 + 上下文："逐一介绍这些方法"、"展开说说这些观点"
            4. "列出/分别/逐一/展开/详细介绍 + N 项/种/类/个 + 名词"

            【非列表型问题】
            - 单一概念问题："什么是应变能力？"
            - 全书概述："这本书讲了什么？"
            - 是非问题："作者支持这个观点吗？"
            - 单点深入："第一章的核心论点是什么？"

            【上下文提示】
            如果用户消息中包含"上轮AI回答"，且上轮回答中提到了"N项/N种/N类 + 名词"，
            而用户追问这些项的具体内容（即使没有 N 字样），也算列表型问题。

            【输出格式】行式 KV，每行一个字段，不要 JSON、不要代码块围栏、不要其他文字：

            IS_LIST: true
            TOPIC: 11项能力培养

            或（非列表型问题）：
            IS_LIST: false
            TOPIC: （留空）

            IS_LIST 取值：true 或 false
            TOPIC：列表的主题短语（10-20字），如"11项能力培养"、"14种教育艺术"、"书中的核心方法"；非列表型问题时留空。""";

    /**
     * 检测用户问题是否为列表型。
     *
     * @param question     用户问题
     * @param lastAiAnswer 上一轮 AI 回答（可为 null）
     * @return 检测结果；调用失败时返回 notList()（走常规 RAG）
     */
    public DetectionResult detect(String question, String lastAiAnswer) {
        if (question == null || question.isBlank()) {
            return DetectionResult.notList();
        }

        try {
            // 快速预判：明显不含列表信号的问题直接跳过 LLM 调用
            if (!hasListSignal(question, lastAiAnswer)) {
                return DetectionResult.notList();
            }

            List<ChatMessage> messages = List.of(
                    SystemMessage.from(DETECT_SYSTEM_PROMPT),
                    UserMessage.from(buildUserMessage(question, lastAiAnswer)));

            // 使用 TOOL 模型（小模型无 thinking），快速判定
            String aiText = callDetect(messages);
            if (aiText == null || aiText.isBlank()) {
                return DetectionResult.notList();
            }

            return parseResult(aiText);
        } catch (Exception e) {
            log.warn("列表型问题检测失败，走常规 RAG: {}", e.getMessage());
            return DetectionResult.notList();
        }
    }

    /**
     * 快速预判：问题和上轮回答是否含有列表信号。
     * 完全没有信号的问题直接跳过 LLM，节省开销。
     */
    private boolean hasListSignal(String question, String lastAiAnswer) {
        String combined = (question + " " + (lastAiAnswer != null ? lastAiAnswer : "")).toLowerCase();
        // 至少包含一个列表信号词
        return combined.contains("11项") || combined.contains("14种") ||
               combined.contains("分别") || combined.contains("逐一") ||
               combined.contains("列出") || combined.contains("展开") ||
               combined.contains("详细介绍") || combined.contains("项能力") ||
               combined.contains("种方法") || combined.contains("类技巧") ||
               combined.contains("些能力") || combined.contains("些方法") ||
               combined.contains("些观点") || combined.contains("些技巧") ||
               combined.matches(".*\\d+\\s*[项种类个].*");
    }

    private String buildUserMessage(String question, String lastAiAnswer) {
        StringBuilder sb = new StringBuilder();
        if (lastAiAnswer != null && !lastAiAnswer.isBlank()) {
            sb.append("【上轮AI回答】\n")
              .append(truncate(lastAiAnswer, 500))
              .append("\n\n");
        }
        sb.append("【用户问题】\n").append(question);
        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        return text.length() > maxLen ? text.substring(0, maxLen) : text;
    }

    private String callDetect(List<ChatMessage> messages) {
        try {
            var model = chatModelFactory.buildForScene(AiScene.LIST_QUERY_DETECT);
            if (model == null) {
                log.warn("TOOL 模型未配置，跳过列表型问题检测");
                return null;
            }
            return model.chat(messages).aiMessage().text();
        } catch (Exception e) {
            log.warn("列表型问题检测 LLM 调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 LLM 返回的行式 KV 结果。
     * 期望格式：
     * <pre>
     * IS_LIST: true
     * TOPIC: 11项能力培养
     * </pre>
     * 容错：解析失败时返回 notList()。
     */
    private DetectionResult parseResult(String aiText) {
        try {
            String text = CommonUtils.stripCodeFence(aiText);
            if (text == null || text.isBlank()) return DetectionResult.notList();

            // 兼容：LLM 偶发仍输出 JSON 时也能解析
            String trimmed = text.trim();
            if (trimmed.startsWith("{")) {
                try {
                    var mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder().build();
                    var node = mapper.readTree(trimmed);
                    boolean isList = node.has("isList") && node.get("isList").asBoolean(false);
                    String topic = node.has("topic") && !node.get("topic").isNull()
                            ? node.get("topic").asText() : null;
                    if (isList && topic != null && !topic.isBlank()) {
                        log.info("[ListQueryDetector] 检测到列表型问题(JSON兼容): topic={}", topic);
                        return DetectionResult.of(topic.trim());
                    }
                    return DetectionResult.notList();
                } catch (Exception ignored) {
                    // JSON 解析失败，落到下面的行式 KV 解析
                }
            }

            // 行式 KV 解析
            String isListRaw = extractKvLine(text, "IS_LIST");
            String topic = extractKvLine(text, "TOPIC");
            boolean isList = "true".equalsIgnoreCase(isListRaw == null ? "" : isListRaw.trim());
            if (topic != null) {
                topic = topic.trim();
                if (topic.isEmpty() || "（留空）".equals(topic) || "null".equalsIgnoreCase(topic)) {
                    topic = null;
                }
            }

            if (isList && topic != null && !topic.isBlank()) {
                log.info("[ListQueryDetector] 检测到列表型问题: topic={}", topic);
                return DetectionResult.of(topic.trim());
            }
            return DetectionResult.notList();
        } catch (Exception e) {
            log.warn("列表型问题检测结果解析失败: raw={}, error={}", aiText, e.getMessage());
            return DetectionResult.notList();
        }
    }

    /** 提取行式 KV 中某个字段值（大小写不敏感），找不到返回 null */
    private String extractKvLine(String text, String key) {
        Pattern p = Pattern.compile("(?im)^\\s*" + Pattern.quote(key) + "\\s*[:：]\\s*(.*)$");
        Matcher m = p.matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }
}
