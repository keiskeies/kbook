package com.kbook.config;

import com.kbook.config.properties.AiModelProperties;
import com.kbook.entity.AiProviderConfig;
import com.kbook.repository.AiProviderConfigRepository;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 聊天模型工厂类
 * <p>
 * 全部从数据库 {@link AiProviderConfig} 中读取配置构建 AI 聊天模型，不再使用 YML 配置。
 * <p>
 * CHAT 用途的配置按 roles 字段细分为：
 * <ul>
 *   <li>QA 角色 — 大型问答（图书问答、AI 助理、圆桌派、奇葩说）</li>
 *   <li>TOOL 角色 — 小型工具（元数据推断、内容压缩、查询扩展等后台任务）</li>
 * </ul>
 * EMBEDDING 用途的配置从数据库读取，无配置时启动报错。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelFactory {

    /** AI 提供商配置仓库 */
    private final AiProviderConfigRepository configRepository;

    /** AI 熔断器注册表 — 按 provider+model 维度管理熔断器 */
    private final AiCircuitBreakerRegistry cbRegistry;

    /** AI 模型配置属性（视觉 OCR 模型等仅 YML 配置的部分） */
    private final AiModelProperties aiModelProperties;

    // ======================== QA 模型（大型问答）=======================

    /**
     * 构建普通聊天模型（QA 角色，带思考过程）。
     * <p>
     * 从数据库查询 CHAT 用途中 roles 包含 "QA" 的启用配置。
     *
     * @return 聊天模型实例，已包装重试机制
     * @throws IllegalStateException 无可用 QA 配置时抛出
     */
    public ChatModel buildChatModel() {
        AiProviderConfig config = resolveQaConfig();
        return wrap(buildChat(config, true), config);
    }

    /**
     * 构建不包含思考过程的普通聊天模型（QA 角色）。
     *
     * @return 聊天模型实例，已包装重试机制
     * @throws IllegalStateException 无可用 QA 配置时抛出
     */
    public ChatModel buildChatModelWithoutThinking() {
        AiProviderConfig config = resolveQaConfig();
        return wrap(buildChat(config, false), config);
    }

    /**
     * 构建流式聊天模型（QA 角色，带思考过程）。
     *
     * @return 流式聊天模型实例
     * @throws IllegalStateException 无可用 QA 配置时抛出
     */
    public StreamingChatModel buildStreamingChatModel() {
        AiProviderConfig config = resolveQaConfig();
        return wrapStreaming(buildStreaming(config, true), config);
    }

    /**
     * 构建不包含思考过程的流式聊天模型（QA 角色）。
     *
     * @return 流式聊天模型实例
     * @throws IllegalStateException 无可用 QA 配置时抛出
     */
    public StreamingChatModel buildStreamingChatModelWithoutThinking() {
        AiProviderConfig config = resolveQaConfig();
        return wrapStreaming(buildStreaming(config, false), config);
    }

    // ======================== TOOL 模型（小型工具）=======================

    /**
     * 构建工具聊天模型（TOOL 角色，不包含思考过程）。
     * <p>
     * 从数据库查询 CHAT 用途中 roles 包含 "TOOL" 的启用配置。
     * 用于元数据推断、内容压缩、查询扩展等后台任务。
     *
     * @return 聊天模型实例，已包装重试机制
     * @throws IllegalStateException 无可用 TOOL 配置时抛出
     */
    public ChatModel buildToolChatModel() {
        AiProviderConfig config = resolveToolConfig();
        return wrap(buildChat(config, false), config);
    }

    /**
     * 构建压缩专用聊天模型 — 复用 TOOL 配置，但使用低温度（0.1）确保压缩结果的
     * 确定性和忠实度，避免高温度带来的内容改写/编造风险。
     *
     * @return 聊天模型实例，已包装重试机制
     * @throws IllegalStateException 无可用 TOOL 配置时抛出
     */
    public ChatModel buildCompressionChatModel() {
        AiProviderConfig config = resolveToolConfig();
        Duration t = timeout(config.getTimeout());
        return config.getProvider() == AiProviderConfig.Provider.OPENAI
                ? wrap(buildOpenAiChat(config.getBaseUrl(), config.getModelName(),
                0.1, t, config.getApiKey(), false), config)
                : wrap(buildOllamaChat(config.getBaseUrl(), config.getModelName(),
                0.1, t, false), config);
    }

    /**
     * 构建流式工具聊天模型（TOOL 角色，不包含思考过程）。
     *
     * @return 流式聊天模型实例
     * @throws IllegalStateException 无可用 TOOL 配置时抛出
     */
    public StreamingChatModel buildStreamingToolChatModel() {
        AiProviderConfig config = resolveToolConfig();
        return wrapStreaming(buildStreaming(config, false), config);
    }

    // ======================== 其他公开方法 ========================

    /**
     * 构建视觉模型（用于 OCR/PDF 处理）。
     * <p>
     * 优先从 YML 的 {@code kbook.ai.vision} 配置读取（专用的本地 OCR 模型，
     * 如 Ollama 的 Unlimited-OCR:Q4_K_M），避免云端模型（如 Gemini）对
     * data URI 格式图片的兼容性问题。
     * <p>
     * 如果 vision 配置缺失（base-url 或 model-name 留空），则回退到 CHAT-QA 配置。
     * 关闭思考过程、使用低温度以提高 OCR 准确率。
     *
     * @return 聊天模型实例，已包装重试机制
     */
    public ChatModel buildVisionChatModel() {
        AiModelProperties.VisionConfig vision = aiModelProperties.getVision();

        // 回退逻辑：vision 配置缺失时使用 QA 配置
        if (vision == null
                || vision.getBaseUrl() == null || vision.getBaseUrl().isBlank()
                || vision.getModelName() == null || vision.getModelName().isBlank()) {
            log.warn("Vision 配置缺失，回退到 CHAT-QA 配置构建 OCR 模型");
            AiProviderConfig qaConfig = resolveQaConfig();
            Duration timeout = Duration.ofSeconds(600);
            double temperature = 0.3;
            return qaConfig.getProvider() == AiProviderConfig.Provider.OPENAI
                    ? wrap(buildOpenAiChat(qaConfig.getBaseUrl(), qaConfig.getModelName(),
                            temperature, timeout, qaConfig.getApiKey(), false), qaConfig)
                    : wrap(buildOllamaChat(qaConfig.getBaseUrl(), qaConfig.getModelName(),
                            temperature, timeout, false), qaConfig);
        }

        AiProviderConfig.Provider provider = AiProviderConfig.Provider.from(vision.getProvider());
        if (provider == null) {
            provider = AiProviderConfig.Provider.OLLAMA;
        }
        Duration timeout = vision.getTimeout() != null ? vision.getTimeout() : Duration.ofSeconds(600);
        double temperature = 0.3;

        log.info("构建 OCR 视觉 ChatModel (from vision config): provider={}, model={}, baseUrl={}, thinking=false",
                provider, vision.getModelName(), vision.getBaseUrl());

        String providerKey = provider + ":" + vision.getModelName();
        ChatModel model = provider == AiProviderConfig.Provider.OPENAI
                ? buildOpenAiChat(vision.getBaseUrl(), vision.getModelName(),
                        temperature, timeout, null, false)
                : buildOllamaChat(vision.getBaseUrl(), vision.getModelName(),
                        temperature, timeout, false);
        return wrap(model, providerKey);
    }

    /**
     * 根据指定的配置 ID 构建聊天模型（用于测试）。
     *
     * @param configId AI 提供商配置的 ID
     * @return 聊天模型实例，已包装重试机制
     * @throws IllegalArgumentException 如果指定的配置不存在
     */
    public ChatModel buildChatModelForTest(Long configId) {
        AiProviderConfig config = configRepository.findById(configId)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + configId));
        return wrap(buildChat(config, true), config);
    }

    /**
     * 构建 Ollama 嵌入模型。
     */
    public EmbeddingModel buildOllamaEmbeddingModel(String baseUrl, String modelName, Duration timeout) {
        return OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl).modelName(modelName)
                .timeout(timeout != null ? timeout : Duration.ofSeconds(300))
                .customHeaders(AiModelProperties.UTF8_HEADERS)
                .build();
    }

    /**
     * 构建 OpenAI 兼容的嵌入模型。
     */
    public EmbeddingModel buildOpenAiEmbeddingModel(String baseUrl, String modelName, String apiKey, Duration timeout) {
        var builder = OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl).modelName(modelName)
                .timeout(timeout != null ? timeout : Duration.ofSeconds(300));
        builder.apiKey(apiKey != null && !apiKey.isBlank() ? apiKey : "sk-placeholder");
        return builder.build();
    }

    /**
     * 构建默认的嵌入模型 — 从数据库读取 EMBEDDING 用途的启用配置。
     *
     * @return 嵌入模型实例
     * @throws IllegalStateException 无可用 EMBEDDING 配置时抛出
     */
    public EmbeddingModel buildDefaultEmbeddingModel() {
        AiProviderConfig config = configRepository.findFirstByPurposeAndEnabledTrueOrderByUpdatedAtDesc(
                        AiProviderConfig.Purpose.EMBEDDING.name())
                .orElseThrow(() -> new IllegalStateException(
                        "未找到可用的嵌入模型配置，请在管理后台添加 EMBEDDING 用途的配置"));

        log.info("构建 EmbeddingModel (from DB): provider={}, baseUrl={}, model={}",
                config.getProvider(), config.getBaseUrl(), config.getModelName());
        return config.getProvider() == AiProviderConfig.Provider.OPENAI
                ? buildOpenAiEmbeddingModel(config.getBaseUrl(), config.getModelName(), config.getApiKey(), timeout(config.getTimeout()))
                : buildOllamaEmbeddingModel(config.getBaseUrl(), config.getModelName(), timeout(config.getTimeout()));
    }

    /**
     * 判断指定的 AI 提供商配置是否支持 Tool Calling 功能。
     */
    public boolean isToolsSupported(AiProviderConfig config) {
        if (config == null) return false;
        if (config.getToolsEnabled() != null) return config.getToolsEnabled();
        return !config.getModelName().toLowerCase().startsWith("gemma3n");
    }

    /**
     * 获取默认的 AI 服务基础地址 — 从 CHAT-QA 配置中读取。
     */
    public String getDefaultBaseUrl() {
        AiProviderConfig config = resolveQaConfig();
        return config.getBaseUrl();
    }

    /**
     * 获取嵌入模型的服务基础地址 — 从 EMBEDDING 配置中读取。
     */
    public String getEmbeddingBaseUrl() {
        AiProviderConfig config = configRepository
                .findFirstByPurposeAndEnabledTrueOrderByUpdatedAtDesc(AiProviderConfig.Purpose.EMBEDDING.name())
                .orElse(null);
        return config != null ? config.getBaseUrl() : "http://localhost:11434";
    }

    /**
     * 获取当前 QA 配置的聊天模型名称。
     */
    public String getModelName() {
        return resolveQaConfig().getModelName();
    }

    /**
     * 获取当前嵌入模型名称。
     */
    public String getEmbeddingModelName() {
        AiProviderConfig config = configRepository
                .findFirstByPurposeAndEnabledTrueOrderByUpdatedAtDesc(AiProviderConfig.Purpose.EMBEDDING.name())
                .orElse(null);
        return config != null ? config.getModelName() : "bge-m3:latest";
    }

    // ======================== 内部方法 ========================

    /**
     * 解析 CHAT-QA 配置。
     */
    private AiProviderConfig resolveQaConfig() {
        return configRepository.findByPurposeAndEnabledAndRolesContaining(
                        AiProviderConfig.Purpose.CHAT.name(), "%QA%")
                .orElseThrow(() -> new IllegalStateException(
                        "未找到可用的对话模型(roles=QA)配置，请在管理后台添加并启用"));
    }

    /**
     * 解析 CHAT-TOOL 配置。
     */
    private AiProviderConfig resolveToolConfig() {
        return configRepository.findByPurposeAndEnabledAndRolesContaining(
                        AiProviderConfig.Purpose.CHAT.name(), "%TOOL%")
                .orElseThrow(() -> new IllegalStateException(
                        "未找到可用的工具模型(roles=TOOL)配置，请在管理后台添加并启用"));
    }

    private Duration timeout(Integer seconds) {
        return Duration.ofSeconds(seconds != null ? seconds : 600);
    }

    /**
     * 包装 ChatModel：熔断器 → 重试 → 实际模型
     * <p>
     * 熔断器在外层：provider 故障时直接拒绝请求，不进入重试排队。
     * providerKey 按 provider+model 维度隔离，一个 provider 挂了不影响另一个。
     */
    private ChatModel wrap(ChatModel model, AiProviderConfig config) {
        return wrap(model, config.getProvider() + ":" + config.getModelName());
    }

    /**
     * 包装 ChatModel（按 providerKey 字符串）— 用于非 DB 配置来源（如 YML vision 配置）。
     */
    private ChatModel wrap(ChatModel model, String providerKey) {
        CircuitBreaker cb = cbRegistry.getOrCreate(providerKey);
        return new CircuitBreakerChatModel(new RetryableChatModel(model), cb);
    }

    /**
     * 包装 StreamingChatModel：熔断器 → 实际流式模型
     * <p>
     * 流式模型不套重试（StreamingSseHandler 已有重试逻辑），只套熔断器。
     */
    private StreamingChatModel wrapStreaming(StreamingChatModel model, AiProviderConfig config) {
        String providerKey = config.getProvider() + ":" + config.getModelName();
        CircuitBreaker cb = cbRegistry.getOrCreate(providerKey);
        return new CircuitBreakerStreamingChatModel(model, cb);
    }

    // ---- DB config 组合器 ----

    private ChatModel buildChat(AiProviderConfig config, boolean thinking) {
        Duration t = timeout(config.getTimeout());
        log.info("构建 ChatModel (from DB): provider={}, model={}, baseUrl={}, thinking={}",
                config.getProvider(), config.getModelName(), config.getBaseUrl(), thinking);
        return config.getProvider() == AiProviderConfig.Provider.OPENAI
                ? buildOpenAiChat(config.getBaseUrl(), config.getModelName(),
                config.getTemperature(), t, config.getApiKey(), thinking)
                : buildOllamaChat(config.getBaseUrl(), config.getModelName(),
                config.getTemperature(), t, thinking);
    }

    private StreamingChatModel buildStreaming(AiProviderConfig config, boolean thinking) {
        Duration t = timeout(config.getTimeout());
        log.info("构建 StreamingChatModel (from DB): provider={}, model={}, baseUrl={}, thinking={}",
                config.getProvider(), config.getModelName(), config.getBaseUrl(), thinking);
        return config.getProvider() == AiProviderConfig.Provider.OPENAI
                ? buildOpenAiStreaming(config.getBaseUrl(), config.getModelName(),
                config.getTemperature(), t, config.getApiKey(), thinking)
                : buildOllamaStreaming(config.getBaseUrl(), config.getModelName(),
                config.getTemperature(), t, thinking);
    }

    // ======================== 底层构建器 ========================

    private OllamaChatModel buildOllamaChat(String baseUrl, String modelName,
                                            Double temperature, Duration timeout, boolean thinking) {

        return OllamaChatModel.builder()
                .baseUrl(baseUrl).modelName(modelName)
                .temperature(temperature != null ? temperature : 0.7)
                .timeout(timeout != null ? timeout : Duration.ofSeconds(600))
                .customHeaders(AiModelProperties.UTF8_HEADERS)
                .listeners(List.of(ollamaCounterListener()))
                .returnThinking(thinking).think(thinking)
//                .logRequests(true)
                .build();
    }

    private OllamaStreamingChatModel buildOllamaStreaming(String baseUrl, String modelName,
                                                          Double temperature, Duration timeout, boolean thinking) {
        return OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl).modelName(modelName)
                .temperature(temperature != null ? temperature : 0.7)
                .timeout(timeout != null ? timeout : Duration.ofSeconds(600))
                .customHeaders(AiModelProperties.UTF8_HEADERS)
                .listeners(List.of(ollamaCounterListener()))
                .returnThinking(thinking).think(thinking)
