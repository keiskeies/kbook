package com.kbook.service.ai.streaming;

import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.data.message.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一的流式 SSE 响应处理器 — 消除 BookChatService / DebateService / RoundTableService
 * / ChatModelManager 中 5 处重复的 StreamingChatResponseHandler 样板代码。
 *
 * <p>使用方式：
 * <pre>{@code
 *   StreamingSseHandler.stream(model, messages, emitter, new StreamingSseHandler.Callback() {
 *       public String formatMessageEvent(String text) { return text; }
 *       public void onComplete(String fullResponse, ChatResponse resp) {
 *           // 持久化、后处理
 *       }
 *   });
 * }</pre>
 *
 * <p>Callback 接口方法：
 * <ul>
 *   <li>{@code formatMessageEvent} — 格式化 SSE message 事件数据（默认原样返回）</li>
 *   <li>{@code onThinkingToken} — 收到思考 token 时回调（默认忽略）</li>
 *   <li>{@code onComplete} — 流式完成时回调，持久化等</li>
 *   <li>{@code onBeforeComplete} — 发送 SSE done 事件前回调（默认 no-op）</li>
 * </ul>
 *
 * @author kbook
 * @since 1.1.0
 */
@Slf4j
public final class StreamingSseHandler {

    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final long BASE_DELAY_MS = 1000L;
    private static final long MAX_DELAY_MS = 30_000L;

    private StreamingSseHandler() {}

    /**
     * 自定义回调接口 — 每个服务通过实现此接口提供差异化行为。
     */
    public interface Callback {

        /**
         * 返回操作名称，用于日志标识（默认 "流式AI"）。
         */
        default String getOperationName() {
            return "流式AI";
        }

        /**
         * 格式化 SSE "message" 事件数据。
         * 默认原样返回文本（BookChat / SpeedRead 等纯文本场景）。
         * 辩论 / 圆桌派等需要包装 JSON 的场景可覆盖此方法。
         */
        default String formatMessageEvent(String text) {
            return text;
        }

        /**
         * 收到思考（thinking）token 的回调。
         * 默认忽略（大多数场景不使用 thinking 模式）。
         * BookChat 等需要展示思考过程的场景可覆盖此方法，
         * 通过 emitter 发送 "thinking_content" 事件。
         */
        default void onThinkingToken(String thinkingText, SseEmitter emitter) {
            // 默认忽略
        }

        /**
         * 流式输出完成回调。
         * <p>调用时机：在发送 SSE "done" 事件和 emitter.complete() 之前。</p>
         *
         * @param fullResponse 完整响应文本（已 trim）
         * @param completeResponse LangChain4j 完整响应对象（含 token 使用量等）
         */
        void onComplete(String fullResponse, ChatResponse completeResponse);

        /**
         * 发送 SSE done 事件之前的回调（可选）。
         * 用于需要在 done 之前执行的逻辑（如圆桌派的覆盖度更新）。
         */
        default void onBeforeDone(String fullResponse) {
            // 默认无操作
        }

        /**
         * 连接关闭时的回调（可选），用于仅保存部分输出的场景。
         */
        default void onConnectionClosed(String partialResponse) {
            // 默认无操作
        }

        /**
         * 错误回调（可选），在发送 SSE error 事件之前调用。
         * 用于清除缓存、释放资源等清理操作。
         */
        default void onError(Throwable error) {
            // 默认无操作
        }
    }

    /**
     * 执行流式聊天并推送 SSE 事件（不重试）。
     */
    public static void stream(
            StreamingChatModel model,
            List<ChatMessage> messages,
            SseEmitter emitter,
            Callback callback) {
        stream(model, messages, emitter, callback, 0);
    }

