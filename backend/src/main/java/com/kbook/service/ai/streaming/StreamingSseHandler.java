package com.kbook.service.ai.streaming;

import com.kbook.common.util.CommonUtils;
import com.kbook.common.util.SseHelper;
import com.kbook.service.ai.core.ChatModelManager.AiCallLogContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
 *   <li>{@code onBeforeDone} — 发送 SSE done 事件前回调（默认 no-op）</li>
 *   <li>{@code onFinishReason} — 上报模型结束原因（LENGTH=被截断，默认 no-op）</li>
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
         * 流式完成前上报模型的结束原因（可选）。
         * <p>调用时机：onCompleteResponse 内、onComplete 之前。
         * {@code LENGTH} 表示输出因达到 token 上限被截断——回答可能不完整，
         * 业务侧可据此记录诊断字段或提示用户。连接已关闭时不会回调。</p>
         *
         * @param finishReason 结束原因名称（STOP/LENGTH/CONTENT_FILTER/TOOL_EXECUTION/OTHER；未知时为 null）
         */
        default void onFinishReason(String finishReason) {
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
        stream(model, messages, emitter, callback, 0, null);
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
        stream(model, messages, emitter, callback, maxRetries, null);
    }

    /**
     * 执行流式聊天并推送 SSE 事件（带重试 + 日志上下文）。
     *
     * @param logContext 日志上下文（场景/模型/思考配置），可为 null（摘要中显示"未知"）
     */
    public static void stream(
            StreamingChatModel model,
            List<ChatMessage> messages,
            SseEmitter emitter,
            Callback callback,
            int maxRetries,
            AiCallLogContext logContext) {

        // DEBUG: 打印完整对话消息
        CommonUtils.logAiMessages(callback.getOperationName(), messages);

        // 记录流式调用开始时间，用于统一摘要日志
        final long startTime = System.currentTimeMillis();
        // 提取 SystemMessage 文本，用于统一摘要日志的请求预览
        final String systemPromptText = extractSystemPromptText(messages);

        int retries = 0;
        while (true) {
            final boolean[] connectionClosed = {false};
            StringBuilder fullResponse = new StringBuilder();
            StringBuilder fullThinking = new StringBuilder();
            final int[] thinkingTokenCount = {0};
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            // Google AI 的 <thought> 标签解析器 — 从普通 content 中分离思考内容
            final ThoughtTagParser thoughtParser = new ThoughtTagParser();

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

                    // 通过 <thought> 标签解析器分离思考内容和正常回复
                    ThoughtTagParser.Result parsed = thoughtParser.process(text);

                    // 处理分离出的思考内容
                    if (parsed.hasThinking()) {
                        thinkingTokenCount[0]++;
                        fullThinking.append(parsed.thinking());
                        callback.onThinkingToken(parsed.thinking(), emitter);
                    }

                    // 处理正常回复内容
                    if (parsed.hasMessage()) {
                        fullResponse.append(parsed.message());
                        String eventData = callback.formatMessageEvent(parsed.message());
                        if (!SseHelper.safeSendEvent(emitter, "message", eventData)) {
                            connectionClosed[0] = true;
                            if (streamingHandle != null) {
                                streamingHandle.cancel();
                            }
                        }
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    String content = fullResponse.toString().trim();
                    String thinking = !fullThinking.isEmpty() ? fullThinking.toString() : null;

                    // 阶段0诊断：记录模型结束原因。LENGTH = 输出被 token 上限截断，
                    // 回答大概率不完整（列表展开丢尾项的主要嫌疑之一），此前全仓库无人读取该字段。
                    FinishReason finishReason = completeResponse.finishReason();
                    String finishReasonName = finishReason != null ? finishReason.name() : null;
                    if (finishReason == FinishReason.LENGTH) {
                        log.warn("[诊断] 流式输出被 token 上限截断 (finishReason=LENGTH): op={}, scene={}, model={}, "
                                        + "outputTokens={}, contentChars={}, thinkingTokens={}",
                                callback.getOperationName(),
                                logContext != null ? logContext.scene() : null,
                                logContext != null ? logContext.modelName() : null,
                                completeResponse.tokenUsage() != null ? completeResponse.tokenUsage().outputTokenCount() : null,
                                content.length(), thinkingTokenCount[0]);
                    } else if (finishReason == null) {
                        log.info("[诊断] 流式完成但未提供 finishReason: op={}, contentChars={}",
                                callback.getOperationName(), content.length());
                    } else {
                        log.debug("流式完成: op={}, finishReason={}, contentChars={}",
                                callback.getOperationName(), finishReasonName, content.length());
                    }

                    // INFO: 统一流式摘要日志（一次 LLM 调用只打一条）
                    int inputTokens = completeResponse.tokenUsage() != null && completeResponse.tokenUsage().inputTokenCount() != null
                            ? completeResponse.tokenUsage().inputTokenCount() : 0;
                    int outputTokens = completeResponse.tokenUsage() != null && completeResponse.tokenUsage().outputTokenCount() != null
                            ? completeResponse.tokenUsage().outputTokenCount() : 0;
                    long elapsed = System.currentTimeMillis() - startTime;
                    String scene = logContext != null ? logContext.scene() : null;
                    String modelName = logContext != null ? logContext.modelName() : null;
                    String configName = logContext != null ? logContext.configName() : null;
                    String thinkingMode = logContext != null ? logContext.thinkingMode() : null;
                    boolean thinkingEnabled = logContext != null && logContext.thinkingEnabled();
                    String reasoningEffort = logContext != null ? logContext.reasoningEffort() : null;
                    CommonUtils.logAiStreamingSummary(callback.getOperationName(), scene, modelName, configName,
                            thinkingMode, thinkingEnabled, reasoningEffort,
                            messages,
                            content, thinking,
                            elapsed, inputTokens, outputTokens);

                    if (connectionClosed[0]) {
                        if (!content.isBlank()) {
                            callback.onConnectionClosed(content);
                        }
                        return;
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }

                    // 上报结束原因给业务侧（onComplete 之前），供记录诊断字段
                    try {
                        callback.onFinishReason(finishReasonName);
                    } catch (Exception ex) {
                        log.warn("onFinishReason 回调异常（不影响主流程）: {}", ex.getMessage());
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
     * 从消息列表中提取第一条 SystemMessage 的文本（用于摘要日志的请求预览）。
     */
    private static String extractSystemPromptText(List<ChatMessage> messages) {
        if (messages == null) return null;
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage sm) {
                return sm.text();
            }
        }
        return null;
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

    /**
     * 单段流式结果（多段流水线原语，§4.4）。
     *
     * @param text             本段已输出的完整文本（截断/中断时为部分文本）
     * @param finishReason     结束原因 STOP/LENGTH/…；失败为 "ERROR"；超时为 "TIMEOUT"；可为 null
     * @param connectionClosed 客户端连接已断或任务被取消——调用方应中止流水线并按部分输出落库
     * @param error            本段模型错误（无重试价值的一次性失败），null=正常完成
     */
    public record SegmentResult(String text, String finishReason,
                                 boolean connectionClosed, String error) {
        public boolean failed() {
            return error != null;
        }
    }

    /**
     * 单段流式：推 message/thinking_content，<b>不发 done、不 complete、不调 onComplete</b>。
     * <p>阻塞至该段完成（onCompleteResponse / onError / 连接断开 / 线程中断 / 超时），返回本段结果。
     * 供列表展开流水线串行调用：每节一个段，节与节之间由调用方驱动。
     * <p>内置单节截断续写：{@code finishReason=LENGTH} 且已有内容时，以已输出文本为前缀
     * 再续写一轮（最多 2 次），爆炸半径只在本节（对齐计划 §4.2）。
     * <p>取消语义：调用线程被中断（emitter.onTimeout → aiFuture.cancel(true)）会取消底层
     * streamingHandle 并以 connectionClosed=true 返回，外层循环据此停止后续节。
     *
     * @param model             流式模型
     * @param messages          本段消息（完整上下文，调用方组装）
     * @param emitter           SSE 发射器（多段共用）
     * @param callback          仅提供 getOperationName/formatMessageEvent/onThinkingToken
     * @param logContext        日志上下文，可为 null
     * @param segmentTimeoutMs  本段超时（毫秒）
     */
    public static SegmentResult streamSegment(
            StreamingChatModel model,
            List<ChatMessage> messages,
            SseEmitter emitter,
            Callback callback,
            AiCallLogContext logContext,
            long segmentTimeoutMs) {

        final int maxContinuations = 2;
        final StringBuilder sink = new StringBuilder();
        List<ChatMessage> currentMessages = messages;
        SegmentResult round = null;

        for (int attempt = 0; attempt <= maxContinuations; attempt++) {
            round = runSegmentRound(model, currentMessages, emitter, callback,
                    logContext, segmentTimeoutMs, sink);

            if (round.connectionClosed() || round.failed()) {
                return round;
            }
            boolean truncated = "LENGTH".equals(round.finishReason());
            if (!truncated || round.text().isBlank() || attempt == maxContinuations) {
                return round;
            }

            // LENGTH 截断续写：把"已输出末尾 300 字"塞进续写指令，让模型从断点接着写。
            // 关键：续写轮的 messages 固定 = 原始 messages + 一条续写 UserMessage（含已输出末尾），
            // 不采用"AiMessage(前缀)+UserMessage"双消息累积——否则多轮截断时前缀消息越滚越多。
            // 取末尾而非全文：续写只需"断点上下文"，全文塞进 user 消息会顶爆上下文且模型
            // 倾向重读已给内容导致重复输出。sink 是跨轮累积的本段输出，续写轮继续 append，
            // round.text() 始终是本段全文（含前缀），前端收到的 message 流不会重复
            // （续写轮模型若复读前缀，由段内 LENGTH 不再续写兜底——最多 2 次）。
            String tail = sink.length() > 300 ? sink.substring(sink.length() - 300) : sink.toString();
            log.warn("[诊断] 流式段落被 token 上限截断，以已输出末尾 300 字为断点续写（第{}次）: op={}, charsSoFar={}",
                    attempt + 1, callback.getOperationName(), sink.length());
            List<ChatMessage> continued = new ArrayList<>(messages);
            continued.add(UserMessage.from(
                    "【续写契约】你刚才的本节输出因长度限制中断。以下是已输出的最后一段（原文照录）："
                            + "「" + tail + "」\n"
                            + "请从断点直接接着往下写（不要重复上述已输出内容、不要重新开场、不要加总结语），"
                            + "直至本节自然结束。"));
            currentMessages = continued;
        }

        // 不可达：循环每轮都有 return 路径
        return round;
    }

    /**
     * 跑一轮单段流式（一次模型调用），桥接回调 → 同步返回。
     */
    private static SegmentResult runSegmentRound(
            StreamingChatModel model,
            List<ChatMessage> messages,
            SseEmitter emitter,
            Callback callback,
            AiCallLogContext logContext,
            long segmentTimeoutMs,
            StringBuilder sink) {

        CommonUtils.logAiMessages(callback.getOperationName(), messages);
        final long startTime = System.currentTimeMillis();

        // sink 是"本段输出"的跨轮累积（首轮空；LENGTH 续写轮直接 append）。
        // 错误/超时路径返回时读 sink 即得已流出的部分文本——这正是需要的"部分输出"，
        // 因此本方法不在轮首清空 sink；清空由调用方（streamSegment 每次新建 sink）负责。
        final CompletableFuture<SegmentResult> done = new CompletableFuture<>();
        final boolean[] connectionClosed = {false};
        final int[] thinkingTokenCount = {0};
        final StringBuilder fullThinking = new StringBuilder();
        final AtomicReference<StreamingHandle> handleRef = new AtomicReference<>();
        final ThoughtTagParser thoughtParser = new ThoughtTagParser();

        model.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
                captureHandle(context);
                if (connectionClosed[0]) return;
                String thinking = partialThinking.text();
                if (thinking != null && !thinking.isEmpty()) {
                    thinkingTokenCount[0]++;
                    fullThinking.append(thinking);
                    callback.onThinkingToken(thinking, emitter);
                }
            }

            @Override
            public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                captureHandle(context);
                if (connectionClosed[0]) return;
                String text = partialResponse.text();
                if (text == null || text.isEmpty()) return;

                ThoughtTagParser.Result parsed = thoughtParser.process(text);
                if (parsed.hasThinking()) {
                    thinkingTokenCount[0]++;
                    fullThinking.append(parsed.thinking());
                    callback.onThinkingToken(parsed.thinking(), emitter);
                }
                if (parsed.hasMessage()) {
                    String message = parsed.message();
                    // 跨轮累积：sink 是本段全文（首轮=本轮输出；LENGTH 续写轮=已输出前缀+续写输出）
                    sink.append(message);
                    String eventData = callback.formatMessageEvent(message);
                    if (!SseHelper.safeSendEvent(emitter, "message", eventData)) {
                        connectionClosed[0] = true;
                        StreamingHandle h = handleRef.get();
                        if (h != null) h.cancel();
                        done.complete(new SegmentResult(sink.toString(), null, true, null));
                    }
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                FinishReason fr = completeResponse.finishReason();
                String frName = fr != null ? fr.name() : null;
                // 注意：本段全文 = sink（跨轮累积）；本轮增量 = content
                String content = sink.toString().trim();

                int inputTokens = completeResponse.tokenUsage() != null && completeResponse.tokenUsage().inputTokenCount() != null
                        ? completeResponse.tokenUsage().inputTokenCount() : 0;
                int outputTokens = completeResponse.tokenUsage() != null && completeResponse.tokenUsage().outputTokenCount() != null
                        ? completeResponse.tokenUsage().outputTokenCount() : 0;
                if (fr == FinishReason.LENGTH) {
                    log.warn("[诊断] 流式段落被 token 上限截断 (finishReason=LENGTH): op={}, scene={}, model={}, "
                                    + "outputTokens={}, contentChars={}",
                            callback.getOperationName(),
                            logContext != null ? logContext.scene() : null,
                            logContext != null ? logContext.modelName() : null,
                            outputTokens, content.length());
                }
                CommonUtils.logAiStreamingSummary(callback.getOperationName(),
                        logContext != null ? logContext.scene() : null,
                        logContext != null ? logContext.modelName() : null,
                        logContext != null ? logContext.configName() : null,
                        logContext != null ? logContext.thinkingMode() : null,
                        logContext != null && logContext.thinkingEnabled(),
                        logContext != null ? logContext.reasoningEffort() : null,
                        messages, content, fullThinking.isEmpty() ? null : fullThinking.toString(),
                        System.currentTimeMillis() - startTime, inputTokens, outputTokens);

                done.complete(new SegmentResult(content, frName, false, null));
            }

            @Override
            public void onError(Throwable error) {
                String content = sink.toString().trim();
                log.warn("流式段落错误: op={}, error={}, partialChars={}",
                        callback.getOperationName(), error.getMessage(), content.length());
                done.complete(new SegmentResult(content, "ERROR", false,
                        error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()));
            }

            private void captureHandle(PartialResponseContext context) {
                if (handleRef.get() == null && context != null) {
                    handleRef.set(context.streamingHandle());
                }
            }

            private void captureHandle(PartialThinkingContext context) {
                if (handleRef.get() == null && context != null) {
                    handleRef.set(context.streamingHandle());
                }
            }
        });

        try {
            return done.get(segmentTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            StreamingHandle h = handleRef.get();
            if (h != null) h.cancel();
            return new SegmentResult(sink.toString().trim(), "INTERRUPTED", true, null);
        } catch (java.util.concurrent.TimeoutException te) {
            StreamingHandle h = handleRef.get();
            if (h != null) h.cancel();
            log.warn("流式段落超时（{}ms）: op={}", segmentTimeoutMs, callback.getOperationName());
            return new SegmentResult(sink.toString().trim(), "TIMEOUT", false, "段落生成超时");
        } catch (java.util.concurrent.ExecutionException ee) {
            return new SegmentResult(sink.toString().trim(), "ERROR", false,
                    ee.getCause() != null ? ee.getCause().getMessage() : ee.getMessage());
        }
    }
}
