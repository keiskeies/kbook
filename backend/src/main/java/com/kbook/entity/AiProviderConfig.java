package com.kbook.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 供应商配置实体 — 支持管理员在后台动态配置不同用途的 AI 模型
 * <p>
 * purpose 字段区分不同用途：
 * - CHAT: 对话类（AI图书问答、AI阅读助手、AI图书管理员），未配置时回退到 yml 默认模型
 * - TAG: 标签/评分/相关度生成（保留扩展用，当前仍用 yml 配置）
 * - EMBEDDING: 向量模型（保留扩展用）
 * - VISION: OCR 视觉模型（保留扩展用）
 * <p>
 * provider 字段区分供应商类型：
 * - OLLAMA: 本地/远程 Ollama 服务
 * - OPENAI: OpenAI 兼容 API（包括 DeepSeek、通义千问等兼容接口）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_provider_config", indexes = {
        @Index(name = "idx_purpose_enabled", columnList = "purpose, enabled")
})
public class AiProviderConfig {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 配置名称（用于多配置时区分显示，如 "DeepSeek-V4"、"Qwen3-Max"） */
    @Column(nullable = false, length = 50)
    private String name;

    /** 配置用途：CHAT=对话, TAG=标签评分, EMBEDDING=向量, VISION=OCR */
    @Column(nullable = false, length = 20)
    private String purpose;

    /** 供应商类型：OLLAMA 或 OPENAI */
    @Column(nullable = false, length = 20)
    private Provider provider;

    /** API 基础地址（如 <a href="http://localhost:11434">...</a> 或 <a href="https://api.deepseek.com">...</a>） */
    @Column(nullable = false, length = 500)
    private String baseUrl;

    /** 模型名称（如 qwen3:8b, deepseek-chat） */
    @Column(nullable = false, length = 100)
    private String modelName;

    /** API Key（OpenAI 兼容接口需要，Ollama 可为空） */
    @Column(length = 500)
    private String apiKey;

    /** 温度参数（0.0-2.0，默认 0.7） */
    @Builder.Default
    private Double temperature = 0.7;

    /** 超时时间（秒，默认 120） */
    @Builder.Default
    private Integer timeout = 120;

    /** 是否支持 Tool Calling（null=自动检测） */
    private Boolean toolsEnabled;

    /** 是否启用 */
    @Builder.Default
    private Boolean enabled = true;

    /** 是否为当前 purpose 的默认/激活配置（同 purpose 下仅一条可为 true） */
    @Builder.Default
    private Boolean isDefault = false;

    /** RAG 检索返回的最大片段数（top-k），为空则使用全局默认值 */
    private Integer ragTopK;

    /** 模型上下文长度（token 数），用于计算历史压缩阈值。为空则默认 32768 (32K) */
    private Integer maxTokens;

    /** 创建时间 */
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** JPA 持久化前回调，自动设置创建和更新时间 */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /** JPA 更新前回调，自动设置更新时间 */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** 配置用途枚举 */
    public enum Purpose {
        CHAT, TAG, EMBEDDING, VISION
    }

    /** 供应商类型枚举 */
    public enum Provider {
        OLLAMA, OPENAI;

        @JsonCreator
        public static Provider from(String value) {
            if (value == null) return null;
            return valueOf(value.toUpperCase());
        }
    }

    /** JPA 枚举转换器 — 大小写不敏感 */
    @Converter(autoApply = true)
    public static class ProviderConverter implements AttributeConverter<Provider, String> {
        @Override
        public String convertToDatabaseColumn(Provider attribute) {
            return attribute == null ? null : attribute.name();
        }

        @Override
        public Provider convertToEntityAttribute(String dbData) {
            return dbData == null ? null : Provider.valueOf(dbData.toUpperCase());
        }
    }
}
