package com.kbook.service.ai.core;

import com.kbook.common.util.CommonUtils;
import com.kbook.config.ChatModelFactory;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话历史压缩器 — 将长文本压缩为简短摘要，用于 LLM 上下文窗口管理。
 *
 * <p>从 ChatModelManager 中抽取，直接依赖 ChatModelFactory 避免循环依赖。</p>
 *
 * @author kbook
 * @since 1.1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatHistoryCompressor {

    private final ChatModelFactory chatModelFactory;

    /**
     * 压缩闸门：短于约一句完整话（50 字）的消息压缩收益极低且单条调用浪费，直接保留；
     * 更长的含冗余内容才送压缩。仅作为"是否值得一次 LLM 调用"的下界（单条压缩用），
     * 批量压缩不依赖此阈值（短内容不额外消耗 LLM 调用次数）。
     */
    private static final int COMPRESS_THRESHOLD = 50;
    /** 批量压缩失败时的重试次数（原 fallbackCompress 逐条回退无意义，改为简单重试） */
    private static final int MAX_RETRIES = 1;

    /** 将通用对话内容按信息密度动态压缩精简（单条压缩，用于 AiChatMemory） */
    public String compressContent(String original) {
        if (original == null || original.length() <= COMPRESS_THRESHOLD) return original;
        long startTime = System.currentTimeMillis();
        try {
            var model = chatModelFactory.buildCompressionChatModel();
            if (model == null) {
                log.warn("AI 模型未配置，跳过历史压缩");
                return null;
            }
            List<ChatMessage> messages = List.of(
                    SystemMessage.from("请压缩以下内容以节省上下文空间。"
                            + "保留核心观点、关键论据和关键信息，去除冗余与重复表述。"
                            + "信息密度高的内容尽量保留完整，冗余啰嗦的内容可大幅精简。"
                            + "重点是不要丢失实质信息。"),
                    UserMessage.from(original));
            ChatResponse response = model.chat(messages);
            long elapsed = System.currentTimeMillis() - startTime;
            int inputTokens = response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null
                    ? response.tokenUsage().inputTokenCount() : 0;
            int outputTokens = response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null
                    ? response.tokenUsage().outputTokenCount() : 0;
            String text = response.aiMessage().text();
            if (text != null) text = text.trim();
            CommonUtils.logAiCall("历史压缩", elapsed, inputTokens, outputTokens,
                    String.format("%d→%d chars", original.length(), text != null ? text.length() : 0));
            return text;
        } catch (Exception e) {
            log.warn("调用 AI 压缩内容失败: {}", e.getMessage());
            return null;
        }
    }

    /** 圆桌派讨论历史压缩 */
    public String compressRoundTableContent(String original) {
        if (original == null || original.length() <= COMPRESS_THRESHOLD) return original;
        long startTime = System.currentTimeMillis();
        try {
            var model = chatModelFactory.buildCompressionChatModel();
            if (model == null) {
                log.warn("AI 模型未配置，跳过圆桌派历史压缩");
                return null;
            }
            List<ChatMessage> messages = List.of(
                    SystemMessage.from("""
                            请压缩以下圆桌派发言以节省上下文空间，去除冗余但保留实质。

                            【保留】
                            - 发言者的核心论点
                            - 关键论据或例子
                            - 提出的问题或挑战（如果有的话）
                            - 情绪方向（支持/反对/质疑/追问等）

                            【禁止】
                            - 禁止变成干巴巴的要点列表
                            - 禁止丢失发言者的态度和立场
                            - 禁止删掉提出的问题

                            用一段话概括，保留发言的"味道"。
                            信息密度高的内容尽量保留完整，冗余啰嗦的内容可精简。"""),
                    UserMessage.from(original));
            ChatResponse response = model.chat(messages);
            long elapsed = System.currentTimeMillis() - startTime;
            int inputTokens = response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null
                    ? response.tokenUsage().inputTokenCount() : 0;
            int outputTokens = response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null
                    ? response.tokenUsage().outputTokenCount() : 0;
            String text = response.aiMessage().text();
            if (text != null) text = text.trim();
            CommonUtils.logAiCall("圆桌派历史压缩", elapsed, inputTokens, outputTokens,
                    String.format("%d→%d chars", original.length(), text != null ? text.length() : 0));
            return text;
        } catch (Exception e) {
            log.warn("调用 AI 压缩圆桌派内容失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 一次性批量压缩多条通用对话内容。
     * <p>
     * 与逐条 {@link #compressContent} 相比，本方法只发起一次 LLM 调用，
     * 显著降低长会话场景下的串行请求数与等待延迟。返回列表与输入列表严格按索引对齐：
     * 第 i 个元素对应第 i 条原始内容。仅超过阈值的内容会真正送交 LLM 压缩，
     * 短内容原样保留（与单条压缩行为一致）；批量解析失败或缺失则整体回退逐条压缩。
     *
     * @param originals 待压缩的原始内容列表（允许包含已较短、无需压缩的内容）
     * @return 与 originals 等长且索引对齐的压缩结果列表；整体失败时返回 null
     */
    public List<String> compressContentBatch(List<String> originals) {
        return doBatchCompress(originals, false);
    }

    /**
     * 一次性批量压缩多条圆桌派讨论发言，保留论点、论据、问题与情绪方向。
     * 行为与 {@link #compressRoundTableContent} 一致，但只发起一次 LLM 调用。
     *
     * @param originals 待压缩的发言内容列表
     * @return 与 originals 等长且索引对齐的压缩结果列表；整体失败时返回 null
     */
    public List<String> compressRoundTableContentBatch(List<String> originals) {
        return doBatchCompress(originals, true);
    }

    /**
     * 批量压缩公共逻辑：所有非空内容送一次 LLM 调用（短内容 LLM 自然保留原样）。
     * 解析失败时重试 {@link #MAX_RETRIES} 次而非回退逐条压缩（逐条同样会失败）。
     */
    private List<String> doBatchCompress(List<String> originals, boolean roundTable) {
        if (originals == null || originals.isEmpty()) return List.of();

        // 收集所有非空内容（不再用 COMPRESS_THRESHOLD 过滤——批量调用只有一次，短内容不额外费调用）
        boolean hasContent = false;
        List<Integer> sendIndices = new ArrayList<>();
        List<String> toSend = new ArrayList<>();
        for (int i = 0; i < originals.size(); i++) {
            String o = originals.get(i);
            if (o != null && !o.isBlank()) {
                hasContent = true;
                sendIndices.add(i);
                toSend.add(o);
            }
        }
        if (!hasContent) return new ArrayList<>(originals);

        long startTime = System.currentTimeMillis();
        String numberedInput = buildNumberedInput(toSend);
        String systemPrompt = roundTable
                ? buildRoundTableBatchSystemPrompt(toSend.size())
                : buildBatchSystemPrompt(toSend.size());

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                var model = chatModelFactory.buildCompressionChatModel();
                if (model == null) {
                    log.warn("AI 模型未配置，跳过批量历史压缩");
                    return null;
                }
                List<ChatMessage> messages = List.of(
                        SystemMessage.from(systemPrompt),
                        UserMessage.from(numberedInput));
                ChatResponse response = model.chat(messages);
                long elapsed = System.currentTimeMillis() - startTime;
                String text = response.aiMessage().text();
                if (text != null) text = text.trim();

                List<String> parsed = parseNumberedBatch(text, toSend.size());
                if (parsed.stream().anyMatch(java.util.Objects::isNull)) {
                    log.warn("批量压缩结果格式异常(第{}次): 部分条目缺失", attempt + 1);
                    if (attempt < MAX_RETRIES) continue; // 重试
                    log.warn("重试后格式仍异常，跳过本次压缩");
                    return null;
                }

                List<String> result = new ArrayList<>(originals);
                for (int k = 0; k < sendIndices.size(); k++) {
                    result.set(sendIndices.get(k), parsed.get(k));
                }
                int inputTokens = tokenCount(response, true);
                int outputTokens = tokenCount(response, false);
                CommonUtils.logAiCall(roundTable ? "圆桌派批量历史压缩" : "批量历史压缩", elapsed, inputTokens, outputTokens,
                        String.format("%d条 %d→%d chars", toSend.size(),
                                toSend.stream().mapToInt(String::length).sum(),
                                parsed.stream().mapToInt(s -> s != null ? s.length() : 0).sum()));
                return result;
            } catch (Exception e) {
                log.warn("批量压缩内容失败(第{}次): {}", attempt + 1, e.getMessage());
                if (attempt < MAX_RETRIES) continue; // 重试
                log.warn("重试后仍失败，跳过本次压缩");
                return null;
            }
        }
        return null;
    }

    // ==================== 批量压缩内部工具 ====================

    /** 构造带序号的批量输入，序号从 1 开始，与输入索引对齐 */
    private String buildNumberedInput(List<String> inputs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < inputs.size(); i++) {
            sb.append("[").append(i + 1).append("]\n")
              .append(inputs.get(i) != null ? inputs.get(i) : "")
              .append("\n\n");
        }
        return sb.toString();
    }

    /** 通用批量压缩系统提示词 */
    private String buildBatchSystemPrompt(int count) {
        return """
                你需要压缩以下多条对话内容以节省上下文空间。

                【要求】
                1. 对每条内容独立压缩，保留核心观点、关键论据和关键信息，去除冗余
                2. 信息密度高的内容尽量保留完整，冗余啰嗦的内容可大幅精简。
                   最终目标是保留实质的前提下尽可能节省空间。
                3. 严格按照输入顺序输出，共 %d 条
                4. 每条输出以 [序号] 开头（序号从 1 开始，与输入一一对应），序号后直接跟压缩内容
                5. 不要输出任何额外说明、前缀或总结文字

                【输出格式示例】
                [1] 压缩后的第一条内容
                [2] 压缩后的第二条内容""".formatted(count);
    }

    /** 圆桌派批量压缩系统提示词 */
    private String buildRoundTableBatchSystemPrompt(int count) {
        return """
                你需要压缩以下多条圆桌派发言以节省上下文空间。

                【要求】
                1. 对每条发言独立压缩，去除冗余但保留实质
                2. 信息密度高的内容尽量保留完整，冗余啰嗦的内容可大幅精简。
                   最终目标是保留实质的前提下尽可能节省空间。
                3. 严格按照输入顺序输出，共 %d 条
                4. 每条输出以 [序号] 开头（序号从 1 开始，与输入一一对应），序号后直接跟压缩内容
                5. 不要输出任何额外说明、前缀或总结文字

                【每条保留】
                - 发言者的核心论点
                - 关键论据或例子
                - 提出的问题或挑战（如果有的话）
                - 情绪方向（支持/反对/质疑/追问等）

                【每条禁止】
                - 禁止变成干巴巴的要点列表
                - 禁止丢失发言者的态度和立场
                - 禁止删掉提出的问题

                用一段话概括每条发言，保留"味道"。
                压缩程度按内容重要性自行判断——核心论证多保留，
                过渡性或寒暄性内容可更精简。""".formatted(count);
    }

    /**
     * 解析带 [序号] 标记的批量输出，返回索引对齐的列表（长度 = count）。
     * 未解析到的位置填 null，由调用方决定回退策略。
     */
    private List<String> parseNumberedBatch(String text, int count) {
        List<String> result = new ArrayList<>(Collections.nCopies(count, null));
        if (text == null || text.isBlank()) return result;

        // 按 [N] 起头的块切分，支持跨行内容
        Pattern p = Pattern.compile("(?m)^\\s*\\[(\\d+)\\]\\s*(.*?)(?=\\n\\s*\\[\\d+\\]\\s*|\\z)", Pattern.DOTALL);
        Matcher m = p.matcher(text);
        while (m.find()) {
            int idx;
            try {
                idx = Integer.parseInt(m.group(1)) - 1;
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (idx >= 0 && idx < count) {
                result.set(idx, m.group(2).trim());
            }
        }
        return result;
    }

    private int tokenCount(ChatResponse response, boolean input) {
        var usage = response.tokenUsage();
        if (usage == null) return 0;
        Integer v = input ? usage.inputTokenCount() : usage.outputTokenCount();
        return v != null ? v : 0;
    }
}
