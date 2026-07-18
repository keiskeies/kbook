package com.kbook.config;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import lombok.extern.slf4j.Slf4j;

/**
 * ChatModel 请求/响应监听器 — 诊断用：打印系统消息和模型返回
 */
@Slf4j
public class DiagnosticChatListener implements ChatModelListener {

    /**
     * 请求发送时的回调 — 空实现。
     * <p>
     * 完整请求消息已由 {@code CommonUtils.logAiMessages} 在 DEBUG 级别打印，
     * 统一摘要由 {@code CommonUtils.logAiSummary} 打印，此处不再重复。
     */
    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        // 空实现：避免与 logAiMessages / logAiSummary 重复
    }

    /**
     * 响应成功时的回调 — 空实现，避免日志爆炸
     */
    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        // 不打印模型响应内容，避免日志爆炸
    }

    /**
     * 请求出错时的回调 — 记录错误日志
     */
    @Override
    public void onError(ChatModelErrorContext ctx) {
        log.error("📤 [AI 请求] 模型调用失败: {}", ctx.error().getMessage());
    }
}
