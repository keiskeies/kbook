package com.kbook.service;

import com.kbook.config.ChatModelFactory;
import com.kbook.entity.AiProviderConfig;
import com.kbook.repository.AiProviderConfigRepository;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 提供商配置服务 — 全局配置管理 + 动态构建 ChatModel
 * <p>
 * 管理员可配置多个 LLM 提供商，只有 enabled=true 的配置会被使用。
 * 支持连接测试功能，验证 API 连通性。
 * <p>
 * ChatModel 的构建委托给 ChatModelFactory，本类不直接依赖 Ollama/OpenAI 实现类。
 * <p>
 * 注意：AiToolService 使用 ObjectProvider 延迟获取，
 * 因为 LangChain4j 的 .tools() 需要扫描真实类上的 @Tool 注解。
 */
@Slf4j
@Service
public class AiProviderConfigService {

    private final AiProviderConfigRepository configRepository;
    private final AiChatMemory chatMemoryStore;
    private final ObjectProvider<AiToolService> toolServiceProvider;
    private final ChatModelFactory chatModelFactory;

    public AiProviderConfigService(
            AiProviderConfigRepository configRepository,
            AiChatMemory chatMemoryStore,
            ObjectProvider<AiToolService> toolServiceProvider,
            ChatModelFactory chatModelFactory
    ) {
        this.configRepository = configRepository;
        this.chatMemoryStore = chatMemoryStore;
        this.toolServiceProvider = toolServiceProvider;
        this.chatModelFactory = chatModelFactory;
    }

    /** AiAssistant 缓存（key: "global"，全局共用一个活跃实例） */
    private final ConcurrentHashMap<String, AiAssistant> assistantCache = new ConcurrentHashMap<>();

    // ==================== 配置 CRUD ====================

    /** 获取所有配置 */
    public List<AiProviderConfig> getAllConfigs() {
        return configRepository.findAll();
    }

    /** 获取指定配置 */
    public AiProviderConfig getConfigById(Long id) {
        return configRepository.findById(id).orElse(null);
    }

    /** 获取当前活跃的配置 */
    public AiProviderConfig getActiveConfig() {
        return configRepository.findActiveConfig().orElse(null);
    }

    /** 保存配置 */
    @Transactional
    public AiProviderConfig saveConfig(AiProviderConfig config) {
        if (Boolean.TRUE.equals(config.getEnabled())) {
            disableAllConfigs();
        }
        AiProviderConfig saved = configRepository.save(config);
        clearAssistantCache();
        log.info("保存 AI 配置: id={}, name={}, provider={}, model={}, enabled={}",
                saved.getId(), saved.getConfigName(), saved.getProvider(), saved.getModelName(), saved.getEnabled());
        return saved;
    }

    /** 删除配置 */
    @Transactional
    public void deleteConfig(Long id) {
        configRepository.deleteById(id);
        clearAssistantCache();
        log.info("删除 AI 配置: id={}", id);
    }

