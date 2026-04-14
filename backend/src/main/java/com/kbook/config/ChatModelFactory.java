package com.kbook.config;

import com.kbook.entity.AiProviderConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 模型工厂 — 统一封装 Ollama / OpenAI 兼容模型的构建逻辑
 * <p>
 * 所有 ChatModel 和 EmbeddingModel 的创建都通过此类进行，
 * 业务 Service 不再直接依赖 langchain4j-ollama / langchain4j-open-ai 的具体实现类。
 */
@Slf4j
@Component
public class ChatModelFactory {

    @Value("${langchain4j.ollama.chat-model.base-url:http://localhost:11434}")
    private String defaultBaseUrl;

    @Value("${langchain4j.ollama.chat-model.model-name:gemma4:e4b}")
    private String defaultModelName;

    @Value("${langchain4j.ollama.chat-model.temperature:0.7}")
    private Double defaultTemperature;

    @Value("${langchain4j.ollama.chat-model.timeout:120s}")
    private Duration defaultTimeout;

    @Value("${langchain4j.ollama.embedding-model.model-name:qwen3-embedding:0.6b}")
    private String defaultEmbeddingModelName;

    // ==================== ChatModel 构建 ====================

    /**
     * 根据 AiProviderConfig 构建 ChatModel
     */
    public ChatModel buildChatModel(AiProviderConfig config) {
        double temperature = config.getTemperature() != null ? config.getTemperature() : defaultTemperature;
        int maxTokens = config.getMaxTokens() != null ? config.getMaxTokens() : 2048;
        String thinkingLevel = config.getThinkingLevel() != null ? config.getThinkingLevel() : "NONE";

        if ("OLLAMA".equalsIgnoreCase(config.getProvider())) {
            Duration baseTimeout = Duration.ofSeconds(120);
            Duration timeout = getTimeoutWithDuration(thinkingLevel, baseTimeout);
            return OllamaChatModel.builder()
                    .baseUrl(config.getBaseUrl())
                    .modelName(config.getModelName())
                    .temperature(temperature)
                    .timeout(timeout)
                    .build();
        } else {
            // OpenAI 兼容（含 DeepSeek、通义千问、智谱等）
            Duration baseTimeout = Duration.ofSeconds(60);
            Duration timeout = getTimeoutWithDuration(thinkingLevel, baseTimeout);
            return OpenAiChatModel.builder()
                    .apiKey(config.getApiKey() != null ? config.getApiKey() : "sk-placeholder")
                    .baseUrl(config.getBaseUrl())
                    .modelName(config.getModelName())
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .timeout(timeout)
                    .build();
        }
    }

    /**
     * 构建默认 ChatModel（使用 application.yml 中的 Ollama 配置）
     */
    public ChatModel buildDefaultChatModel() {
        return OllamaChatModel.builder()
                .baseUrl(defaultBaseUrl)
                .modelName(defaultModelName)
                .temperature(defaultTemperature)
                .timeout(defaultTimeout != null ? defaultTimeout : Duration.ofSeconds(120))
                .build();
    }

    /**
     * 根据配置构建 ChatModel，如果没有活跃配置则使用默认
     */
    public ChatModel buildChatModelOrDefault(AiProviderConfig activeConfig) {
        if (activeConfig != null) {
            return buildChatModel(activeConfig);
        }
        return buildDefaultChatModel();
    }

    // ==================== Vision ChatModel 构建（PDF OCR 用） ====================

    /** PDF OCR 视觉模型名称（需支持图片输入，如 gemma4:e4b、llava、qwen2.5vl 等） */
    @Value("${kbook.ai.vision-model-name:}")
    private String visionModelName;

    /** PDF OCR 视觉模型超时（OCR 处理图片更慢，需要更长超时） */
    @Value("${kbook.ai.vision-timeout:600s}")
    private Duration visionTimeout;

