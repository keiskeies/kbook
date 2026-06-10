package com.kbook.config;

import com.kbook.config.properties.AiModelProperties;
import com.kbook.entity.AiProviderConfig;
import com.kbook.repository.AiProviderConfigRepository;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.HttpClientBuilderLoader;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * AI 聊天模型工厂类
 * <p>
 * 负责根据数据库配置或 yml 配置文件构建不同类型的 AI 聊天模型，包括：
 * <ul>
 *   <li>普通聊天模型（ChatModel）</li>
 *   <li>流式聊天模型（StreamingChatModel）</li>
 *   <li>视觉模型（用于 OCR/PDF 处理）</li>
 *   <li>嵌入模型（用于向量生成）</li>
 * </ul>
 * <p>
 * 配置优先级：
 * 1. 优先从数据库 {@link AiProviderConfig} 中获取配置
 * 2. 如果数据库中未找到配置，则回退到 {@link AiModelProperties} yml 配置
 * <p>
 * 支持的 AI 提供商：
 * - Ollama：本地或远程 Ollama 服务
 * - OpenAI：OpenAI 兼容 API（包括 DeepSeek、通义千问等）
 * <p>
 * 特性：
 * - 支持启用/禁用模型的思考过程（thinking）
 * - 自动重试机制（通过 {@link RetryableChatModel} 包装）
 * - Ollama KV 缓存自动管理（每 50 次请求自动重置缓存）
 * - 请求诊断和监听
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelFactory {

    /** AI 模型配置属性（从 yml 配置文件注入） */
    private final AiModelProperties aiProps;

    /** AI 提供商配置仓库（用于从数据库读取配置） */
    private final AiProviderConfigRepository configRepository;

    /** Redis 模板（用于 Ollama 缓存管理） */
    private final StringRedisTemplate redisTemplate;

    /** Ollama 请求计数器在 Redis 中的键名 */
    private static final String OLLAMA_COUNTER_KEY = "ollama:request_count";

    /** Ollama 缓存重置间隔（每 50 次请求重置一次缓存） */
    private static final long OLLAMA_RESET_INTERVAL = 50;

    // ======================== 8 个无参公开方法 ========================

    // ---- DB 配置（优先 DB，无 DB 时回退 yml）----

    /**
     * 构建普通聊天模型。
     * <p>
     * 优先从数据库配置中获取 AI 提供商配置并构建聊天模型；
     * 如果数据库中未找到相关配置，则回退到 yml 配置文件构建。
     * <p>
     * 该模型包含思考过程，适用于需要展示模型推理过程的场景。
     *
     * @return 聊天模型实例，已包装重试机制
     */
    public ChatModel buildChatModel() {
        AiProviderConfig config = resolveChatConfig();
        return config != null
                ? wrap(buildChat(config, true))
                : buildChatModelFromYml();
    }

    /**
     * 构建不包含思考过程的普通聊天模型。
     * <p>
     * 优先从数据库配置中获取 AI 提供商配置并构建聊天模型；
     * 如果数据库中未找到相关配置，则回退到 yml 配置文件构建。
     * <p>
     * 该模型不包含思考过程，适用于快速响应场景。
     *
     * @return 聊天模型实例，已包装重试机制
     */
    public ChatModel buildChatModelWithoutThinking() {
        AiProviderConfig config = resolveChatConfig();
        return config != null
                ? wrap(buildChat(config, false))
                : buildChatModelWithoutThinkingFromYml();
    }

    /**
     * 构建流式聊天模型。
     * <p>
     * 优先从数据库配置中获取 AI 提供商配置并构建流式聊天模型；
     * 如果数据库中未找到相关配置，则回退到 yml 配置文件构建。
     * <p>
     * 该模型包含思考过程，支持实时流式响应，适用于需要展示模型推理过程的对话场景。
     *
     * @return 流式聊天模型实例
     */
    public StreamingChatModel buildStreamingChatModel() {
        AiProviderConfig config = resolveChatConfig();
        return config != null
                ? buildStreaming(config, true)
                : buildStreamingChatModelFromYml();
    }

    /**
     * 构建不包含思考过程的流式聊天模型。
     * <p>
     * 优先从数据库配置中获取AI提供商配置并构建流式聊天模型；
     * 如果数据库中未找到相关配置，则回退到yml配置文件构建。
     *
     * @return 流式聊天模型实例，支持流式响应但不包含模型的思考过程
     */
    public StreamingChatModel buildStreamingChatModelWithoutThinking() {
        AiProviderConfig config = resolveChatConfig();
        return config != null
                ? buildStreaming(config, false)
                : buildStreamingChatModelWithoutThinkingFromYml();
    }

    // ---- yml 配置 ----

    /**
     * 基于 yml 配置构建普通聊天模型（包含思考过程）。
     * <p>
     * 直接从 application.yml 中的 langchain4j.chat-model 配置读取参数构建模型。
     *
     * @return 聊天模型实例，已包装重试机制
     */
    public ChatModel buildChatModelFromYml() {
        AiModelProperties.ChatModelConfig chat = aiProps.getChatModel();
        return wrap(chat.getProvider() == AiProviderConfig.Provider.OPENAI
                ? buildOpenAiChat(chat, true)
                : buildOllamaChat(chat, true));
    }

    /**
     * 基于 yml 配置构建普通聊天模型（不包含思考过程）。
     * <p>
     * 直接从 application.yml 中的 langchain4j.chat-model 配置读取参数构建模型。
     *
     * @return 聊天模型实例，已包装重试机制
     */
    public ChatModel buildChatModelWithoutThinkingFromYml() {
        AiModelProperties.ChatModelConfig chat = aiProps.getChatModel();
        return wrap(chat.getProvider() == AiProviderConfig.Provider.OPENAI
                ? buildOpenAiChat(chat, false)
                : buildOllamaChat(chat, false));
    }

    /**
     * 基于 yml 配置构建流式聊天模型（包含思考过程）。
     * <p>
     * 直接从 application.yml 中的 langchain4j.chat-model 配置读取参数构建模型。
     *
     * @return 流式聊天模型实例
     */
    public StreamingChatModel buildStreamingChatModelFromYml() {
        AiModelProperties.ChatModelConfig chat = aiProps.getChatModel();
        return chat.getProvider() == AiProviderConfig.Provider.OPENAI
                ? buildOpenAiStreaming(chat, true)
                : buildOllamaStreaming(chat, true);
    }

    /**
     * 基于 yml 配置构建流式聊天模型（不包含思考过程）。
     * <p>
     * 直接从 application.yml 中的 langchain4j.chat-model 配置读取参数构建模型。
     *
     * @return 流式聊天模型实例
     */
    public StreamingChatModel buildStreamingChatModelWithoutThinkingFromYml() {
        AiModelProperties.ChatModelConfig chat = aiProps.getChatModel();
        return chat.getProvider() == AiProviderConfig.Provider.OPENAI
                ? buildOpenAiStreaming(chat, false)
                : buildOllamaStreaming(chat, false);
    }

    // ======================== 其他公开方法 ========================

        /**
     * 构建视觉模型（用于 OCR/PDF 处理）。
     * <p>
     * 使用 Ollama 视觉模型处理包含图片的内容，如 PDF 文档的 OCR 识别。
     * 优先使用 vision 配置中的模型名称和超时时间，如果未配置则回退到 chat-model 配置。
     *
     * @return 聊天模型实例，已包装重试机制，专门用于视觉任务
     */
    public ChatModel buildVisionChatModel() {
        AiModelProperties.ChatModelConfig chat = aiProps.getChatModel();
        AiModelProperties.VisionConfig vision = aiProps.getVision();
        String modelName = (vision.getModelName() != null && !vision.getModelName().isBlank())
                ? vision.getModelName() : chat.getModelName();
        Duration timeout = vision.getTimeout() != null ? vision.getTimeout() : Duration.ofSeconds(600);
        log.info("构建 OCR 视觉 ChatModel (Ollama): baseUrl={}, model={}, timeout={}s",
                chat.getBaseUrl(), modelName, timeout.getSeconds());
        return wrap(OllamaChatModel.builder()
                .baseUrl(chat.getBaseUrl()).modelName(modelName)
                .temperature(0.3).timeout(timeout)
                .customHeaders(AiModelProperties.UTF8_HEADERS)
                .build());
    }

    /**
     * 根据指定的配置 ID 构建聊天模型（用于测试）。
     * <p>
     * 直接从数据库中读取指定 ID 的配置并构建模型，忽略默认配置逻辑。
     *
     * @param configId AI 提供商配置的 ID
     * @return 聊天模型实例，已包装重试机制
     * @throws IllegalArgumentException 如果指定的配置不存在
     */
    public ChatModel buildChatModelForTest(Long configId) {
        AiProviderConfig config = configRepository.findById(configId)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + configId));
        return wrap(buildChat(config, true));
    }

    /**
     * 构建 Ollama 嵌入模型。
     * <p>
     * 用于生成文本向量表示，支持自定义服务地址、模型名称和超时时间。
     *
     * @param baseUrl   Ollama 服务地址
     * @param modelName 嵌入模型名称
     * @param timeout   请求超时时间，如果为 null 则使用默认 300 秒
     * @return 嵌入模型实例
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
     * <p>
     * 用于生成文本向量表示，支持 OpenAI 兼容 API（如 Gitee AI、DeepSeek 等）。
     *
     * @param baseUrl   API 服务地址
     * @param modelName 嵌入模型名称
     * @param apiKey    API Key
     * @param timeout   请求超时时间，如果为 null 则使用默认 300 秒
     * @return 嵌入模型实例
     */
    public EmbeddingModel buildOpenAiEmbeddingModel(String baseUrl, String modelName, String apiKey, Duration timeout) {
        var builder = OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl).modelName(modelName)
                .timeout(timeout != null ? timeout : Duration.ofSeconds(300));
        builder.apiKey(apiKey != null && !apiKey.isBlank() ? apiKey : "sk-placeholder");
        return builder.build();
    }

    /**
     * 构建默认的嵌入模型。
     * <p>
     * 从 yml 配置中读取嵌入模型参数，根据 provider 类型构建 Ollama 或 OpenAI 兼容模型。
     *
     * @return 嵌入模型实例
     */
    public EmbeddingModel buildDefaultEmbeddingModel() {
        AiModelProperties.EmbeddingModelConfig emb = aiProps.getEmbeddingModel();
        log.info("构建 EmbeddingModel: provider={}, baseUrl={}, model={}",
                emb.getProvider(), emb.getBaseUrl(), emb.getModelName());
        return emb.getProvider() == AiProviderConfig.Provider.OPENAI
                ? buildOpenAiEmbeddingModel(emb.getBaseUrl(), emb.getModelName(), emb.getApiKey(), emb.getTimeout())
                : buildOllamaEmbeddingModel(emb.getBaseUrl(), emb.getModelName(), emb.getTimeout());
    }

    /**
     * 判断当前配置的聊天模型是否支持 Tool Calling 功能。
     * <p>
     * 从 yml 配置中读取设置并判断。
     *
     * @return true 如果支持工具调用，false 否则
     */
    public boolean isToolsSupported() {
        return aiProps.isToolsSupported();
    }

    /**
     * 判断指定的 AI 提供商配置是否支持 Tool Calling 功能。
     * <p>
     * 优先级：
     * 1. 如果配置为 null，则回退到 yml 配置
     * 2. 如果配置中明确设置了 toolsEnabled，则使用该值
     * 3. 否则根据模型名称自动检测（已知 gemma3n 不支持）
     *
     * @param config AI 提供商配置
     * @return true 如果支持工具调用，false 否则
     */
    public boolean isToolsSupported(AiProviderConfig config) {
        if (config == null) return isToolsSupported();
        if (config.getToolsEnabled() != null) return config.getToolsEnabled();
        return !config.getModelName().toLowerCase().startsWith("gemma3n");
    }

    /**
     * 获取默认的 AI 服务基础地址。
     * <p>
     * 从 yml 配置的聊天模型中读取。
     *
     * @return 默认的基础 URL
     */
    public String getDefaultBaseUrl() {
        return aiProps.getChatModel().getBaseUrl();
    }

    /**
     * 获取嵌入模型的服务基础地址。
     * <p>
     * 从 yml 配置的嵌入模型中读取。
     *
     * @return 嵌入模型的基础 URL
     */
    public String getEmbeddingBaseUrl() {
        return aiProps.getEmbeddingModel().getBaseUrl();
    }

    /**
     * 获取当前配置的聊天模型名称。
     * <p>
     * 从 yml 配置的聊天模型中读取。
     *
     * @return 模型名称
     */
    public String getModelName() {
        return aiProps.getChatModel().getModelName();
    }

    /**
     * 获取当前配置的嵌入模型名称。
     * <p>
     * 从 yml 配置的嵌入模型中读取。
     *
     * @return 嵌入模型名称
     */
    public String getEmbeddingModelName() {
        return aiProps.getEmbeddingModel().getModelName();
    }

    /**
     * 获取 embedding 并发数。
     * <p>
     * 从 yml 配置的嵌入模型中读取，用于控制批量入库时的并行请求数。
     *
     * @return 并发线程数
     */
    public int getEmbeddingConcurrency() {
        return aiProps.getEmbeddingModel().getConcurrency();
    }

    // ======================== 内部：维度组合器 ========================

    /**
     * 解析聊天配置。
     * <p>
     * 从数据库中查询用途为 CHAT 且已启用的默认配置。
     *
     * @return AI 提供商配置，如果未找到则返回 null
     */
    private AiProviderConfig resolveChatConfig() {
        var result = configRepository.findByPurposeAndIsDefaultTrueAndEnabledTrue(
                AiProviderConfig.Purpose.CHAT.name());
        if (result.isPresent()) {
            AiProviderConfig config = result.get();
            log.info("resolveChatConfig: 命中数据库配置 id={}, name={}, provider={}, baseUrl={}, model={}, enabled={}, isDefault={}",
                    config.getId(), config.getName(), config.getProvider(), config.getBaseUrl(), config.getModelName(),
                    config.getEnabled(), config.getIsDefault());
        } else {
            log.warn("resolveChatConfig: 未找到数据库配置(purpose=CHAT, isDefault=true, enabled=true)，将回退到 yml 配置");
        }
        return result.orElse(null);
    }

    /**
     * 创建超时时间对象。
     * <p>
     * 如果传入的秒数为 null，则使用默认值 120 秒。
     *
     * @param seconds 超时秒数
     * @return Duration 对象
     */
    private Duration timeout(Integer seconds) {
        return Duration.ofSeconds(seconds != null ? seconds : 120);
    }

    /**
     * 包装聊天模型，添加重试机制。
     * <p>
     * 将原始模型包装为 {@link RetryableChatModel}，提供自动重试功能。
     *
     * @param model 原始聊天模型
     * @return 包装后的聊天模型
     */
    private ChatModel wrap(ChatModel model) {
        return new RetryableChatModel(model);
    }

    // ---- DB config 组合器 ----

    /**
     * 基于数据库配置构建聊天模型。
     * <p>
     * 根据提供商类型（OpenAI 或 Ollama）调用相应的构建方法。
     *
     * @param config   AI 提供商配置
     * @param thinking 是否启用思考过程
     * @return 聊天模型实例
     */
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

    /**
     * 基于数据库配置构建流式聊天模型。
     * <p>
     * 根据提供商类型（OpenAI 或 Ollama）调用相应的构建方法。
     *
     * @param config   AI 提供商配置
     * @param thinking 是否启用思考过程
     * @return 流式聊天模型实例
     */
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

    // ---- yml config 组合器（解包为原始参数）----

    /**
     * 基于 yml 配置构建 Ollama 聊天模型。
     * <p>
     * 从配置对象中提取参数并调用底层构建器。
     *
     * @param chat     yml 聊天模型配置
     * @param thinking 是否启用思考过程
     * @return Ollama 聊天模型实例
     */
    private OllamaChatModel buildOllamaChat(AiModelProperties.ChatModelConfig chat, boolean thinking) {
        return buildOllamaChat(chat.getBaseUrl(), chat.getModelName(),
                chat.getTemperature(), chat.getTimeout(), thinking);
    }

    /**
     * 基于 yml 配置构建 Ollama 流式聊天模型。
     * <p>
     * 从配置对象中提取参数并调用底层构建器。
     *
     * @param chat     yml 聊天模型配置
     * @param thinking 是否启用思考过程
     * @return Ollama 流式聊天模型实例
     */
    private OllamaStreamingChatModel buildOllamaStreaming(AiModelProperties.ChatModelConfig chat, boolean thinking) {
        return buildOllamaStreaming(chat.getBaseUrl(), chat.getModelName(),
                chat.getTemperature(), chat.getTimeout(), thinking);
    }

    /**
     * 基于 yml 配置构建 OpenAI 聊天模型。
     * <p>
     * 从配置对象中提取参数并调用底层构建器，记录日志。
     *
     * @param chat     yml 聊天模型配置
     * @param thinking 是否启用思考过程
     * @return OpenAI 聊天模型实例
     */
    private OpenAiChatModel buildOpenAiChat(AiModelProperties.ChatModelConfig chat, boolean thinking) {
        Duration t = chat.getTimeout() != null ? chat.getTimeout() : Duration.ofSeconds(120);
        log.info("构建 OpenAI ChatModel (yml): baseUrl={}, model={}, timeout={}s",
                chat.getBaseUrl(), chat.getModelName(), t.getSeconds());
        return buildOpenAiChat(chat.getBaseUrl(), chat.getModelName(),
                chat.getTemperature(), t, chat.getApiKey(), thinking);
    }

    /**
     * 基于 yml 配置构建 OpenAI 流式聊天模型。
     * <p>
     * 从配置对象中提取参数并调用底层构建器，记录日志。
     *
     * @param chat     yml 聊天模型配置
     * @param thinking 是否启用思考过程
     * @return OpenAI 流式聊天模型实例
     */
    private OpenAiStreamingChatModel buildOpenAiStreaming(AiModelProperties.ChatModelConfig chat, boolean thinking) {
        Duration t = chat.getTimeout() != null ? chat.getTimeout() : Duration.ofSeconds(120);
        log.info("构建 OpenAI StreamingChatModel (yml): baseUrl={}, model={}",
                chat.getBaseUrl(), chat.getModelName());
        return buildOpenAiStreaming(chat.getBaseUrl(), chat.getModelName(),
                chat.getTemperature(), t, chat.getApiKey(), thinking);
    }

    // ======================== 底层构建器（纯参数，无配置对象）=======================

    /**
     * 构建 Ollama 聊天模型（底层方法）。
     * <p>
     * 使用纯参数构建，不依赖配置对象，设置默认值并启用思考过程控制。
     *
     * @param baseUrl    Ollama 服务地址
     * @param modelName  模型名称
     * @param temperature 温度参数（0.0-2.0），如果为 null 则使用 0.7
     * @param timeout    超时时间，如果为 null 则使用 120 秒
     * @param thinking   是否启用思考过程
     * @return Ollama 聊天模型实例
     */
    private OllamaChatModel buildOllamaChat(String baseUrl, String modelName,
                                            Double temperature, Duration timeout, boolean thinking) {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl).modelName(modelName)
                .temperature(temperature != null ? temperature : 0.7)
                .timeout(timeout != null ? timeout : Duration.ofSeconds(120))
                .customHeaders(AiModelProperties.UTF8_HEADERS)
                .listeners(List.of(ollamaCounterListener()))
                .returnThinking(thinking).think(thinking)
                .build();
    }

    /**
     * 构建 Ollama 流式聊天模型（底层方法）。
     * <p>
     * 使用纯参数构建，不依赖配置对象，设置默认值并启用思考过程控制。
     *
     * @param baseUrl    Ollama 服务地址
     * @param modelName  模型名称
     * @param temperature 温度参数（0.0-2.0），如果为 null 则使用 0.7
     * @param timeout    超时时间，如果为 null 则使用 120 秒
     * @param thinking   是否启用思考过程
     * @return Ollama 流式聊天模型实例
     */
    private OllamaStreamingChatModel buildOllamaStreaming(String baseUrl, String modelName,
                                                          Double temperature, Duration timeout, boolean thinking) {
        return OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl).modelName(modelName)
                .temperature(temperature != null ? temperature : 0.7)
                .timeout(timeout != null ? timeout : Duration.ofSeconds(120))
                .customHeaders(AiModelProperties.UTF8_HEADERS)
                .httpClientBuilder(new CancellableHttpClientBuilder(HttpClientBuilderLoader.loadHttpClientBuilder()))
                .listeners(List.of(ollamaCounterListener()))
                .returnThinking(thinking).think(thinking)
                .build();
    }

    /**
     * 构建 OpenAI 聊天模型（底层方法）。
     * <p>
     * 使用纯参数构建，不依赖配置对象，设置默认值、API Key 和诊断监听器。
     *
     * @param baseUrl    OpenAI API 基础地址
     * @param modelName  模型名称
     * @param temperature 温度参数（0.0-2.0），如果为 null 则使用 0.7
     * @param timeout    超时时间，如果为 null 则使用 120 秒
     * @param apiKey     API Key，如果为空则使用占位符
     * @param thinking   是否启用思考过程
     * @return OpenAI 聊天模型实例
     */
    private OpenAiChatModel buildOpenAiChat(String baseUrl, String modelName,
                                            Double temperature, Duration timeout, String apiKey, boolean thinking) {
        var builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl).modelName(modelName)
                .temperature(temperature != null ? temperature : 0.7)
                .timeout(timeout != null ? timeout : Duration.ofSeconds(120))
                .returnThinking(thinking).sendThinking(thinking)
                .listeners(List.of(new DiagnosticChatListener()));
        builder.apiKey(apiKey != null && !apiKey.isBlank() ? apiKey : "sk-placeholder");
        return builder.build();
    }

    /**
     * 构建 OpenAI 流式聊天模型（底层方法）。
     * <p>
     * 使用纯参数构建，不依赖配置对象，设置默认值、API Key 和诊断监听器。
     *
     * @param baseUrl    OpenAI API 基础地址
     * @param modelName  模型名称
     * @param temperature 温度参数（0.0-2.0），如果为 null 则使用 0.7
     * @param timeout    超时时间，如果为 null 则使用 120 秒
     * @param apiKey     API Key，如果为空则使用占位符
     * @param thinking   是否启用思考过程
     * @return OpenAI 流式聊天模型实例
     */
    private OpenAiStreamingChatModel buildOpenAiStreaming(String baseUrl, String modelName,
                                                          Double temperature, Duration timeout, String apiKey, boolean thinking) {
        var builder = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl).modelName(modelName)
                .temperature(temperature != null ? temperature : 0.7)
                .timeout(timeout != null ? timeout : Duration.ofSeconds(120))
                .httpClientBuilder(new CancellableHttpClientBuilder(HttpClientBuilderLoader.loadHttpClientBuilder()))
                .returnThinking(thinking).sendThinking(thinking)
                .listeners(List.of(new DiagnosticChatListener()));
        builder.apiKey(apiKey != null && !apiKey.isBlank() ? apiKey : "sk-placeholder");
        return builder.build();
    }

    // ======================== Ollama KV 缓存管理 ========================

    /**
     * 创建 Ollama 请求计数器监听器。
     * <p>
     * 监听每次请求完成后的响应事件，在 Redis 中递增计数器。
     * 当请求次数达到重置间隔（50次）时，触发 Ollama 缓存软重置。
     *
     * @return 聊天模型监听器实例
     */
    private ChatModelListener ollamaCounterListener() {
        return new ChatModelListener() {
            @Override
            public void onRequest(ChatModelRequestContext ctx) {}

            @Override
            public void onResponse(ChatModelResponseContext ctx) {
                long count = redisTemplate.opsForValue().increment(OLLAMA_COUNTER_KEY);
                if (count > 0 && count % OLLAMA_RESET_INTERVAL == 0) {
                    performOllamaSoftReset(count);
                }
            }

            @Override
            public void onError(ChatModelErrorContext ctx) {}
        };
    }

    /**
     * 执行 Ollama 缓存软重置。
     * <p>
     * 通过发送一个空请求来触发 Ollama 服务的缓存清理，避免长时间运行后出现性能下降或缓存问题。
     * 包含完整的异常处理，确保重置失败不影响正常服务。
     *
     * @param count 当前请求计数
     */
    private void performOllamaSoftReset(long count) {
        try {
            log.info("========== Ollama KV 缓存软重置 (#{}) ==========", count);
            try {
                ChatModel chatModel = buildChatModel();
                if (chatModel instanceof OllamaChatModel) {
                    chatModel.chat(List.of(UserMessage.from("")));
                    log.info("Ollama ChatModel 缓存已重置");
                }
            } catch (Exception e) {
                log.warn("ChatModel 缓存重置失败: {}", e.getMessage());
            }
            log.info("========== Ollama 缓存重置完成 ==========");
        } catch (Exception e) {
            log.error("Ollama 缓存重置异常: {}", e.getMessage());
        }
    }
}