    /** 启用指定配置 */
    @Transactional
    public AiProviderConfig enableConfig(Long id) {
        disableAllConfigs();
        AiProviderConfig config = configRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("配置不存在"));
        config.setEnabled(true);
        AiProviderConfig saved = configRepository.save(config);
        clearAssistantCache();
        log.info("启用 AI 配置: id={}, name={}", id, saved.getConfigName());
        return saved;
    }

    /** 禁用所有配置 */
    @Transactional
    public void disableAllConfigs() {
        List<AiProviderConfig> enabledConfigs = configRepository.findByEnabledTrue();
        enabledConfigs.forEach(c -> c.setEnabled(false));
        configRepository.saveAll(enabledConfigs);
    }

    // ==================== 思考模式控制 ====================

    /**
     * 获取当前活跃配置的 Thinking prompt 后缀
     * <p>
     * Qwen3 系列模型支持通过 prompt 指令控制思考模式：
     * - thinkingLevel = NONE 时追加 "/no_think"，关闭思考（快速响应）
     * - thinkingLevel != NONE 时不追加，保持模型默认思考行为
     * <p>
     * 对非 Qwen 模型，追加此后缀也无害（模型会忽略不认识的指令）。
     */
    public String getThinkingPromptSuffix() {
        AiProviderConfig activeConfig = getActiveConfig();
        if (activeConfig == null) {
            return "";
        }
        String thinkingLevel = activeConfig.getThinkingLevel();
        if (thinkingLevel == null || "NONE".equalsIgnoreCase(thinkingLevel)) {
            return " /no_think";
        }
        return "";
    }

    /**
     * 判断当前活跃配置是否启用了思考模式
     */
    public boolean isThinkingEnabled() {
        AiProviderConfig activeConfig = getActiveConfig();
        if (activeConfig == null) {
            return false;
        }
        String thinkingLevel = activeConfig.getThinkingLevel();
        return thinkingLevel != null && !"NONE".equalsIgnoreCase(thinkingLevel);
    }

    // ==================== 连接测试 ====================

    /**
     * 测试 AI 提供商连接
     * @return 测试结果（成功/失败 + 响应信息）
     */
    public ConnectionTestResult testConnection(AiProviderConfig config) {
        long startTime = System.currentTimeMillis();
        try {
            log.info("========== AI 连接测试请求 ==========");
            log.info("提供商: {}", config.getProvider());
            log.info("模型名称: {}", config.getModelName());
            log.info("基础 URL: {}", config.getBaseUrl());
            log.info("测试问题: 你好");
            
            ChatModel chatModel = chatModelFactory.buildChatModel(config);
            String thinkingSuffix = (config.getThinkingLevel() == null || "NONE".equalsIgnoreCase(config.getThinkingLevel()))
                    ? " /no_think" : "";
            ChatResponse response = chatModel.chat(List.of(
                    UserMessage.from("你好" + thinkingSuffix)
            ));
            String reply = response.aiMessage().text();
            long elapsed = System.currentTimeMillis() - startTime;
            
            int inputTokens = estimateTokens("你好");
            int outputTokens = estimateTokens(reply);
            double totalTokens = inputTokens + outputTokens;
            double tokensPerSecond = (elapsed > 0) ? (totalTokens / (elapsed / 1000.0)) : 0;
            
            log.info("测试回答: {}", reply);
            log.info("耗时: {}ms | 输入tokens: {} | 输出tokens: {} | 总tokens: {} | 速度: {} tokens/s",
                    elapsed, inputTokens, outputTokens, (int)totalTokens, String.format("%.2f", tokensPerSecond));
            log.info("====================================\n");
            
            return ConnectionTestResult.success("连接成功 (" + elapsed + "ms)", reply, config.getModelName());
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.warn("AI 连接测试失败: provider={}, model={}, 耗时={}ms, error={}",
                    config.getProvider(), config.getModelName(), elapsed, e.getMessage());
            log.info("====================================\n");
            return ConnectionTestResult.fail("连接失败 (" + elapsed + "ms): " + e.getMessage());
        }
    }

    /**
     * 测试已保存配置的连接（按 ID）
     */
    public ConnectionTestResult testConnectionById(Long id) {
        AiProviderConfig config = configRepository.findById(id).orElse(null);
        if (config == null) {
            return ConnectionTestResult.fail("配置不存在");
        }
        return testConnection(config);
    }

    // ==================== Assistant 构建 ====================

    /**
     * 获取全局 AiAssistant（带缓存）
     * @return AiAssistant，如果没有可用的 AI 配置则返回 null
     */
    public AiAssistant getAssistant(Long userId) {
        return assistantCache.computeIfAbsent("global", k -> buildAssistant());
    }

    /** 清除 Assistant 缓存 */
    public void clearAssistantCache() {
        assistantCache.clear();
        log.debug("已清除 AI Assistant 缓存");
    }

    /**
     * 构建用于标签生成的 ChatModel（不需要 tools 和 memory）
     * @return ChatModel，如果没有可用的 AI 配置则使用默认
     */
    public ChatModel buildTagChatModel() {
        AiProviderConfig activeConfig = getActiveConfig();
        return chatModelFactory.buildChatModelOrDefault(activeConfig);
    }

    /**
     * 构建用于 PDF OCR 的视觉 ChatModel
     * <p>
     * 优先使用管理员配置的活跃模型（需支持视觉/多模态能力），
     * 如果管理员配置的模型不支持视觉，仍会尝试调用（调用失败时会回退到 PDFTextStripper）。
     * OCR 需要更长的超时时间（图片编码消耗更多 token），默认 10 分钟。
     *
     * @return ChatModel，如果没有可用的 AI 配置则使用默认
     */
    public ChatModel buildVisionChatModel() {
        AiProviderConfig activeConfig = getActiveConfig();
        return chatModelFactory.buildVisionChatModel(activeConfig);
    }

    /**
     * 根据当前活跃配置构建 AiAssistant
     */
    private AiAssistant buildAssistant() {
        AiProviderConfig activeConfig = getActiveConfig();
        ChatModel chatModel = chatModelFactory.buildChatModelOrDefault(activeConfig);

        if (activeConfig != null) {
            log.info("构建自定义 AI Assistant: provider={}, model={}",
                    activeConfig.getProvider(), activeConfig.getModelName());
        } else {
            log.debug("使用默认 AI Assistant（无自定义配置）");
        }

        return AiServices.builder(AiAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(sessionId -> dev.langchain4j.memory.chat.MessageWindowChatMemory.builder()
                        .id(sessionId)
                        .maxMessages(20)
                        .chatMemoryStore(chatMemoryStore)
                        .build())
                .tools(toolServiceProvider.getObject())
                .build();
    }

    /**
     * 估算文本的 token 数量（粗略估算：中文约1字符=1token，英文约4字符=1token）
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int chineseChars = 0;
        int otherChars = 0;
        for (char c : text.toCharArray()) {
            if (c >= '一' && c <= '鿿') {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        return chineseChars + (otherChars / 4);
    }

    // ==================== 内部类 ====================

    /** 连接测试结果 */
    @lombok.Data
    @lombok.AllArgsConstructor(staticName = "of")
    @lombok.NoArgsConstructor
    public static class ConnectionTestResult {
        private boolean success;
        private String message;
        private String reply;
        private String modelName;

        public static ConnectionTestResult success(String message, String reply, String modelName) {
            ConnectionTestResult r = new ConnectionTestResult();
            r.setSuccess(true);
            r.setMessage(message);
            r.setReply(reply);
            r.setModelName(modelName);
            return r;
        }

        public static ConnectionTestResult fail(String message) {
            ConnectionTestResult r = new ConnectionTestResult();
            r.setSuccess(false);
            r.setMessage(message);
            return r;
        }
    }
}
