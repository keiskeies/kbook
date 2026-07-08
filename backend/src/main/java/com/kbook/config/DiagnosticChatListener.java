package com.kbook.config;

import com.kbook.common.util.CommonUtils;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * ChatModel 请求/响应监听器 — 诊断用：打印系统消息和模型返回
 */
@Slf4j
public class DiagnosticChatListener implements ChatModelListener {

    /**
     * 请求发送时的回调 — 打印 SystemMessage 摘要
     */
    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        List<ChatMessage> messages = ctx.chatRequest().messages();
        // 找 SystemMessage 打印
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage sm) {
                String text = sm.text().replaceAll("\\n", "");
                log.info("📤 [AI 请求] SystemMessage ({}字符): {}",
                        text.length(),
                        CommonUtils.truncateText(text, 100));
                return;
            }
        }
        log.warn("📤 [AI 请求] ⚠️ 未找到 SystemMessage！消息数={}", messages.size());
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
