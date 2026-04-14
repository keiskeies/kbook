package com.kbook.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * LangChain4j ChatMemory 存储实现
 * <p>
 * 基于内存的 ChatMemoryStore，按 sessionId 分组存储对话上下文。
 * 持久化由 AiChatService 负责（保存到 AiConversation 表），
 * 此处仅负责为 LangChain4j 框架提供运行时的消息窗口。
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
        store.put(sessionId, new CopyOnWriteArrayList<>(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        store.remove(sessionId);
    }
}
