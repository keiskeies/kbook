package com.kbook.service;

import com.kbook.config.ChatModelFactory;
import com.kbook.constants.AiPromptConstants;
import com.kbook.entity.AiProviderConfig;
import com.kbook.repository.AiProviderConfigRepository;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 配置服务 — 支持从数据库动态读取对话 AI 配置，未配置时回退到 yml 默认模型
 * <p>
 * 配置优先级：
 * 1. 数据库 ai_provider_config 表中 purpose=CHAT 且 enabled=true 的记录
 * 2. application.yml 中的 langchain4j.ollama.chat-model 配置
 * <p>
 * ChatModel 的构建委托给 ChatModelFactory。
 * AiToolService 使用 ObjectProvider 延迟获取（@Lazy 代理会导致 @Tool 注解不可见）。
 */
@Slf4j
@Service
public class AiProviderConfigService {

    private final AiChatMemory chatMemoryStore;
    private final ObjectProvider<AiToolService> toolServiceProvider;
    private final ChatModelFactory chatModelFactory;
    private final AiProviderConfigRepository configRepository;
    private final ObjectProvider<BookAdminChatService> bookAdminChatServiceProvider;

    /** 对话 AI 配置的缓存版本号，每次配置变更时递增 */
    private volatile long chatConfigVersion = 0;

    /** 按版本号缓存的 AiAssistant 实例 */
    private volatile AiAssistant cachedChatAssistant;
    private volatile long cachedChatAssistantVersion = -1;

    /** 旧版全局 Assistant 缓存（兼容保留） */
    private final ConcurrentHashMap<String, AiAssistant> assistantCache = new ConcurrentHashMap<>();

    public AiProviderConfigService(
            AiChatMemory chatMemoryStore,
            ObjectProvider<AiToolService> toolServiceProvider,
            ChatModelFactory chatModelFactory,
            AiProviderConfigRepository configRepository,
            ObjectProvider<BookAdminChatService> bookAdminChatServiceProvider
    ) {
        this.chatMemoryStore = chatMemoryStore;
        this.toolServiceProvider = toolServiceProvider;
        this.chatModelFactory = chatModelFactory;
        this.configRepository = configRepository;
        this.bookAdminChatServiceProvider = bookAdminChatServiceProvider;
    }

    // ==================== 对话 AI 配置 ====================

    /**
     * 获取对话用途的数据库配置（CHAT purpose），如果不存在或未启用则返回 null
     */
    public AiProviderConfig getChatConfig() {
        return configRepository.findByPurposeAndEnabledTrue(AiProviderConfig.Purpose.CHAT.name())
                .orElse(null);
    }

    /**
     * 构建对话用的 ChatModel
     * <p>
     * 优先使用数据库 CHAT 配置，未配置时回退到 yml 默认（标签评分模型）
     */
    public ChatModel buildChatChatModel() {
        AiProviderConfig config = getChatConfig();
        if (config != null) {
            log.debug("使用数据库对话配置: provider={}, model={}", config.getProvider(), config.getModelName());
        } else {
            log.debug("无数据库对话配置，回退到 yml 默认模型");
        }
        return chatModelFactory.buildChatModel(config);
    }

    /**
     * 构建对话用的 StreamingChatModel
     * <p>
     * 优先使用数据库 CHAT 配置，未配置时回退到 yml 默认
     */
    public StreamingChatModel buildChatStreamingModel() {
        AiProviderConfig config = getChatConfig();
        return chatModelFactory.buildStreamingChatModel(config);
    }

    /**
     * 判断对话模型是否支持 Tool Calling
     */
    public boolean isChatToolsSupported() {
        AiProviderConfig config = getChatConfig();
        return chatModelFactory.isToolsSupported(config);
    }

    /**
     * 获取对话 AiAssistant（带版本缓存）
     * <p>
     * 当管理员更新对话配置后，调用 invalidateChatCache() 使缓存失效，
     * 下次获取时将重新构建 Assistant。
     */
    public AiAssistant getChatAssistant(Long userId) {
        long currentVersion = chatConfigVersion;
        if (cachedChatAssistant != null && cachedChatAssistantVersion == currentVersion) {
            return cachedChatAssistant;
        }

        synchronized (this) {
            // 双重检查
            if (cachedChatAssistant != null && cachedChatAssistantVersion == currentVersion) {
                return cachedChatAssistant;
            }
            cachedChatAssistant = buildChatAssistant();
            cachedChatAssistantVersion = currentVersion;
            return cachedChatAssistant;
        }
    }

