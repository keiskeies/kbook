package com.kbook.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.kbook.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

/**
 * AI 供应商配置实体 — 支持管理员在后台动态配置不同用途的 AI 模型
 * <p>
 * purpose 字段区分不同用途：
 * - CHAT: 对话模型（AI图书问答、AI助理、圆桌派、奇葩说等），通过 roles 字段细分为 QA 和 TOOL 角色
 * - EMBEDDING: 嵌入模型（用于向量生成），需配置 embeddingDimension
 * - VISION: OCR 视觉模型
 * <p>
 * roles 字段（仅 CHAT 用途）：逗号分隔的角色枚举值
 * - QA: 大型问答（图书问答、AI助理、圆桌派、奇葩说）
 * - TOOL: 小型工具（元数据推断、内容压缩、查询扩展等后台任务）
 * <p>
 * provider 字段区分供应商类型：
 * - OLLAMA: 本地/远程 Ollama 服务
 * - OPENAI: OpenAI 兼容 API（包括 DeepSeek、通义千问等兼容接口）
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_provider_config", indexes = {
        @Index(name = "idx_purpose_enabled", columnList = "purpose, enabled"),
        @Index(name = "idx_purpose_roles", columnList = "purpose, roles")
})
public class AiProviderConfig extends BaseEntity {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 配置名称（用于多配置时区分显示，如 "DeepSeek-V4"、"Qwen3-Max"） */
    @Column(nullable = false, length = 100)
    private String name;

    /** 配置用途：CHAT=对话, EMBEDDING=向量, VISION=OCR */
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

    /** 超时时间（秒，默认 600） */
    @Builder.Default
    private Integer timeout = 600;

    /** 是否支持 Tool Calling（null=自动检测） */
    private Boolean toolsEnabled;

    /** 是否启用 */
    @Builder.Default
    private Boolean enabled = true;

    /** 对话模型角色：QA=大型问答, TOOL=小型工具。逗号分隔可多选（如 "QA,TOOL"）。仅 CHAT 用途有效 */
    @Column(length = 50)
    private String roles;

    /** 嵌入模型向量维度（如 1024）。仅 EMBEDDING 用途有效 */
    private Integer embeddingDimension;

    /** RAG 检索返回的最大片段数（top-k），为空则使用全局默认值 */
    private Integer ragTopK;

    /** 模型上下文长度（token 数），用于计算历史压缩阈值。为空则默认 32768 (32K) */
    private Integer maxTokens;

    /**
     * 思考模式 — 声明该模型支持的思考参数能力，决定场景配置时能选哪些选项。
     * <ul>
     *   <li>{@link ThinkingMode#NONE} 不支持思考参数（如 Gemini，发送任何思考参数都会 400）</li>
     *   <li>{@link ThinkingMode#SWITCH} 仅支持开/关（大多数 OpenAI 兼容模型）</li>
     *   <li>{@link ThinkingMode#REASONING_EFFORT} 支持 low/medium/high 强度调节</li>
     *   <li>{@link ThinkingMode#THINKING_BUDGET} 支持 token 预算（如 OpenAI o 系列）</li>
     * </ul>
     * 场景配置时根据此字段动态渲染表单（联动）。
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    private ThinkingMode thinkingMode = ThinkingMode.SWITCH;

    /**
     * 思考模式枚举 — 用于 AiProviderConfig.thinkingMode 字段
     */
    public enum ThinkingMode {
        /** 不支持思考参数（Gemini 等） */
        NONE,
        /** 仅支持开/关（think=true/false） */
        SWITCH,
        /** 支持 reasoning_effort: low/medium/high */
        REASONING_EFFORT,
        /** 支持 thinking_budget: token 数 */
        THINKING_BUDGET
    }

    /** 配置用途枚举 */
    public enum Purpose {
        CHAT, EMBEDDING, VISION
    }

    /** 对话模型角色枚举（仅 CHAT 用途） */
    public enum Role {
        QA, TOOL
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

    @Override
    public Long getId() {
        return id;
    }
}
