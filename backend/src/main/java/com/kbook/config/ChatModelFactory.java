package com.kbook.config;

import com.kbook.config.properties.AiModelProperties;
import com.kbook.entity.AiProviderConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelFactory {

    private final AiModelProperties aiProps;

    // ==================== 从 yml 配置构建（原有逻辑） ====================

    public ChatModel buildChatModel() {
        var chat = aiProps.getChatModel();
        return OllamaChatModel.builder()
                .baseUrl(chat.getBaseUrl())
                .modelName(chat.getModelName())
                .temperature(chat.getTemperature())
                .timeout(chat.getTimeout() != null ? chat.getTimeout() : Duration.ofSeconds(120))
                .customHeaders(AiModelProperties.UTF8_HEADERS)
                .build();
    }

    public ChatModel buildVisionChatModel() {
        var chat = aiProps.getChatModel();
        var vision = aiProps.getVision();

        String modelName = (vision.getModelName() != null && !vision.getModelName().isBlank())
                ? vision.getModelName()
                : chat.getModelName();

        Duration timeout = vision.getTimeout() != null ? vision.getTimeout() : Duration.ofSeconds(600);
        double temperature = 0.3; // OCR 任务用低温度，保证准确性

        log.info("构建 OCR 视觉 ChatModel (Ollama): baseUrl={}, model={}, timeout={}s",
                chat.getBaseUrl(), modelName, timeout.getSeconds());
        return OllamaChatModel.builder()
                .baseUrl(chat.getBaseUrl())
                .modelName(modelName)
                .temperature(temperature)
                .timeout(timeout)
                .customHeaders(AiModelProperties.UTF8_HEADERS)
                .build();
    }

    public StreamingChatModel buildStreamingChatModel() {
        var chat = aiProps.getChatModel();
        return OllamaStreamingChatModel.builder()
                .baseUrl(chat.getBaseUrl())
                .modelName(chat.getModelName())
                .temperature(chat.getTemperature())
                .timeout(chat.getTimeout() != null ? chat.getTimeout() : Duration.ofSeconds(120))
                .customHeaders(AiModelProperties.UTF8_HEADERS)
                .build();
    }

    public EmbeddingModel buildOllamaEmbeddingModel(String baseUrl, String modelName) {
        return OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(120))
                .customHeaders(AiModelProperties.UTF8_HEADERS)
                .build();
    }

    public EmbeddingModel buildDefaultEmbeddingModel() {
        var embedding = aiProps.getEmbeddingModel();
        return buildOllamaEmbeddingModel(embedding.getBaseUrl(), embedding.getModelName());
    }

    public String getEmbeddingModelName() {
        return aiProps.getEmbeddingModel().getModelName();
    }

    public String getDefaultBaseUrl() {
        return aiProps.getChatModel().getBaseUrl();
    }

    public String getModelName() {
        return aiProps.getChatModel().getModelName();
    }

    public boolean isToolsSupported() {
        return aiProps.isToolsSupported();
    }

    // ==================== 从数据库配置动态构建 ====================

    /**
     * 从 AiProviderConfig 实体构建 ChatModel
     * <p>
     * 根据 provider 字段自动选择 Ollama 或 OpenAI 兼容 API 构建。
     */
    public ChatModel buildChatModel(AiProviderConfig config) {
        if (config == null) {
            return buildChatModel(); // 无配置时回退到 yml 默认
        }

        Duration timeout = Duration.ofSeconds(config.getTimeout() != null ? config.getTimeout() : 120);
        String provider = config.getProvider();

        log.info("构建 ChatModel (from DB): provider={}, model={}, baseUrl={}, temperature={}, timeout={}s",
                provider, config.getModelName(), config.getBaseUrl(), config.getTemperature(), timeout.getSeconds());

        if (AiProviderConfig.Provider.OPENAI.name().equalsIgnoreCase(provider)) {
            return buildOpenAiChatModel(config, timeout);
        } else {
            return buildOllamaChatModel(config, timeout);
        }
    }

    /**
     * 从 AiProviderConfig 实体构建 StreamingChatModel
     */
    public StreamingChatModel buildStreamingChatModel(AiProviderConfig config) {
        if (config == null) {
            return buildStreamingChatModel(); // 无配置时回退到 yml 默认
        }

        Duration timeout = Duration.ofSeconds(config.getTimeout() != null ? config.getTimeout() : 120);
        String provider = config.getProvider();

        log.info("构建 StreamingChatModel (from DB): provider={}, model={}, baseUrl={}",
                provider, config.getModelName(), config.getBaseUrl());

        if (AiProviderConfig.Provider.OPENAI.name().equalsIgnoreCase(provider)) {
            return buildOpenAiStreamingChatModel(config, timeout);
        } else {
            return buildOllamaStreamingChatModel(config, timeout);
        }
    }

    /**
     * 判断指定配置是否支持 Tool Calling
     * <p>
     * 优先使用显式配置 toolsEnabled，未配置则按模型名自动检测
     */
    public boolean isToolsSupported(AiProviderConfig config) {
        if (config == null) {
            return isToolsSupported(); // 无配置时回退到 yml 默认
        }
        if (config.getToolsEnabled() != null) {
            return config.getToolsEnabled();
        }
        // 自动检测：已知不支持 tools 的模型
        String model = config.getModelName().toLowerCase();
        return !model.startsWith("gemma3n");
    }

    // ==================== 内部构建方法 ====================

    private ChatModel buildOllamaChatModel(AiProviderConfig config, Duration timeout) {
        return OllamaChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .temperature(config.getTemperature() != null ? config.getTemperature() : 0.7)
                .timeout(timeout)
                .customHeaders(AiModelProperties.UTF8_HEADERS)
                .build();
    }

    private StreamingChatModel buildOllamaStreamingChatModel(AiProviderConfig config, Duration timeout) {
        return OllamaStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .temperature(config.getTemperature() != null ? config.getTemperature() : 0.7)
                .timeout(timeout)
                .customHeaders(AiModelProperties.UTF8_HEADERS)
                .build();
    }

    private ChatModel buildOpenAiChatModel(AiProviderConfig config, Duration timeout) {
        var builder = OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .temperature(config.getTemperature() != null ? config.getTemperature() : 0.7)
                .timeout(timeout)
                .returnThinking(true)
                .sendThinking(true)
                .listeners(List.of(new DiagnosticChatListener()));
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            builder.apiKey(config.getApiKey());
        } else {
            builder.apiKey("sk-placeholder"); // 某些兼容 API 不需要 key，但字段不能为空
        }
        return builder.build();
    }

    private StreamingChatModel buildOpenAiStreamingChatModel(AiProviderConfig config, Duration timeout) {
        var builder = OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .temperature(config.getTemperature() != null ? config.getTemperature() : 0.7)
                .timeout(timeout)
                .returnThinking(true)
                .sendThinking(true)
                .listeners(List.of(new DiagnosticChatListener()));
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            builder.apiKey(config.getApiKey());
        } else {
            builder.apiKey("sk-placeholder");
        }
        return builder.build();
    }
}
