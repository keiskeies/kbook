package com.kbook.service.ai.core;

import com.kbook.config.AiConfigCacheInvalidator;
import com.kbook.config.ChatModelFactory;
import com.kbook.entity.AiProviderConfig;
import com.kbook.entity.AiScene;
import com.kbook.service.ai.AiAssistant;
import com.kbook.service.ai.AiChatMemory;
import com.kbook.service.ai.AiToolService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import com.kbook.constants.ai.AiAssistantPrompts;

/**
 * 对话 Assistant 提供者 — 构建/缓存 AI 助理的 {@link AiAssistant} 实例。
 * <p>
 * 从 {@link AiProviderConfigService} 抽出（M4 解循环依赖）：原配置服务为构建 Assistant
 * 反向持有 AiToolService/AiChatMemory 等上层聊天 Bean，形成
 * 「配置服务 → 聊天层 → 模型工厂 → 场景配置 → 配置服务」的环。抽出后配置服务只剩纯数据职责。
 * <p>
 * 缓存失效：本类向 {@link AiConfigCacheInvalidator} 注册自己的清空回调，
 * 配置变更（本副本或其他副本）时自动重建。
 */
@Slf4j
@Service
public class AiAssistantProvider {

    private final AiSceneConfigService sceneConfigService;
    private final ChatModelFactory chatModelFactory;
    private final AiToolService toolService;
    private final AiChatMemory chatMemoryStore;
    private final AiConfigCacheInvalidator cacheInvalidator;

    /** AI 助理 AiAssistant 缓存，key = configId，配置变更时清空 */
    private final ConcurrentHashMap<Long, AiAssistant> chatAssistantCache = new ConcurrentHashMap<>();

    public AiAssistantProvider(
            AiSceneConfigService sceneConfigService,
            ChatModelFactory chatModelFactory,
            AiToolService toolService,
            AiChatMemory chatMemoryStore,
            AiConfigCacheInvalidator cacheInvalidator) {
        this.sceneConfigService = sceneConfigService;
        this.chatModelFactory = chatModelFactory;
        this.toolService = toolService;
        this.chatMemoryStore = chatMemoryStore;
        this.cacheInvalidator = cacheInvalidator;
    }

    /** 向失效协调器注册本类的本地缓存清空回调 */
    @PostConstruct
    void registerCacheInvalidation() {
        cacheInvalidator.register(this::invalidateChatAssistantCache);
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
                        .maxMessages(AiAssistantPrompts.ADMIN_MAX_MESSAGES)
                        .chatMemoryStore(chatMemoryStore)
                        .build());

        if (toolsSupported) {
            builder.tools(toolService);
            log.info("  已注册 AI 工具: class={}", toolService.getClass().getName());
        }

        return builder.build();
    }

    // ==================== 缓存管理 ====================

    /**
     * 使对话 Assistant 缓存失效
     */
    public void invalidateChatAssistantCache() {
        chatAssistantCache.clear();
        log.debug("对话 Assistant 缓存已清空");
    }

    /**
     * 清理当前 Assistant 缓存（错误恢复时调用，使下一个请求重新创建 Assistant）。
     * <p>
     * 同时清空 {@link ChatModelFactory} 的本副本模型缓存 — 与改动前"每次重建 Assistant
     * 都会新建模型实例"的恢复语义保持一致（重置底层 HTTP 客户端），
     * 但<b>不</b>递增 Redis 版本号：数据库配置未变化，无需通知其他副本。
     */
    public void clearAssistantCache() {
        invalidateChatAssistantCache();
        chatModelFactory.invalidateCaches();
    }
}