//                .logRequests(true)
                .build();
    }

    private OpenAiChatModel buildOpenAiChat(String baseUrl, String modelName,
                                            Double temperature, Duration timeout, String apiKey, boolean thinking) {
        boolean gemini = isGeminiModel(baseUrl, modelName);
        var builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl).modelName(modelName)
                .temperature(temperature != null ? temperature : 0.7)
                .timeout(timeout != null ? timeout : Duration.ofSeconds(600))
                // Gemini 模型不支持 reasoningEffort 参数，跳过设置
                .reasoningEffort(gemini ? null : (thinking ? null : "none"))
//                .logRequests(true)
                .listeners(List.of(new DiagnosticChatListener()));
        // Gemini 模型不设置 returnThinking/sendThinking：
        // 某些 Gemini 模型不支持 thinking 功能，设置后会在请求中携带 thinkingBudget 参数导致 400 错误
        if (!gemini) {
            builder.returnThinking(thinking).sendThinking(thinking);
        }
        builder.apiKey(apiKey != null && !apiKey.isBlank() ? apiKey : "sk-placeholder");
        return builder.build();
    }

    private OpenAiStreamingChatModel buildOpenAiStreaming(String baseUrl, String modelName,
                                                          Double temperature, Duration timeout, String apiKey, boolean thinking) {
        boolean gemini = isGeminiModel(baseUrl, modelName);
        var builder = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl).modelName(modelName)
                .temperature(temperature != null ? temperature : 0.7)
                .timeout(timeout != null ? timeout : Duration.ofSeconds(600))
                // Gemini 模型不支持 reasoningEffort 参数，跳过设置
                .reasoningEffort(gemini ? null : (thinking ? null : "none"))
