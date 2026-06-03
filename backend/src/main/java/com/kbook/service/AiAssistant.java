package com.kbook.service;

import com.kbook.constants.AiPromptConstants;
import dev.langchain4j.service.*;

/**
 * LangChain4j AI 助理接口
 * <p>
 * 使用 @SystemMessage 定义系统提示词，@UserMessage 定义用户消息模板
 *
 * &#064;MemoryId  自动关联 ChatMemory
 * &#064;V("userId")  注入当前用户ID到系统提示词模板，让AI知道对话用户身份
 */
@SystemMessage(AiPromptConstants.AI_CHAT_SYSTEM_PROMPT)
public interface AiAssistant {

    /**
     * 非流式对话
     */
    String chat(@MemoryId String sessionId, @V("userId") Long userId, @UserMessage String userMessage);

    /**
     * 非流式对话 — 返回完整响应（含 token 用量和 thinking）
     */
    Result<String> chatWithResponse(
            @MemoryId String sessionId,
            @V("userId") Long userId,
            @UserMessage String userMessage
    );

    /**
     * 真正的 Token 级流式对话
     */
    TokenStream chatStream(@MemoryId String sessionId, @V("userId") Long userId, @UserMessage String userMessage);
}
