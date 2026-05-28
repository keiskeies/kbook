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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 配置服务 — 支持从数据库动态读取对话 AI 配置，未配置时回退到 yml 默认模型
 * <p>
 * 配置优先级：
 * 1. 数据库 ai_provider_config 表中 purpose=CHAT 且 enabled=true 的记录
 * 2. application.yml 中的 langchain4j.chat-model 配置
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
     * 获取对话用途的默认数据库配置（CHAT purpose, isDefault=true, enabled=true），
     * 如果不存在或未启用则返回 null
     */
    public AiProviderConfig getChatConfig() {
        return configRepository.findByPurposeAndIsDefaultTrueAndEnabledTrue(AiProviderConfig.Purpose.CHAT.name())
                .orElse(null);
    }

    /**
     * 获取当前激活对话配置的 RAG TopK 值
     * 如果配置中未指定，则返回 null，调用方应回退到全局默认值
     */
    public Integer getActiveRagTopK() {
        AiProviderConfig config = getChatConfig();
        return config != null ? config.getRagTopK() : null;
    }

    /**
     * 获取指定用途的所有配置列表
     */
    public List<AiProviderConfig> getConfigsByPurpose(String purpose) {
        return configRepository.findByPurposeOrderByIsDefaultDescUpdatedAtDesc(purpose);
    }

    /**
     * 切换指定配置为默认（激活）状态
     * <p>
     * 将同 purpose 的其他配置的 isDefault 设为 false，
     * 将目标配置的 isDefault 设为 true。
     */
    @Transactional
    public AiProviderConfig switchDefault(Long configId) {
        AiProviderConfig config = configRepository.findById(configId)
                .orElseThrow(() -> new RuntimeException("配置不存在: " + configId));

        // 清除同 purpose 其他配置的默认标记
        configRepository.clearDefaultForPurpose(config.getPurpose(), configId);

        // 设置目标配置为默认
        config.setIsDefault(true);
        AiProviderConfig saved = configRepository.save(config);

        // 使对话缓存失效
        invalidateChatCache();

        log.info("已切换默认配置: id={}, name={}, purpose={}", configId, config.getName(), config.getPurpose());
        return saved;
    }


    /**
     * 获取对话 AiAssistant（带版本缓存）
     * <p>
     * 当管理员更新对话配置后，调用 invalidateChatCache() 使缓存失效，
     * 下次获取时将重新构建 Assistant。
     */
    public AiAssistant getChatAssistant() {
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

        ChatModel chatModel = chatModelFactory.buildChatModel();
        StreamingChatModel streamingModel = chatModelFactory.buildStreamingChatModel();

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
}
