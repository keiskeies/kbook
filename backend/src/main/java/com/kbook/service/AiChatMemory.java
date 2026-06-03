package com.kbook.service;

import com.kbook.constants.AiPromptConstants;
import com.kbook.entity.AiConversation;
import com.kbook.repository.AiConversationRepository;
import com.kbook.repository.AiSessionRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * LangChain4j ChatMemory 存储实现
 * <p>
 * 每次直接从数据库加载对话历史，不使用内存缓存，支持压缩。
 * 使用 compressed_content 字段来组装消息。
 * <p>
 * AiProviderConfigService 通过 ObjectProvider 延迟注入，避免循环依赖：
 * AiChatMemory → AiProviderConfigService → AiChatMemory
 */
@Slf4j
@Component
public class AiChatMemory implements ChatMemoryStore {

    /**
     * 按 sessionId 分组存储对话消息的内存容器
     */
    private final ConcurrentHashMap<String, List<ChatMessage>> store = new ConcurrentHashMap<>();

    /**
     * 默认最大 token 数
     */
    private static final int DEFAULT_MAX_TOKENS = 32768;

    /**
     * token 与字符的估算比例（1 token ≈ 1.5 字符）
     */
    private static final double TOKEN_TO_CHAR_RATIO = 1.5;

    /**
     * 压缩触发阈值：历史达到上限的 80% 时触发压缩
     */
    private static final double COMPRESS_TRIGGER_RATIO = 0.8;

    /**
     * 压缩目标比例：压缩后目标为上限的 60%
     */
    private static final double COMPRESS_TARGET_RATIO = 0.6;

    /**
     * 估算字符数
     */
    private static final Function<String, Integer> CHAR_LENGTH_ESTIMATE =
            s -> s != null ? s.length() : 0;

    private final AiSessionRepository sessionRepository;
    private final AiConversationRepository conversationRepository;
    private final ObjectProvider<AiProviderConfigService> providerConfigServiceProvider;
    private final ChatModelManager chatModelManager;

    public AiChatMemory(AiSessionRepository sessionRepository,
                        AiConversationRepository conversationRepository,
                        ObjectProvider<AiProviderConfigService> providerConfigServiceProvider,
                        ChatModelManager chatModelManager) {
        this.sessionRepository = sessionRepository;
        this.conversationRepository = conversationRepository;
        this.providerConfigServiceProvider = providerConfigServiceProvider;
        this.chatModelManager = chatModelManager;
    }

    /**
     * 获取指定会话的消息列表
     * <p>
     * 每次从数据库加载历史，加载前检查并压缩超长历史，使用 compressed_content 字段。
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {


        String sessionId = memoryId.toString();

        List<ChatMessage> messages = new CopyOnWriteArrayList<>(store.getOrDefault(sessionId, List.of()));
        if (!messages.isEmpty()) {
            return messages;
        }

        // 先查 AiSession 获取 userId
        var sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            log.debug("会话不存在: sessionId={}", sessionId);
            return new CopyOnWriteArrayList<>();
        }

        Long userId = sessionOpt.get().getUserId();

        // 从数据库加载历史，使用 compressed_content
        List<AiConversation> history = conversationRepository
                .findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
        messages = new ArrayList<>();
        messages.add(SystemMessage.from(AiPromptConstants.AI_CHAT_SYSTEM_PROMPT));

        for (AiConversation conv : history) {
            String content = conv.getCompressedContent();
            if (content == null || content.isBlank()) continue;

            if ("user".equals(conv.getRole())) {
                messages.add(UserMessage.from(content));
            } else if ("assistant".equals(conv.getRole())) {
                messages.add(AiMessage.from(content));
            }
        }

        log.debug("从数据库加载对话历史: sessionId={}, userId={}, messages={}",
                sessionId, userId, messages.size());

        return new CopyOnWriteArrayList<>(messages);
    }

    /**
     * 更新指定会话的消息列表（全量替换）
     *
     * @param memoryId 会话标识（sessionId）
     * @param messages 新的完整消息列表
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = memoryId.toString();
        store.put(sessionId, new CopyOnWriteArrayList<>(messages));
    }

    /**
     * 删除指定会话的所有消息
     *
     * @param memoryId 会话标识（sessionId）
     */
    @Override
    public void deleteMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        store.remove(sessionId);
    }

    /**
     * 按需压缩会话历史消息，防止超出模型 token 限制
     * <p>
     * 当会话历史总字符数超过触发阈值时，从最老的未压缩 AI 回复开始逐条压缩。
     * 通用对话无 RAG 上下文，直接用历史长度判断。
     */
    public void compressHistoryIfNeeded(Long userId, String sessionId) {
        // 计算字符数限制和压缩目标值
        AiProviderConfigService configService = providerConfigServiceProvider.getIfAvailable();
        Integer maxTokens = configService != null ? configService.getActiveMaxTokens() : null;
        int tokenLimit = maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS;
        int charLimit = (int) (tokenLimit * TOKEN_TO_CHAR_RATIO);
        long compressTarget = (long) (charLimit * COMPRESS_TARGET_RATIO);

        try {
            // 检查是否需要压缩（直接用历史长度，无额外 overhead）
            long totalChars = conversationRepository.sumCompressedContentLength(userId, sessionId);
            log.debug("压缩检查: sessionId={}, history={}/{} ({}%)",
                    sessionId, totalChars, charLimit,
                    charLimit > 0 ? totalChars * 100 / charLimit : 0);
            if (totalChars < charLimit * COMPRESS_TRIGGER_RATIO) return;

            // 循环压缩最老的未压缩 AI 回复
            int compressed = 0;
            while (totalChars >= Math.max(compressTarget, 0)) {
                AiConversation target = conversationRepository
                        .findFirstUncompressedAssistant(userId, sessionId)
                        .orElse(null);
                if (target == null) {
                    log.info("无可压缩的 AI 回复: sessionId={}, compressed={}", sessionId, compressed);
                    return;
                }

                String original = target.getContent();
                String summary = chatModelManager.compressContent(original);
                if (summary == null) {
                    log.warn("压缩失败(跳过): sessionId={}, convId={}", sessionId, target.getId());
                    break;
                }

                target.setCompressedContent(summary);
                conversationRepository.save(target);
                totalChars = totalChars - CHAR_LENGTH_ESTIMATE.apply(original) + CHAR_LENGTH_ESTIMATE.apply(summary);
                compressed++;
                log.info("压缩历史消息: sessionId={}, convId={}, {}→{} chars",
                        sessionId, target.getId(), original.length(), summary.length());
            }

            if (compressed > 0) {
                log.info("压缩完成: sessionId={}, compressed={}条", sessionId, compressed);
            }
        } catch (Exception e) {
            log.warn("压缩历史消息失败: {}", e.getMessage());
        }
    }

}
