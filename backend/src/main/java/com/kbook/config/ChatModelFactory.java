package com.kbook.config;

import com.kbook.config.properties.AiModelProperties;
import com.kbook.entity.AiProviderConfig;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.UserMessage;
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
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * AI 模型工厂 — 统一封装模型构建逻辑
 * <p>
 * 支持两种构建来源：
 * 1. 从 application.yml 静态配置（AiModelProperties）— 用于标签评分/OCR/Embedding 等固定场景
 * 2. 从数据库 AiProviderConfig 实体动态构建 — 用于管理员可配置的对话场景
 * <p>
 * 支持 Ollama 和 OpenAI 兼容 API 两种提供商。
 * <p>
 * 重要：所有模型构建必须设置 customHeaders 携带 Content-Type: application/json;charset=utf-8，
 * 否则 LangChain4j 默认请求头缺少字符集声明，Ollama 返回的中文内容会被按 iso-8859-1 解码导致乱码。
 * <p>
 * Ollama KV 缓存管理：
 * 所有 Ollama ChatModel/StreamingChatModel 都会添加请求计数监听器，
 * 每隔 100 次请求自动发送空消息软重置清除 KV 缓存，防止性能下降。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelFactory {

    private final AiModelProperties aiProps;
    private final StringRedisTemplate redisTemplate;

    /** Redis 计数器 key */
    private static final String OLLAMA_COUNTER_KEY = "ollama:request_count";

    /** 每隔多少次请求触发一次缓存重置 */
    private static final long OLLAMA_RESET_INTERVAL = 50;

    /**
     * Ollama 请求计数监听器 — 每次响应成功后通过 Redis 计数
     * <p>
     * 实现逻辑：
     * 1. onRequest: 请求开始时不做处理
     * 2. onResponse: 响应成功后递增计数器，达到阈值时触发缓存重置
     * 3. onError: 错误时不做处理
     */
    private ChatModelListener ollamaCounterListener() {
        return new ChatModelListener() {
            /**
             * 请求开始时的回调（空实现）
             */
            @Override
            public void onRequest(ChatModelRequestContext ctx) {}

            /**
             * 响应成功后的回调
             * 通过 Redis 原子递增计数器，达到重置间隔时触发 KV 缓存清理
             *
             * @param ctx 响应上下文，包含模型响应信息
             */
            @Override
            public void onResponse(ChatModelResponseContext ctx) {
                // 原子递增 Redis 计数器
                long count = redisTemplate.opsForValue().increment(OLLAMA_COUNTER_KEY);
                
                // 判断是否达到重置阈值（每 100 次请求）
                if (count > 0 && count % OLLAMA_RESET_INTERVAL == 0) {
                    performOllamaSoftReset(count);
                }
            }

            /**
             * 请求出错时的回调（空实现）
             */
            @Override
            public void onError(ChatModelErrorContext ctx) {}
        };
    }

    /**
     * 发送空消息软重置 Ollama KV 缓存
     * <p>
     * 背景：Ollama 随着同一类问题问多了会越来越慢（KV 缓存膨胀），
     * 发送空消息可以软重置清除 KV 缓存，恢复性能。
     * <p>
     * 实现步骤：
     * 1. 构建 ChatModel 并发送空消息触发缓存清理
     * 2. 构建 StreamingChatModel 并发送空消息触发缓存清理
     * 3. 阻塞等待异步请求完成
     *
     * @param count 当前请求计数，用于日志记录
     */
    private void performOllamaSoftReset(long count) {
        try {
            // 记录缓存重置开始日志
            log.info("========== Ollama KV 缓存软重置 (#{}) ==========", count);

            // ==================== 重置 ChatModel ====================
            try {
                // 构建标准的 ChatModel 实例
                ChatModel chatModel = buildChatModel();
                
                // 仅对 Ollama 模型执行缓存重置
                if (chatModel instanceof OllamaChatModel) {
                    // 发送空消息触发 KV 缓存清理
                    chatModel.chat(List.of(UserMessage.from("")));
                    log.info("Ollama ChatModel 缓存已重置");
                }
            } catch (Exception e) {
                // 捕获异常但不中断流程，继续尝试重置 StreamingChatModel
                log.warn("ChatModel 缓存重置失败: {}", e.getMessage());
            }

            // 记录缓存重置完成日志
            log.info("========== Ollama 缓存重置完成 ==========");
        } catch (Exception e) {
            // 捕获所有未处理的异常，防止影响正常业务流程
            log.error("Ollama 缓存重置异常: {}", e.getMessage());
        }
    }

    // ==================== 从 yml 配置构建（原有逻辑） ====================

    /**
     * 构建关闭思考模式的 ChatModel
     * <p>
     * 用途：用于生成追问等不需要深度推理的场景，减少响应时间。
     * <p>
     * 实现逻辑：
     * - OpenAI/DeepSeek: 通过 returnThinking(false)/sendThinking(false) 关闭思考过程输出
     * - Ollama: 直接构建标准模型（Ollama 无思考模式参数）
     *
     * @param config AI 提供商配置，包含 baseUrl、modelName、temperature 等
     * @return ChatModel 实例，已关闭思考模式
     */
    public ChatModel buildChatModelWithoutThinking(AiProviderConfig config) {
        // 如果配置为空，回退到 yml 默认配置
        if (config == null) {
            return buildChatModel(); // 无配置时回退到 yml 默认
        }

        // 计算超时时间，默认 120 秒
        Duration timeout = Duration.ofSeconds(config.getTimeout() != null ? config.getTimeout() : 120);
        
        // 获取提供商类型
        String provider = config.getProvider();

        // 记录构建日志
        log.info("构建 ChatModel 关闭思考模式: provider={}, model={}, baseUrl={}",
                provider, config.getModelName(), config.getBaseUrl());

        // 根据提供商类型选择构建方式
        if (AiProviderConfig.Provider.OPENAI.name().equalsIgnoreCase(provider)) {
            // OpenAI 兼容 API：构建关闭思考模式的模型
            return buildOpenAiChatModelNoThinking(config, timeout);
        } else {
            // Ollama：构建标准模型
            return buildOllamaChatModel(config, timeout);
        }
    }

    /**
     * 构建默认的 ChatModel（从 yml 配置）
     * <p>
     * 使用 application.yml 中的 chat-model 配置构建 Ollama 模型，
     * 并附加请求计数监听器用于 KV 缓存管理。
     *
     * @return ChatModel 实例
     */
    public ChatModel buildChatModel() {
        // 获取 yml 中的 chat-model 配置
        var chat = aiProps.getChatModel();
        
        // 构建 Ollama ChatModel
        return OllamaChatModel.builder()
                .baseUrl(chat.getBaseUrl())              // 设置 Ollama 服务地址
                .modelName(chat.getModelName())          // 设置模型名称
                .temperature(chat.getTemperature())      // 设置温度参数（控制随机性）
                .timeout(chat.getTimeout() != null ? chat.getTimeout() : Duration.ofSeconds(120))  // 设置超时时间
                .customHeaders(AiModelProperties.UTF8_HEADERS)  // 设置 UTF-8 编码头，防止中文乱码
                .listeners(List.of(ollamaCounterListener()))    // 添加请求计数监听器
                .build();
    }

    /**
     * 构建视觉 ChatModel（用于 OCR 图片识别）
     * <p>
     * 特点：
     * 1. 优先使用 vision 配置，未配置则回退到 chat-model
     * 2. 固定 temperature=0.3，保证 OCR 结果的准确性
     * 3. 默认超时 600 秒，适应大图片处理
     *
     * @return ChatModel 实例，专用于 OCR 任务
     */
    public ChatModel buildVisionChatModel() {
        // 获取 chat-model 和 vision 配置
        var chat = aiProps.getChatModel();
        var vision = aiProps.getVision();

        // 确定模型名称：优先使用 vision 配置，否则使用 chat-model
        String modelName = (vision.getModelName() != null && !vision.getModelName().isBlank())
                ? vision.getModelName()
                : chat.getModelName();

        // 设置超时时间，默认 600 秒（OCR 任务可能较慢）
        Duration timeout = vision.getTimeout() != null ? vision.getTimeout() : Duration.ofSeconds(600);
        
        // 固定温度为 0.3，降低随机性，提高 OCR 准确性
        double temperature = 0.3;

        // 记录构建日志
        log.info("构建 OCR 视觉 ChatModel (Ollama): baseUrl={}, model={}, timeout={}s",
                chat.getBaseUrl(), modelName, timeout.getSeconds());
        
        // 构建 Ollama ChatModel
        return OllamaChatModel.builder()
                .baseUrl(chat.getBaseUrl())              // 设置 Ollama 服务地址
                .modelName(modelName)                    // 设置模型名称
                .temperature(temperature)                // 设置低温参数（0.3）
                .timeout(timeout)                        // 设置长超时时间
                .customHeaders(AiModelProperties.UTF8_HEADERS)  // 设置 UTF-8 编码头
                .build();
    }

    /**
     * 构建默认的流式 ChatModel（从 yml 配置）
     * <p>
     * 用于 SSE 流式响应场景，支持逐 token 输出。
     * 附加请求计数监听器用于 KV 缓存管理。
     *
     * @return StreamingChatModel 实例
     */
    public StreamingChatModel buildStreamingChatModel() {
        // 获取 yml 中的 chat-model 配置
        var chat = aiProps.getChatModel();
        
        // 构建 Ollama 流式 ChatModel
        return OllamaStreamingChatModel.builder()
                .baseUrl(chat.getBaseUrl())              // 设置 Ollama 服务地址
                .modelName(chat.getModelName())          // 设置模型名称
                .temperature(chat.getTemperature())      // 设置温度参数
                .timeout(chat.getTimeout() != null ? chat.getTimeout() : Duration.ofSeconds(120))  // 设置超时时间
                .customHeaders(AiModelProperties.UTF8_HEADERS)  // 设置 UTF-8 编码头
                .listeners(List.of(ollamaCounterListener()))    // 添加请求计数监听器
                .build();
    }

    /**
     * 构建 Ollama Embedding 模型
     * <p>
     * 用于文本向量化，支持自定义 baseUrl 和 modelName。
     *
     * @param baseUrl   Ollama 服务地址
     * @param modelName 嵌入模型名称
     * @param timeout   超时时间（优先使用 yml 配置，默认 300 秒）
     * @return EmbeddingModel 实例
     */
    public EmbeddingModel buildOllamaEmbeddingModel(String baseUrl, String modelName, Duration timeout) {
        return OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(timeout != null ? timeout : Duration.ofSeconds(300))
                .customHeaders(AiModelProperties.UTF8_HEADERS)
                .build();
    }

    /**
     * 构建默认的 Embedding 模型（从 yml 配置）
     *
     * @return EmbeddingModel 实例
     */
    public EmbeddingModel buildDefaultEmbeddingModel() {
        var embedding = aiProps.getEmbeddingModel();
        return buildOllamaEmbeddingModel(embedding.getBaseUrl(), embedding.getModelName(), embedding.getTimeout());
    }

    /**
     * 获取默认 Embedding 模型名称
     *
     * @return 模型名称字符串
     */
    public String getEmbeddingModelName() {
        return aiProps.getEmbeddingModel().getModelName();
    }

    /**
     * 获取默认 ChatModel 的 baseUrl
     *
     * @return baseUrl 字符串
     */
    public String getDefaultBaseUrl() {
        return aiProps.getChatModel().getBaseUrl();
    }

    /**
     * 获取默认 ChatModel 的模型名称
     *
     * @return 模型名称字符串
     */
    public String getModelName() {
        return aiProps.getChatModel().getModelName();
    }

    /**
     * 判断默认配置是否支持 Tool Calling（函数调用）
     *
     * @return true-支持, false-不支持
     */
    public boolean isToolsSupported() {
        return aiProps.isToolsSupported();
    }

    // ==================== 从数据库配置动态构建 ====================

    /**
     * 从 AiProviderConfig 实体构建 ChatModel
     * <p>
     * 用途：支持管理员在后台动态配置 AI 模型，无需重启服务。
     * <p>
     * 实现逻辑：
     * 1. 根据 provider 字段自动选择 Ollama 或 OpenAI 兼容 API 构建
     * 2. 使用配置中的 baseUrl、modelName、temperature、timeout 等参数
     * 3. 如果配置为空，回退到 yml 默认配置
     *
     * @param config AI 提供商配置实体（从数据库读取）
     * @return ChatModel 实例
     */
    public ChatModel buildChatModel(AiProviderConfig config) {
        // 如果配置为空，回退到 yml 默认配置
        if (config == null) {
            return buildChatModel(); // 无配置时回退到 yml 默认
        }

        // 计算超时时间，默认 120 秒
        Duration timeout = Duration.ofSeconds(config.getTimeout() != null ? config.getTimeout() : 120);
        
        // 获取提供商类型
        String provider = config.getProvider();

        // 记录构建日志，包含所有关键参数
        log.info("构建 ChatModel (from DB): provider={}, model={}, baseUrl={}, temperature={}, timeout={}s",
                provider, config.getModelName(), config.getBaseUrl(), config.getTemperature(), timeout.getSeconds());

        // 根据提供商类型选择构建方式
        if (AiProviderConfig.Provider.OPENAI.name().equalsIgnoreCase(provider)) {
            // OpenAI 兼容 API：构建带思考模式的模型
            return buildOpenAiChatModel(config, timeout);
        } else {
            // Ollama：构建标准模型
            return buildOllamaChatModel(config, timeout);
        }
    }

    /**
     * 从 AiProviderConfig 实体构建 StreamingChatModel
     * <p>
     * 用于 SSE 流式响应场景，支持逐 token 输出。
     *
     * @param config AI 提供商配置实体（从数据库读取）
     * @return StreamingChatModel 实例
     */
    public StreamingChatModel buildStreamingChatModel(AiProviderConfig config) {
        // 如果配置为空，回退到 yml 默认配置
        if (config == null) {
            return buildStreamingChatModel(); // 无配置时回退到 yml 默认
        }

        // 计算超时时间，默认 120 秒
        Duration timeout = Duration.ofSeconds(config.getTimeout() != null ? config.getTimeout() : 120);
        
        // 获取提供商类型
        String provider = config.getProvider();

        // 记录构建日志
        log.info("构建 StreamingChatModel (from DB): provider={}, model={}, baseUrl={}",
                provider, config.getModelName(), config.getBaseUrl());

        // 根据提供商类型选择构建方式
        if (AiProviderConfig.Provider.OPENAI.name().equalsIgnoreCase(provider)) {
            // OpenAI 兼容 API：构建流式模型
            return buildOpenAiStreamingChatModel(config, timeout);
        } else {
            // Ollama：构建流式模型
            return buildOllamaStreamingChatModel(config, timeout);
        }
    }

    /**
     * 判断指定配置是否支持 Tool Calling（函数调用）
     * <p>
     * 优先级：
     * 1. 显式配置 toolsEnabled 字段（最高优先级）
     * 2. 未配置则按模型名自动检测（gemma3n 不支持）
     *
     * @param config AI 提供商配置实体
     * @return true-支持 Tool Calling, false-不支持
     */
    public boolean isToolsSupported(AiProviderConfig config) {
        // 如果配置为空，回退到 yml 默认配置
        if (config == null) {
            return isToolsSupported(); // 无配置时回退到 yml 默认
        }
        
        // 优先使用显式配置的 toolsEnabled 字段
        if (config.getToolsEnabled() != null) {
            return config.getToolsEnabled();
        }
        
        // 自动检测：已知不支持 tools 的模型
        String model = config.getModelName().toLowerCase();
        return !model.startsWith("gemma3n");  // gemma3n 系列不支持工具调用
    }

    // ==================== 内部构建方法 ====================

    /**
     * 构建 Ollama ChatModel（内部方法）
     *
     * @param config  AI 提供商配置
     * @param timeout 超时时间
     * @return OllamaChatModel 实例，带请求计数监听器
     */
    private ChatModel buildOllamaChatModel(AiProviderConfig config, Duration timeout) {
        return OllamaChatModel.builder()
                .baseUrl(config.getBaseUrl())                                    // 设置 Ollama 服务地址
                .modelName(config.getModelName())                                // 设置模型名称
                .temperature(config.getTemperature() != null ? config.getTemperature() : 0.7)  // 设置温度，默认 0.7
                .timeout(timeout)                                                // 设置超时时间
                .customHeaders(AiModelProperties.UTF8_HEADERS)                   // 设置 UTF-8 编码头，防止中文乱码
                .listeners(List.of(ollamaCounterListener()))                     // 添加请求计数监听器
                .build();
    }

    /**
     * 构建 Ollama StreamingChatModel（内部方法）
     *
     * @param config  AI 提供商配置
     * @param timeout 超时时间
     * @return OllamaStreamingChatModel 实例，带请求计数监听器
     */
    private StreamingChatModel buildOllamaStreamingChatModel(AiProviderConfig config, Duration timeout) {
        return OllamaStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())                                    // 设置 Ollama 服务地址
                .modelName(config.getModelName())                                // 设置模型名称
                .temperature(config.getTemperature() != null ? config.getTemperature() : 0.7)  // 设置温度，默认 0.7
                .timeout(timeout)                                                // 设置超时时间
                .customHeaders(AiModelProperties.UTF8_HEADERS)                   // 设置 UTF-8 编码头
                .listeners(List.of(ollamaCounterListener()))                     // 添加请求计数监听器
                .build();
    }

    /**
     * 构建 OpenAI 兼容 API ChatModel（带思考模式）
     * <p>
     * 特点：
     * - returnThinking(true): 返回模型的思考过程
     * - sendThinking(true): 发送思考过程给前端
     * - 使用 DiagnosticChatListener 进行诊断日志记录
     *
     * @param config  AI 提供商配置
     * @param timeout 超时时间
     * @return OpenAiChatModel 实例
     */
    private ChatModel buildOpenAiChatModel(AiProviderConfig config, Duration timeout) {
        // 构建器初始化，设置基础参数
        var builder = OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())                                    // 设置 API 地址
                .modelName(config.getModelName())                                // 设置模型名称
                .temperature(config.getTemperature() != null ? config.getTemperature() : 0.7)  // 设置温度，默认 0.7
                .timeout(timeout)                                                // 设置超时时间
                .returnThinking(true)                                            // 启用返回思考过程
                .sendThinking(true)                                              // 启用发送思考过程
                .listeners(List.of(new DiagnosticChatListener()));               // 添加诊断监听器
        
        // 设置 API Key：优先使用配置的 key，否则使用占位符
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            builder.apiKey(config.getApiKey());
        } else {
            builder.apiKey("sk-placeholder");  // 本地 Ollama 等不需要真实 key
        }
        
        return builder.build();
    }

    /**
     * 构建 OpenAI 兼容 API ChatModel（关闭思考模式）
     * <p>
     * 用于不需要深度推理的场景，减少响应时间。
     *
     * @param config  AI 提供商配置
     * @param timeout 超时时间
     * @return OpenAiChatModel 实例，已关闭思考模式
     */
    private ChatModel buildOpenAiChatModelNoThinking(AiProviderConfig config, Duration timeout) {
        // 构建器初始化，设置基础参数
        var builder = OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())                                    // 设置 API 地址
                .modelName(config.getModelName())                                // 设置模型名称
                .temperature(config.getTemperature() != null ? config.getTemperature() : 0.7)  // 设置温度，默认 0.7
                .timeout(timeout)                                                // 设置超时时间
                .returnThinking(false)                                           // 禁用返回思考过程
                .sendThinking(false)                                             // 禁用发送思考过程
                .listeners(List.of(new DiagnosticChatListener()));               // 添加诊断监听器
        
        // 设置 API Key：优先使用配置的 key，否则使用占位符
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            builder.apiKey(config.getApiKey());
        } else {
            builder.apiKey("sk-placeholder");
        }
        
        return builder.build();
    }

    /**
     * 构建 OpenAI 兼容 API StreamingChatModel（带思考模式）
     * <p>
     * 用于 SSE 流式响应场景，支持逐 token 输出思考过程。
     *
     * @param config  AI 提供商配置
     * @param timeout 超时时间
     * @return OpenAiStreamingChatModel 实例
     */
    private StreamingChatModel buildOpenAiStreamingChatModel(AiProviderConfig config, Duration timeout) {
        // 构建器初始化，设置基础参数
        var builder = OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())                                    // 设置 API 地址
                .modelName(config.getModelName())                                // 设置模型名称
                .temperature(config.getTemperature() != null ? config.getTemperature() : 0.7)  // 设置温度，默认 0.7
                .timeout(timeout)                                                // 设置超时时间
                .returnThinking(true)                                            // 启用返回思考过程
                .sendThinking(true)                                              // 启用发送思考过程
                .listeners(List.of(new DiagnosticChatListener()));               // 添加诊断监听器
        
        // 设置 API Key：优先使用配置的 key，否则使用占位符
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            builder.apiKey(config.getApiKey());
        } else {
            builder.apiKey("sk-placeholder");
        }
        
        return builder.build();
    }
}
