package com.kbook.entity;

import com.kbook.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * AI 调用用量记录 — 每次 LLM 请求落一行，供管理后台成本监控聚合。
 * <p>
 * 与 {@link AiConversation#getTokenCount()} 的区别：本表记录的是实际 API 调用，
 * 包含未产生对话行的内部调用（查询扩展、压缩、元数据推断、圆桌/辩论发言等），
 * 因此成本口径更完整。
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_usage_log", indexes = {
        @Index(name = "idx_usage_created_at", columnList = "created_at"),
        @Index(name = "idx_usage_scene", columnList = "scene"),
        @Index(name = "idx_usage_user_id", columnList = "user_id"),
        @Index(name = "idx_usage_book_id", columnList = "book_id")
})
public class AiUsageLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** AI 场景（AiScene 枚举名，含工具类场景） */
    @Column(nullable = false, length = 50)
    private String scene;

    /** 模型名 */
    @Column(name = "model_name", length = 100)
    private String modelName;

    /** 配置名 */
    @Column(name = "config_name", length = 100)
    private String configName;

    /** 输入 token */
    @Column(name = "input_tokens", nullable = false)
    private Integer inputTokens;

    /** 输出 token */
    @Column(name = "output_tokens", nullable = false)
    private Integer outputTokens;

    /** 总 token */
    @Column(name = "total_tokens", nullable = false)
    private Integer totalTokens;

    /** 调用方式：STREAM / CHAT */
    @Column(name = "call_type", length = 20)
    private String callType;

    /** 发起用户（内部/定时任务调用时为空） */
    @Column(name = "user_id")
    private Long userId;

    /** 关联图书（图书问答/辩论/圆桌） */
    @Column(name = "book_id")
    private Long bookId;

    /** 关联会话 ID */
    @Column(name = "session_id", length = 100)
    private String sessionId;

    /** 调用耗时（毫秒） */
    @Column(name = "latency_ms")
    private Integer latencyMs;

    /** 是否成功（失败也记录，用于成功率统计） */
    @Column(nullable = false)
    @Builder.Default
    private Boolean success = true;

    /** 模型结束原因（STOP / LENGTH / ...） */
    @Column(name = "finish_reason", length = 20)
    private String finishReason;

    /** 失败类型（异常类名） */
    @Column(name = "error_type", length = 100)
    private String errorType;

    /** 调用时的输入单价快照（元 / 百万 token） */
    @Column(name = "input_price")
    private Double inputPrice;

    /** 调用时的输出单价快照（元 / 百万 token） */
    @Column(name = "output_price")
    private Double outputPrice;

    /** 本次调用费用（元，按单价快照计算） */
    @Column(nullable = false)
    @Builder.Default
    private Double cost = 0D;

    @Override
    public Long getId() {
        return id;
    }
}
