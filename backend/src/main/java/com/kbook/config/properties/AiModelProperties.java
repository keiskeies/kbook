package com.kbook.config.properties;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 模型配置属性 — 仅保留视觉模型配置（PDF OCR 专用），
 * 聊天模型和嵌入模型现已全部从数据库读取。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "kbook.ai")
public class AiModelProperties {

    /** AI 视觉模型配置（PDF OCR 专用） */
    private VisionConfig vision = new VisionConfig();

    @Data
    public static class VisionConfig {
        /** PDF OCR 视觉模型 provider（OLLAMA / OPENAI），默认 OLLAMA */
        private String provider = "OLLAMA";
        /** PDF OCR 视觉模型服务地址，默认本地 Ollama */
        private String baseUrl = "http://localhost:11434";
        /** PDF OCR 视觉模型名称（需支持图片输入，如 Unlimited-OCR:Q4_K_M、llava、minicpm-v 等） */
        private String modelName = "Unlimited-OCR:Q4_K_M";
        /** PDF OCR 视觉模型超时（OCR 处理图片更慢，需要更长超时） */
        private Duration timeout = Duration.ofSeconds(600);
    }

    /** 修复中文乱码：强制声明 Content-Type 含 UTF-8 字符集 */
    public static final java.util.Map<String, String> UTF8_HEADERS = java.util.Map.of(
            "Content-Type", "application/json;charset=utf-8"
    );

    @PostConstruct
    public void logConfig() {
        log.info("[AiModelProperties] 绑定结果: vision={}, provider={}, baseUrl={}, modelName={}, timeout={}",
                vision != null ? "非null" : "null",
                vision != null ? vision.getProvider() : "N/A",
                vision != null ? vision.getBaseUrl() : "N/A",
                vision != null ? vision.getModelName() : "N/A",
                vision != null ? vision.getTimeout() : "N/A");
    }
}
