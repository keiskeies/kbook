package com.kbook.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 辅助工具 — 统一 SSE 错误处理和友好错误信息提取
 */
@Slf4j
public final class SseHelper {

    private SseHelper() {}

    /**
     * 发送错误事件并完成 SSE 连接
     *
     * @param emitter SSE 发射器
     * @param message 错误消息
     */
    public static void sendErrorAndComplete(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(message));
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
        } catch (Exception e) {
            log.warn("SSE 发送错误事件失败（连接可能已关闭）: {}", e.getMessage());
        }
    }

    /**
     * 从异常信息中提取用户友好的错误提示
     * 合并自 AiChatService / BookAdminChatService / BookChatService 中的重复方法
     *
     * @param e 异常对象
     * @return 用户友好的错误提示文本
     */
    public static String extractFriendlyError(Throwable e) {
        if (e == null) return "AI 响应异常，请稍后重试。";
        String msg = e.getMessage();
        if (msg == null) return "AI 响应异常，请稍后重试。";
        if (msg.contains("not found")) return "AI 模型不存在，请管理员检查模型配置。";
        if (msg.contains("timed out") || msg.contains("Timeout")) return "AI 响应超时，请检查模型服务是否正常运行。";
        if (msg.contains("Connection refused") || msg.contains("connect")) return "无法连接到 AI 模型服务，请检查端点地址和网络。";
        if (msg.contains("401") || msg.contains("Unauthorized") || msg.contains("api key")) return "AI 服务认证失败，请检查 API Key 配置。";
        if (msg.contains("429") || msg.contains("rate limit")) return "AI 服务请求过于频繁，请稍后重试。";
        return "AI 响应异常，请稍后重试。";
    }

    /**
     * 安全发送 SSE 事件 — 失败时返回 false（不中断线程）
     *
     * @param emitter   SSE 发射器
     * @param eventName 事件名称
     * @param data      事件数据
     * @return 是否发送成功
     */
    public static boolean safeSendEvent(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            return true;
        } catch (Exception e) {
            log.debug("SSE 发送事件 [{}] 失败（连接可能已关闭）: {}", eventName, e.getMessage());
            return false;
        }
    }

    /**
     * 判断异常是否值得重试（与 RetryableChatModel 判断逻辑一致）。
     * <p>
     * 可重试：429 限速、5xx 服务端错误、网络层异常（超时/DNS/IO）。
     * 不可重试：4xx 客户端错误、编程异常等。
     */
    public static boolean isRetryableError(Throwable e) {
        if (e == null) return false;
        if (isRateLimit(e)) return true;
        if (isServerError(e)) return true;
        if (e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.UnknownHostException
                || e instanceof java.io.IOException) {
            return true;
        }
        return isRetryableError(e.getCause());
    }

    private static boolean isRateLimit(Throwable e) {
        String name = e.getClass().getName();
        if ("dev.langchain4j.model.openai.OpenAiHttpException".equals(name)) {
            try {
                int code = (int) e.getClass().getMethod("statusCode").invoke(e);
                if (code == 429) return true;
            } catch (Exception ignored) {}
        }
        String msg = e.getMessage();
        if (msg != null && (msg.contains("429") || msg.contains("Too Many Requests")
                || msg.contains("rate_limit") || msg.contains("Rate Limit"))) {
            return true;
        }
        return e.getCause() != null && isRateLimit(e.getCause());
    }

    private static boolean isServerError(Throwable e) {
        String msg = e.getMessage();
        if (msg != null && (msg.contains(" 500 ") || msg.contains(" 502 ")
                || msg.contains(" 503 ") || msg.contains(" 504 ")
                || msg.contains("Internal Server Error")
                || msg.contains("Bad Gateway")
                || msg.contains("Service Unavailable")
                || msg.contains("Gateway Timeout"))) {
            return true;
        }
        return e.getCause() != null && isServerError(e.getCause());
    }
}