    /**
     * 构建用于 PDF OCR 的视觉 ChatModel
     * <p>
     * 如果配置了专用的视觉模型名称 (kbook.ai.vision-model-name)，则使用该模型；
     * 否则使用管理员配置的活跃模型（需确保该模型支持视觉能力）。
     * OCR 超时默认 10 分钟（图片编码消耗大量 token，处理较慢）。
     *
     * @param activeConfig 管理员配置的活跃 AI 配置
     * @return ChatModel（支持视觉/多模态）
     */
    public ChatModel buildVisionChatModel(AiProviderConfig activeConfig) {
        // 确定使用的模型名称：优先使用专用视觉模型，否则使用活跃配置的模型
        String modelName = (visionModelName != null && !visionModelName.isBlank())
                ? visionModelName
                : (activeConfig != null ? activeConfig.getModelName() : defaultModelName);

        // 确定基础 URL
        String baseUrl = (activeConfig != null && activeConfig.getBaseUrl() != null && !activeConfig.getBaseUrl().isBlank())
                ? activeConfig.getBaseUrl()
                : defaultBaseUrl;

        // 确定提供商类型
        boolean isOllama = activeConfig == null || "OLLAMA".equalsIgnoreCase(activeConfig.getProvider());

        Duration timeout = visionTimeout != null ? visionTimeout : Duration.ofSeconds(600);
        double temperature = 0.3; // OCR 任务用低温度，保证准确性

        if (isOllama) {
            log.info("构建 OCR 视觉 ChatModel (Ollama): baseUrl={}, model={}, timeout={}s",
                    baseUrl, modelName, timeout.getSeconds());
            return OllamaChatModel.builder()
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .temperature(temperature)
                    .timeout(timeout)
                    .build();
        } else {
            String apiKey = activeConfig.getApiKey() != null
                    ? activeConfig.getApiKey() : "sk-placeholder";
            log.info("构建 OCR 视觉 ChatModel (OpenAI兼容): baseUrl={}, model={}, timeout={}s",
                    baseUrl, modelName, timeout.getSeconds());
            return OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .temperature(temperature)
                    .maxTokens(4096)
                    .timeout(timeout)
                    .build();
        }
    }

    // ==================== EmbeddingModel 构建 ====================

    /**
     * 构建 Ollama Embedding 模型（当前仅支持 Ollama）
     */
    public EmbeddingModel buildOllamaEmbeddingModel(String baseUrl, String modelName) {
        return OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    /**
     * 使用默认配置构建 Ollama Embedding 模型
     */
    public EmbeddingModel buildDefaultEmbeddingModel() {
        return buildOllamaEmbeddingModel(defaultBaseUrl, defaultEmbeddingModelName);
    }

    /**
     * 根据活跃 AI 配置自动选择 baseUrl 构建 Embedding 模型
     */
    public EmbeddingModel buildEmbeddingModel(AiProviderConfig activeConfig) {
        String baseUrl = defaultBaseUrl;
        if (activeConfig != null && activeConfig.getBaseUrl() != null && !activeConfig.getBaseUrl().isBlank()) {
            baseUrl = activeConfig.getBaseUrl();
            log.info("使用管理员配置的 AI 模型生成 Embedding: baseUrl={}, model={}", baseUrl, defaultEmbeddingModelName);
        }
        return buildOllamaEmbeddingModel(baseUrl, defaultEmbeddingModelName);
    }

    // ==================== 辅助方法 ====================

    /**
     * 根据 Thinking 等级计算超时时间
     * NONE = 基础超时, LOW = 2x, MEDIUM = 4x, HIGH = 8x
     */
    private Duration getTimeoutWithDuration(String thinkingLevel, Duration baseTimeout) {
        if (thinkingLevel == null || "NONE".equalsIgnoreCase(thinkingLevel)) {
            return baseTimeout;
        }
        int multiplier = switch (thinkingLevel.toUpperCase()) {
            case "LOW" -> 2;
            case "MEDIUM" -> 4;
            case "HIGH" -> 8;
            default -> 1;
        };
        return baseTimeout.multipliedBy(multiplier);
    }
}