    /**
     * 执行流式聊天并推送 SSE 事件（带重试）。
     * <p>
     * 仅在流式输出尚未产生任何 token 时（{@code fullResponse} 为空）触发重试。
     * 适用于 429 限速、5xx 服务端错误、网络超时等场景。
     * 一旦已有 token 输出，不再重试，直接报错。
     *
     * @param model      流式聊天模型实例
     * @param messages   对话消息列表
     * @param emitter    SSE 发射器
     * @param callback   业务回调接口
     * @param maxRetries 最大重试次数（0 = 不重试）
     */
    public static void stream(
            StreamingChatModel model,
            List<ChatMessage> messages,
            SseEmitter emitter,
            Callback callback,
            int maxRetries) {

        // DEBUG: 打印完整对话消息
        CommonUtils.logAiMessages(callback.getOperationName(), messages);

        int retries = 0;
        while (true) {
            final boolean[] connectionClosed = {false};
            StringBuilder fullResponse = new StringBuilder();
            StringBuilder fullThinking = new StringBuilder();
            final int[] thinkingTokenCount = {0};
            AtomicReference<Throwable> errorRef = new AtomicReference<>();

            model.chat(messages, new StreamingChatResponseHandler() {
                StreamingHandle streamingHandle;

                @Override
                public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
                    if (streamingHandle == null) {
                        streamingHandle = context.streamingHandle();
                    }
                    if (connectionClosed[0] || (streamingHandle != null && streamingHandle.isCancelled())) {
                        return;
                    }
                    String thinking = partialThinking.text();
                    if (thinking != null && !thinking.isEmpty()) {
                        if (thinkingTokenCount[0] == 0) {
                            log.warn("检测到模型思考 token（首个），可能模型配置有误或未禁用思考模式");
                        }
                        thinkingTokenCount[0]++;
                        fullThinking.append(thinking);
                        callback.onThinkingToken(thinking, emitter);
                    }
                }

                @Override
                public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                    if (streamingHandle == null) {
                        streamingHandle = context.streamingHandle();
                    }
                    if (connectionClosed[0] || (streamingHandle != null && streamingHandle.isCancelled())) {
                        return;
                    }
                    String text = partialResponse.text();
                    if (text == null || text.isEmpty()) {
                        return;
                    }
                    fullResponse.append(text);

                    String eventData = callback.formatMessageEvent(text);
                    if (!SseHelper.safeSendEvent(emitter, "message", eventData)) {
                        connectionClosed[0] = true;
                        if (streamingHandle != null) {
                            streamingHandle.cancel();
                        }
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    String content = fullResponse.toString().trim();

                    if (thinkingTokenCount[0] > 0) {
                        log.info("流式完成，共收到 {} 个思考 token", thinkingTokenCount[0]);
                    }
                    CommonUtils.logAiResponse(callback.getOperationName(), content,
                            !fullThinking.isEmpty() ? fullThinking.toString() : null);

                    if (connectionClosed[0]) {
                        if (!content.isBlank()) {
                            callback.onConnectionClosed(content);
                        }
                        return;
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }

                    callback.onBeforeDone(content);
                    callback.onComplete(content, completeResponse);

                    try {
                        emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                        emitter.complete();
                    } catch (Exception e) {
                        log.warn("发送 SSE done 事件失败: {}", e.getMessage());
                    }
                }

                @Override
                public void onError(Throwable error) {
                    if (connectionClosed[0] || (streamingHandle != null && streamingHandle.isCancelled())) {
                        return;
                    }
                    log.error("SSE 流式输出异常: {}", error.getMessage(), error);

                    // 仅在无内容输出时记录错误，由外层循环决定是否重试
                    if (fullResponse.toString().trim().isEmpty()) {
                        errorRef.set(error);
                        return;
                    }

                    // 已有部分内容输出，不重试，直接报错
                    String content = fullResponse.toString().trim();
                    try {
                        callback.onConnectionClosed(content);
                    } catch (Exception ex) {
                        log.warn("保存部分输出失败: {}", ex.getMessage());
                    }
                    callback.onError(error);
                    SseHelper.sendErrorAndComplete(emitter, SseHelper.extractFriendlyError(error));
                }
            });

            // 检查是否需要重试
            Throwable error = errorRef.get();
            if (error == null) break;  // 成功完成

            if (retries >= maxRetries || !SseHelper.isRetryableError(error)) {
                // 重试耗尽或不可重试，发送错误给客户端
                callback.onError(error);
                SseHelper.sendErrorAndComplete(emitter, SseHelper.extractFriendlyError(error));
                break;
            }

            retries++;
            long delay = computeDelay(retries);
            log.warn("流式输出失败可重试 (第{}/{}次)，等待 {}ms 后重试: {}",
                    retries, maxRetries, delay, error.getMessage());
            SseHelper.safeSendEvent(emitter, "retry", "网络波动，正在重试...");

            try {
                Thread.sleep(delay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                callback.onError(error);
                SseHelper.sendErrorAndComplete(emitter, SseHelper.extractFriendlyError(error));
                break;
            }
        }
    }

    /**
     * 指数退避 + 随机抖动
     */
    private static long computeDelay(int retryCount) {
        long base = BASE_DELAY_MS * (1L << (retryCount - 1));
        long capped = Math.min(base, MAX_DELAY_MS);
        double jitter = 0.75 + Math.random() * 0.5;
        return (long) (capped * jitter);
    }
}