    /**
     * 使对话缓存失效（配置变更时调用）
     */
    public void invalidateChatCache() {
        chatConfigVersion++;
        cachedChatAssistant = null;
        // 同时清除 BookAdminChatService 的独立缓存
        bookAdminChatServiceProvider.ifAvailable(BookAdminChatService::clearCache);
        log.info("对话 AI 缓存已失效，下次请求将重新构建 Assistant");
    }

    /**
     * 清除所有缓存（兼容旧代码）
     */
    public void clearAssistantCache() {
        assistantCache.clear();
        invalidateChatCache();
        log.debug("已清除所有 AI Assistant 缓存");
    }

    // ==================== 标签/评分/OCR 配置（仍用 yml） ====================

    public ChatModel buildTagChatModel() {
        return chatModelFactory.buildChatModel();
    }

    public StreamingChatModel buildStreamingChatModel() {
        return chatModelFactory.buildStreamingChatModel();
    }

    public ChatModel buildVisionChatModel() {
        return chatModelFactory.buildVisionChatModel();
    }

    // ==================== 旧版全局 Assistant（兼容保留） ====================

    /**
     * @deprecated 使用 {@link #getChatAssistant(Long)} 代替
     */
    @Deprecated
    public AiAssistant getAssistant(Long userId) {
        return assistantCache.computeIfAbsent("global", k -> buildLegacyAssistant());
    }

    // ==================== 内部方法 ====================

    /**
     * 构建对话用途的 AiAssistant（使用数据库配置或 yml 回退）
     */
    private AiAssistant buildChatAssistant() {
        AiProviderConfig config = getChatConfig();
        String modelName = config != null ? config.getModelName() : chatModelFactory.getModelName();
        boolean toolsSupported = chatModelFactory.isToolsSupported(config);

        log.info("构建对话 AI Assistant: source={}, model={}, toolsEnabled={}, baseUrl={}",
                config != null ? "DB(config)" : "yml(default)", modelName, toolsSupported,
                config != null ? config.getBaseUrl() : chatModelFactory.getDefaultBaseUrl());
        if (config != null) {
            log.info("  DB 配置详情: id={}, purpose={}, provider={}, enabled={}, toolsEnabled(explicit)={}",
                    config.getId(), config.getPurpose(), config.getProvider(),
                    config.getEnabled(), config.getToolsEnabled());
        }
        // 诊断：从 AiAssistant 接口反射读取 @SystemMessage 的实际值
        try {
            var sm = AiAssistant.class.getAnnotation(SystemMessage.class);
            if (sm != null) {
                String[] lines = sm.value();
                String firstLine = lines.length > 0 ? lines[0] : "(空)";
                log.info("  @SystemMessage 已读取: 总{}行, 首行={}", lines.length,
                        firstLine.length() > 80 ? firstLine.substring(0, 80) + "..." : firstLine);
            } else {
                log.error("  ❌ AiAssistant 接口上未找到 @SystemMessage 注解！");
            }
        } catch (Exception e) {
            log.error("  读取 @SystemMessage 失败: {}", e.getMessage());
        }

        ChatModel chatModel = chatModelFactory.buildChatModel(config);
        StreamingChatModel streamingModel = chatModelFactory.buildStreamingChatModel(config);

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
        } else {
            log.warn("  对话模型 {} 不支持 Tool Calling，AI 助理将以纯对话模式运行（无法调用搜索/推荐等工具）", modelName);
        }

        return builder.build();
    }

    /**
     * 构建旧版全局 Assistant（从 yml 配置，兼容保留）
     */
    private AiAssistant buildLegacyAssistant() {
        String modelName = chatModelFactory.getModelName();
        boolean toolsSupported = chatModelFactory.isToolsSupported();
        log.info("构建旧版 AI Assistant: model={}, toolsEnabled={}", modelName, toolsSupported);

        var builder = AiServices.builder(AiAssistant.class)
                .chatModel(chatModelFactory.buildChatModel())
                .streamingChatModel(chatModelFactory.buildStreamingChatModel())
                .chatMemoryProvider(sessionId -> dev.langchain4j.memory.chat.MessageWindowChatMemory.builder()
                        .id(sessionId)
                        .maxMessages(AiPromptConstants.ADMIN_MAX_MESSAGES)
                        .chatMemoryStore(chatMemoryStore)
                        .build());

        if (toolsSupported) {
            Object toolObj = toolServiceProvider.getObject();
            builder.tools(toolObj);
            log.info("  已注册 AI 工具(legacy): class={}", toolObj.getClass().getName());
        } else {
            log.warn("  当前模型 {} 不支持 Tool Calling，AI 助理将以纯对话模式运行（无法调用图书搜索、推荐等工具）", modelName);
        }

        return builder.build();
    }
}
