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

import java.util.List;

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

    private static final int COMPRESS_THRESHOLD = 200;

    /** 将通用对话内容压缩到 200 字以内 */
    public String compressContent(String original) {
        if (original == null || original.length() <= COMPRESS_THRESHOLD) return original;
        long startTime = System.currentTimeMillis();
        try {
            var model = chatModelFactory.buildToolChatModel();
            if (model == null) {
                log.warn("AI 模型未配置，跳过历史压缩");
                return null;
            }
            List<ChatMessage> messages = List.of(
                    SystemMessage.from("将以下内容压缩到200字以内，保留核心观点、关键论据和信息。"),
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
            var model = chatModelFactory.buildToolChatModel();
            if (model == null) {
                log.warn("AI 模型未配置，跳过圆桌派历史压缩");
                return null;
            }
            List<ChatMessage> messages = List.of(
                    SystemMessage.from("""
                            将以下圆桌派讨论发言压缩到200字以内。

                            【必须保留】
                            1. 发言者的核心论点（用一句话概括）
                            2. 具体论据或例子（保留1-2个关键的）
                            3. 提出的问题或挑战（如果有的话）
                            4. 情绪方向（支持/反对/质疑/追问等）

                            【禁止】
                            - 禁止变成干巴巴的要点列表
                            - 禁止丢失发言者的态度和立场
                            - 禁止删掉提出的问题

                            用一段话概括，保留发言的"味道"，让读者能感受到这个人说了什么、态度是什么。"""),
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
}
