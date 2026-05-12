package com.kbook.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * LangChain4j ChatMemory 存储实现
 * <p>
 * 基于内存的 ChatMemoryStore，按 sessionId 分组存储对话上下文。
 * 持久化由 AiChatService 负责（保存到 AiConversation 表），
 * 此处仅负责为 LangChain4j 框架提供运行时的消息窗口。
 * <p>
 * 关键：存储时剥离 AiMessage 中的 thinking 内容，
 * 防止思考 token 在对话历史中累积，导致后续请求越来越慢且模型持续思考。
 */
@Slf4j
@Component
public class AiChatMemory implements ChatMemoryStore {

    private final ConcurrentHashMap<String, List<ChatMessage>> store = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        return new CopyOnWriteArrayList<>(store.getOrDefault(sessionId, List.of()));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = memoryId.toString();
        // 剥离 AiMessage 中的 thinking 内容，防止思考 token 在对话历史中累积
        List<ChatMessage> stripped = messages.stream()
                .map(this::stripThinking)
                .collect(Collectors.toList());
        store.put(sessionId, new CopyOnWriteArrayList<>(stripped));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        store.remove(sessionId);
    }

    /**
     * 剥离 AiMessage 中的 thinking 内容
     * <p>
     * Qwen3 等思考模型会在响应中生成大量 thinking token，
     * 如果这些 token 被存入 ChatMemory，后续请求会把它们作为上下文发回模型，
     * 导致：(1) 输入 token 数暴涨 (2) 模型看到历史中有思考，继续思考
     * <p>
     * 剥离后只保留文本内容，大幅减少上下文长度和响应时间。
     */
    private ChatMessage stripThinking(ChatMessage message) {
        if (message instanceof AiMessage aiMsg && aiMsg.thinking() != null && !aiMsg.thinking().isEmpty()) {
            log.debug("剥离 thinking 内容: sessionId 中 AiMessage thinking 长度={}, text 长度={}",
                    aiMsg.thinking().length(), aiMsg.text() != null ? aiMsg.text().length() : 0);
            return AiMessage.aiMessage(aiMsg.text());
        }
        return message;
    }
}
