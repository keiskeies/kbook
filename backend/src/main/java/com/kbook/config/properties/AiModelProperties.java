package com.kbook.config.properties;

import com.kbook.entity.AiProviderConfig;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 模型配置属性 — 统一管理所有 AI/LLM 相关配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "langchain4j")
public class AiModelProperties {

    /** 聊天模型配置 */
    private ChatModelConfig chatModel = new ChatModelConfig();
    /** 嵌入模型配置 */
    private EmbeddingModelConfig embeddingModel = new EmbeddingModelConfig();

    /** AI 视觉模型配置（PDF OCR 用） */
    private VisionConfig vision = new VisionConfig();

    /** 聊天模型配置 */
    @Data
    public static class ChatModelConfig {
        /** 提供商类型：OLLAMA（默认）或 OPENAI */
        private AiProviderConfig.Provider provider = AiProviderConfig.Provider.OLLAMA;
        /** 服务地址（Ollama 默认 <a href="http://localhost:11434">...</a>，OpenAI 兼容 API 填对应地址） */
        private String baseUrl = "http://localhost:11434";
        /** 模型名称 */
        private String modelName = "gemma3n:e4b";
        /** API Key（OpenAI 兼容 API 需要；Ollama 可留空） */
        private String apiKey;
        /** 温度参数（控制生成随机性，0-2） */
        private double temperature = 0.7;
        /** 请求超时时间 */
        private Duration timeout = Duration.ofSeconds(120);
        /** 是否启用 Tool Calling（部分模型如 gemma3n 不支持 tools）。null=自动检测 */
        private Boolean toolsEnabled;
    }

    /** 嵌入模型配置 */
    @Data
    public static class EmbeddingModelConfig {
        /** 提供商类型：OLLAMA（默认）或 OPENAI */
        private AiProviderConfig.Provider provider = AiProviderConfig.Provider.OLLAMA;
        /** 服务地址（Ollama 默认 localhost:11434，OpenAI 兼容 API 填对应地址） */
        private String baseUrl = "http://localhost:11434";
        /** 嵌入模型名称 */
        private String modelName = "bge-m3:latest";
        /** API Key（OpenAI 兼容 API 需要；Ollama 可留空） */
        private String apiKey;
        /** 请求超时时间 */
        private Duration timeout = Duration.ofSeconds(300);
        /** embedding 并发数（批量入库时并行发送 API 请求的线程数，默认 3） */
        private int concurrency = 3;
    }

    @Data
    public static class VisionConfig {
        /** PDF OCR 视觉模型名称（需支持图片输入，如 llava、gemma3n:e4b、minicpm-v 等） */
        private String modelName = "";
        /** PDF OCR 视觉模型超时（OCR 处理图片更慢，需要更长超时） */
        private Duration timeout = Duration.ofSeconds(600);
    }

    /** 修复中文乱码：强制声明 Content-Type 含 UTF-8 字符集 */
    public static final java.util.Map<String, String> UTF8_HEADERS = java.util.Map.of(
            "Content-Type", "application/json;charset=utf-8"
    );

    /**
     * 判断当前配置的聊天模型是否支持 Tool Calling
     * 优先使用显式配置 toolsEnabled，未配置则按模型名自动检测
     */
    public boolean isToolsSupported() {
        if (chatModel.getToolsEnabled() != null) {
            return chatModel.getToolsEnabled();
        }
        // OpenAI 兼容 API 普遍支持 tools
        if (chatModel.getProvider() == AiProviderConfig.Provider.OPENAI) {
            return true;
        }
        // 自动检测 Ollama：已知不支持 tools 的模型
        String model = chatModel.getModelName().toLowerCase();
        return !model.startsWith("gemma3n");
    }
}
