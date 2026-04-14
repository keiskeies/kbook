package com.kbook.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 模型提供商配置实体（全局配置，由管理员管理）
 * <p>
 * 支持 OpenAI 兼容 API 和 Ollama 两种提供商类型。
 * 同一提供商类型可有多个配置，但只有 enabled=true 的配置会被使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_provider_config", indexes = {
        @Index(name = "idx_provider_type", columnList = "provider")
})
public class AiProviderConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 提供商类型: OPENAI / OLLAMA */
    @Column(nullable = false, length = 20)
    private String provider;

    /** 配置名称（如 "DeepSeek-Chat"、"本地 Qwen2.5"） */
    @Column(name = "config_name", length = 100)
    private String configName;

    /** API 端点地址（OpenAI 兼容: base-url，Ollama: 端点地址） */
    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    /** API Key（OpenAI 兼容需要，Ollama 可留空） */
    @Column(name = "api_key", length = 500)
    private String apiKey;

    /** 模型名称 */
    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    /** 温度 (0~2) */
    @Column
    private Double temperature;

    /** 最大 Token 数 */
    @Column(name = "max_tokens")
    private Integer maxTokens;

    /** Thinking 等级: NONE / LOW / MEDIUM / HIGH — thinking 模型推理时间更长，需要更长超时 */
    @Column(name = "thinking_level", length = 10)
    @Builder.Default
    private String thinkingLevel = "NONE";

    /** 是否启用（全局只有启用的配置才会被使用） */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = false;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
