package com.kbook.service.ai;

import com.kbook.config.ChatModelFactory;
import com.kbook.constants.AiPromptConstants;
import com.kbook.entity.AiProviderConfig;
import com.kbook.entity.AiScene;
import com.kbook.repository.AiProviderConfigRepository;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * AI 配置服务 — 全部从数据库读取，不再回退 yml
 * <p>
 * CHAT 用途分两个角色：
 * - QA: 大型问答（图书问答、AI助理、圆桌派、奇葩说）
 * - TOOL: 小型工具（元数据推断、内容压缩、查询扩展等）
 * <p>
 * 配置缓存存放在 Redis，键格式：
 * - kbook:ai:config:{purpose}:{id} — 单条配置
 * - kbook:ai:config:active:{purpose}:{role} — 某用途角色当前启用的配置
 */
@Slf4j
@Service
public class AiProviderConfigService {

    private static final String REDIS_KEY_PREFIX = "kbook:ai:config:";
    private static final long REDIS_TTL_HOURS = 2;

    private final AiChatMemory chatMemoryStore;
    private final ObjectProvider<AiToolService> toolServiceProvider;
    private final ChatModelFactory chatModelFactory;
    private final AiProviderConfigRepository configRepository;
    private final ObjectProvider<BookAdminChatService> bookAdminChatServiceProvider;
    private final AiSceneConfigService sceneConfigService;
    private final StringRedisTemplate redisTemplate;

    /** AI 助理 AiAssistant 缓存，key = configId，配置变更时清空 */
    private final ConcurrentHashMap<Long, AiAssistant> chatAssistantCache = new ConcurrentHashMap<>();

    public AiProviderConfigService(
            AiChatMemory chatMemoryStore,
            ObjectProvider<AiToolService> toolServiceProvider,
            ChatModelFactory chatModelFactory,
            AiProviderConfigRepository configRepository,
            ObjectProvider<BookAdminChatService> bookAdminChatServiceProvider,
            @Lazy AiSceneConfigService sceneConfigService,
            StringRedisTemplate redisTemplate) {
        this.chatMemoryStore = chatMemoryStore;
        this.toolServiceProvider = toolServiceProvider;
        this.chatModelFactory = chatModelFactory;
        this.configRepository = configRepository;
        this.bookAdminChatServiceProvider = bookAdminChatServiceProvider;
        this.sceneConfigService = sceneConfigService;
        this.redisTemplate = redisTemplate;
    }

    // ==================== 查询 ====================

    /**
     * 获取 CHAT 用途中 roles 包含指定角色的启用配置
     */
    public AiProviderConfig getChatConfigByRole(String role) {
        return configRepository.findByPurposeAndEnabledAndRolesContaining(
                        AiProviderConfig.Purpose.CHAT.name(), "%" + role + "%")
                .orElse(null);
    }

    /**
     * 获取指定用途的所有配置（按更新时间降序）
     */
    public List<AiProviderConfig> getConfigsByPurpose(String purpose) {
        return configRepository.findByPurposeOrderByCreatedAtDesc(purpose);
    }

    /**
     * 获取指定用途的所有启用配置（按更新时间降序）
     */
    public List<AiProviderConfig> getEnabledConfigsByPurpose(String purpose) {
        return configRepository.findByPurposeAndEnabledTrueOrderByCreatedAtDesc(purpose);
    }

    /**
     * 获取指定用途的首个启用配置
     */
    public AiProviderConfig getFirstEnabledByPurpose(String purpose) {
        return configRepository.findFirstByPurposeAndEnabledTrueOrderByUpdatedAtDesc(purpose).orElse(null);
    }

    // ==================== 缓存管理 ====================

