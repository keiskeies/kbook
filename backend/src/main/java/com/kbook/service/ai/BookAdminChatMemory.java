package com.kbook.service.ai;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BookAdminAssistant 专用的 ChatMemory 存储实现
 * <p>
 * 与通用的 AiChatMemory 不同：
 * 1. 不注入系统提示词（BookAdminAssistant 接口已有 @SystemMessage 注解）
 * 2. 保持工具调用的中间状态（ToolExecutionResultMessage）不丢失
 * <p>
 * 工具调用流程中，LangChain4j 会：
 * 1. 用户提问 → UserMessage
 * 2. 模型决定调用工具 → AiMessage(tool_call)
 * 3. 工具执行 → ToolExecutionResultMessage  
 * 4. 模型总结 → AiMessage(final_response)
 * <p>
 * 这些消息必须完整保存在内存中，不能在工具调用过程中清空。
 */
@Slf4j
@Component("bookAdminChatMemory")
public class BookAdminChatMemory implements ChatMemoryStore {

    /**
     * 按 sessionId 分组存储对话消息的内存容器
     * 注意：这里必须保持完整的消息链，包括 ToolExecutionResultMessage
     */
    private final ConcurrentHashMap<String, List<ChatMessage>> store = new ConcurrentHashMap<>();

    /**
     * 获取指定会话的消息列表
     * <p>
     * 优先从内存获取（保持工具调用的中间状态），内存为空时从数据库加载历史。
     * <b>重要：不注入 SystemMessage</b>，由 BookAdminAssistant 接口的 @SystemMessage 注解自动注入。
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        return new CopyOnWriteArrayList<>(store.getOrDefault(sessionId, List.of()));
    }

    /**
     * 更新指定会话的消息列表（全量替换）
     * <p>
     * 这是工具调用过程中的关键步骤：LangChain4j 在工具执行后会更新消息列表，
     * 添加 ToolExecutionResultMessage 和最终回复。
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = memoryId.toString();
        store.put(sessionId, new CopyOnWriteArrayList<>(messages));
        log.debug("更新管理员对话消息: sessionId={}, messages={}", sessionId, messages.size());
    }

    /**
     * 删除指定会话的所有消息
     * <p>
     * 仅在会话结束或用户主动删除时调用，不应在每次请求时调用。
     */
    @Override
    public void deleteMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        store.remove(sessionId);
        log.debug("删除管理员对话消息: sessionId={}", sessionId);
    }

}