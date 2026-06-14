package com.kbook.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 模型配置属性 — 仅保留视觉模型配置（PDF OCR 用），
 * 聊天模型和嵌入模型现已全部从数据库读取。
 */
@Data
@Component
@ConfigurationProperties(prefix = "kbook.ai")
public class AiModelProperties {

    /** AI 视觉模型配置（PDF OCR 用） */
    private VisionConfig vision = new VisionConfig();

    @Data
    public static class VisionConfig {
        /** PDF OCR 视觉模型名称（需支持图片输入，如 llava、gemma4:e4b、minicpm-v 等） */
        private String modelName = "";
        /** PDF OCR 视觉模型超时（OCR 处理图片更慢，需要更长超时） */
        private Duration timeout = Duration.ofSeconds(600);
    }

    /** 修复中文乱码：强制声明 Content-Type 含 UTF-8 字符集 */
    public static final java.util.Map<String, String> UTF8_HEADERS = java.util.Map.of(
            "Content-Type", "application/json;charset=utf-8"
    );
}