    /**
     * 刷新配置到 Redis 缓存
     */
    public void cacheConfig(AiProviderConfig config) {
        if (config == null || config.getId() == null) return;
        String key = REDIS_KEY_PREFIX + config.getPurpose() + ":" + config.getId();
        try {
            redisTemplate.opsForValue().set(key, String.valueOf(config.getId()),
                    REDIS_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Redis 缓存写入失败: {}", e.getMessage());
        }
    }

    /**
     * 从 Redis 删除缓存的配置
     */
    public void evictConfigCache(Long configId, String purpose) {
        String key = REDIS_KEY_PREFIX + purpose + ":" + configId;
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis 缓存删除失败: {}", e.getMessage());
        }
    }

    /**
     * 使所有 AI 模型缓存失效（配置变更时调用）
     */
    public void invalidateAllCache() {
        // Redis 缓存通过 TTL 自动过期，这里通知各个 Assistant 重建
        invalidateChatAssistantCache();
        bookAdminChatServiceProvider.ifAvailable(BookAdminChatService::clearCache);
        log.info("AI 配置缓存已全部失效");
    }

    /**
     * 使对话 Assistant 缓存失效
     */
    public void invalidateChatAssistantCache() {
        chatAssistantCache.clear();
        log.debug("对话 Assistant 缓存已清空");
    }

    /**
     * 清理当前 Assistant 缓存（错误恢复时调用，使下一个请求重新创建 Assistant）
     */
    public void clearAssistantCache() {
        invalidateChatAssistantCache();
    }

    // ==================== 对话 AI ====================

    /**
     * 获取 AI 助理的 AiAssistant（带缓存）。
     * <p>
     * 配置解析走 {@link AiSceneConfigService#resolveConfig(AiScene)} 场景路由：
     * 优先使用 AiScene.AI_ASSISTANT 显式绑定的配置，回退到 Category.QA 默认配置。
     */
    public AiAssistant getChatAssistant() {
        AiProviderConfig config = sceneConfigService.resolveConfig(AiScene.AI_ASSISTANT);
        if (config == null) {
            throw new IllegalStateException("未找到可用 AI 助理对话配置（AiScene.AI_ASSISTANT 未绑定且无 QA 默认配置）");
        }
        return chatAssistantCache.computeIfAbsent(config.getId(),
                id -> buildChatAssistant("AI_ASSISTANT", config));
    }

    /**
     * 构建指定角色的 AiAssistant
     */
    private AiAssistant buildChatAssistant(String role, AiProviderConfig config) {

        boolean toolsSupported = chatModelFactory.isToolsSupported(config);

        log.info("构建对话 AI Assistant: role={}, model={}, toolsEnabled={}, baseUrl={}",
                role, config.getModelName(), toolsSupported, config.getBaseUrl());

        // AI 助理用自己的场景配置（AI_ASSISTANT），不再误用 BOOK_QA 场景
        ChatModel chatModel = chatModelFactory.buildForScene(AiScene.AI_ASSISTANT);
        StreamingChatModel streamingModel = chatModelFactory.buildStreamingForScene(AiScene.AI_ASSISTANT);

        var builder = AiServices.builder(AiAssistant.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingModel)
                .chatMemoryProvider(sessionId -> dev.langchain4j.memory.chat.MessageWindowChatMemory.builder()
                        .id(sessionId)
                        .maxMessages(AiPromptConstants.ADMIN_MAX_MESSAGES)
                        .chatMemoryStore(chatMemoryStore)
                        .build());

        if (toolsSupported) {
            Object toolObj = toolServiceProvider.getObject();
            builder.tools(toolObj);
            log.info("  已注册 AI 工具: class={}", toolObj.getClass().getName());
        }

        return builder.build();
    }

    // ==================== 内部 ====================

    @Transactional
    public AiProviderConfig saveConfig(AiProviderConfig config) {
        AiProviderConfig saved = configRepository.save(config);
        cacheConfig(saved);
        invalidateAllCache();
        return saved;
    }

    @Transactional
    public void deleteConfig(Long id) {
        AiProviderConfig config = configRepository.findById(id).orElse(null);
        if (config != null) {
            evictConfigCache(id, config.getPurpose());
            configRepository.deleteById(id);
            invalidateAllCache();
        }
    }
}