//                .logRequests(true)
                .listeners(List.of(new DiagnosticChatListener()));
        // Gemini 模型不设置 returnThinking/sendThinking：
        // 某些 Gemini 模型不支持 thinking 功能，设置后会在请求中携带 thinkingBudget 参数导致 400 错误
        if (!gemini) {
            builder.returnThinking(thinking).sendThinking(thinking);
        }
        builder.apiKey(apiKey != null && !apiKey.isBlank() ? apiKey : "sk-placeholder");
        return builder.build();
    }

    /**
     * 判断是否为 Google Gemini 模型（通过 baseUrl 或模型名识别）。
     * Gemini 模型不支持 reasoningEffort 参数，需要跳过。
     */
    private static boolean isGeminiModel(String baseUrl, String modelName) {
        if (baseUrl != null && baseUrl.contains("generativelanguage.googleapis.com")) return true;
        if (modelName != null) {
            String lower = modelName.toLowerCase(Locale.ROOT);
            return lower.contains("gemini") || lower.contains("gemma4-31b");
        }
        return false;
    }

    // ======================== Ollama KV 缓存管理 ========================

    private ChatModelListener ollamaCounterListener() {
        return new ChatModelListener() {
            @Override
            public void onRequest(ChatModelRequestContext ctx) {}

            @Override
            public void onResponse(ChatModelResponseContext ctx) {}

            @Override
            public void onError(ChatModelErrorContext ctx) {}
        };
    }
}
