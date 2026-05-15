package com.kbook.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 模型配置属性 — 统一管理所有 AI/LLM 相关配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "langchain4j.ollama")
public class AiModelProperties {

    private ChatModelConfig chatModel = new ChatModelConfig();
    private EmbeddingModelConfig embeddingModel = new EmbeddingModelConfig();

    /** AI 视觉模型配置（PDF OCR 用） */
    private VisionConfig vision = new VisionConfig();

    @Data
    public static class ChatModelConfig {
        private String baseUrl = "http://localhost:11434";
        private String modelName = "gemma3n:e4b";
        private double temperature = 0.7;
        private Duration timeout = Duration.ofSeconds(120);
        /** 是否启用 Tool Calling（部分模型如 gemma3n 不支持 tools）。null=自动检测 */
        private Boolean toolsEnabled;
    }

    @Data
    public static class EmbeddingModelConfig {
        private String baseUrl = "http://localhost:11434";
        private String modelName = "qwen3-embedding:0.6b";
        private Duration timeout = Duration.ofSeconds(300);
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
        // 自动检测：已知不支持 tools 的模型
        String model = chatModel.getModelName().toLowerCase();
        // gemma3n 全系列不支持 tools
        return !model.startsWith("gemma3n");
    }
}
