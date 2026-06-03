package com.kbook.service;

import com.kbook.common.util.CommonUtils;
import com.kbook.config.ChatModelFactory;
import com.kbook.entity.AiConversation;
import com.kbook.repository.AiConversationRepository;
import com.kbook.repository.AiSessionRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.concurrent.CopyOnWriteArrayList;

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

    /** 默认最大 token 数 */
    private static final int DEFAULT_MAX_TOKENS = 32768;

    /** token 与字符的估算比例（1 token ≈ 1.5 字符） */
    private static final double TOKEN_TO_CHAR_RATIO = 1.5;

    /** 压缩触发阈值：历史达到上限的 80% 时触发压缩 */
    private static final double COMPRESS_TRIGGER_RATIO = 0.8;

    /** 压缩目标比例：压缩后目标为上限的 60% */
    private static final double COMPRESS_TARGET_RATIO = 0.6;

    /** 估算字符数 */
    private static final Function<String, Integer> CHAR_LENGTH_ESTIMATE =
            s -> s != null ? s.length() : 0;

    private final AiSessionRepository sessionRepository;
    private final AiConversationRepository conversationRepository;
    private final ObjectProvider<AiProviderConfigService> providerConfigServiceProvider;
    private final ChatModelFactory chatModelFactory;

    public AiChatMemory(AiSessionRepository sessionRepository,
                        AiConversationRepository conversationRepository,
                        ObjectProvider<AiProviderConfigService> providerConfigServiceProvider,
                        ChatModelFactory chatModelFactory) {
        this.sessionRepository = sessionRepository;
        this.conversationRepository = conversationRepository;
        this.providerConfigServiceProvider = providerConfigServiceProvider;
        this.chatModelFactory = chatModelFactory;
    }

    /**
     * 获取指定会话的消息列表
     * <p>
     * 每次从数据库加载历史，加载前检查并压缩超长历史，使用 compressed_content 字段。
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String sessionId = memoryId.toString();

        // 先查 AiSession 获取 userId
        var sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            log.debug("会话不存在: sessionId={}", sessionId);
            return new CopyOnWriteArrayList<>();
        }

        Long userId = sessionOpt.get().getUserId();

        // 检查并压缩历史
        compressHistoryIfNeeded(userId, sessionId);

        // 从数据库加载历史，使用 compressed_content
        List<AiConversation> history = conversationRepository
                .findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
        List<ChatMessage> messages = new ArrayList<>();

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
     * 更新消息列表（本实现不使用内存缓存，直接忽略）
     * <p>
     * LangChain4j 框架在对话完成后会自动调用此方法，但我们每次都从数据库加载，
     * 所以不需要缓存更新。
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // 直接忽略，我们每次都从数据库读
        log.debug("updateMessages 被调用（已忽略）: sessionId={}, messages={}",
                memoryId.toString(), messages.size());
    }

    /**
     * 删除指定会话的所有消息
     */
    @Override
    public void deleteMessages(Object memoryId) {
        // 不删除，会话历史通过 AiChatService.deleteSession 统一管理
        log.debug("deleteMessages 被调用（已忽略）: sessionId={}", memoryId.toString());
    }

    /**
     * 按需压缩会话历史消息，防止超出模型 token 限制
     * <p>
     * 当会话历史总字符数超过触发阈值时，从最老的未压缩 AI 回复开始逐条压缩。
     * 通用对话无 RAG 上下文，直接用历史长度判断。
     */
    private void compressHistoryIfNeeded(Long userId, String sessionId) {
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
                String summary = compressContent(original);
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

    /**
     * 将 AI 回复压缩到 200 字以内
     */
    private String compressContent(String original) {
        if (original == null || original.length() <= 200) return original;
        try {
            long startTime = System.currentTimeMillis();
            ChatModel chatModel = chatModelFactory.buildChatModelWithoutThinkingFromYml();
            if (chatModel == null) return null;

            String prompt = String.format(
                    "将以下内容压缩到200字以内，保留核心观点和信息：\n\n%s", original);
            ChatResponse response = chatModel.chat(List.of(UserMessage.from(prompt)));
            long elapsed = System.currentTimeMillis() - startTime;

            int inputTokens = response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null
                    ? response.tokenUsage().inputTokenCount() : 0;
            int outputTokens = response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null
                    ? response.tokenUsage().outputTokenCount() : 0;

            String compressed = response.aiMessage().text();
            if (compressed != null && !compressed.isBlank()) {
                compressed = compressed.trim();
                CommonUtils.logAiCall("历史压缩", elapsed, inputTokens, outputTokens,
                        String.format("%d→%d chars", original.length(), compressed.length()));
                return compressed;
            }
        } catch (Exception e) {
            log.warn("调用AI压缩内容失败: {}", e.getMessage());
        }
        return null;
    }
}
