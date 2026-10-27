package com.kbook.entity;

import com.kbook.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

/**
 * AI 对话记录实体
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_conversations", indexes = {
        @Index(name = "idx_conv_user_id", columnList = "user_id"),
        @Index(name = "idx_conv_session_id", columnList = "session_id"),
        @Index(name = "idx_conv_type", columnList = "type"),
        @Index(name = "idx_conv_book_id", columnList = "book_id")
})
public class AiConversation extends BaseEntity {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 用户 ID
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 会话 ID（一次多轮对话共享）
     */
    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;

    /** 对话类型：CHAT(通用对话) / BOOK(图书问答) / LIBRARIAN(图书管理员) */
    @Column(length = 20)
    private String type;

    /** 关联图书 ID（图书问答时非空） */
    @Column(name = "book_id")
    private Long bookId;

    /**
     * 角色：user / assistant / system / tool
     */
    @Column(nullable = false, length = 20)
    private String role;

    /**
     * 消息内容（原始）
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * 压缩后的消息内容（初始等于 content；当上下文超限时 AI 回复被压缩后写入此字段）
     * buildChatMessages 始终读取此字段而非 content
     */
    @Column(name = "compressed_content", columnDefinition = "TEXT")
    private String compressedContent;

    /** 思考过程内容（支持思维链的模型返回） */
    @Column(name = "thinking_content", columnDefinition = "TEXT")
    private String thinkingContent;

    /** AI 推荐的后续追问（JSON 数组） */
    @Column(name = "follow_up_questions", columnDefinition = "TEXT")
    private String followUpQuestions;

    /**
     * 回答的结构化条目清单（JSON）。供后续"逐条展开"追问做定向检索与串行生成的任务骨架。
     * <p>服务端内部状态：前端按键取值、不读此字段即无害；不参与历史压缩。
     * schema：{@code {"topic":"...","items":[{"id":1,"title":"...","gist":"...",
     * "keywords":["..."],"chunkAnchors":[12,13]}]}}
     */
    @Column(name = "answer_outline", columnDefinition = "TEXT")
    private String answerOutline;

    /** 最近一次流式结束原因 STOP/LENGTH/…（诊断依据） */
    @Column(name = "finish_reason", length = 20)
    private String finishReason;

    /**
     * 引用校验结果（JSON）：{"valid":[1,2],"invalid":0,"included":3}。
     * 服务端内部状态，前端可按需读取。
     */
    @Column(name = "citation_json", columnDefinition = "TEXT")
    private String citationJson;

    /**
     * 工具调用 ID（当 role=tool 时记录）
     */
    @Column(name = "tool_call_id", length = 100)
    private String toolCallId;

    /**
     * 工具名称（当 role=tool 时记录）
     */
    @Column(name = "tool_name", length = 100)
    private String toolName;

    /**
     * Token 消耗
     */
    @Column(name = "token_count")
    private Integer tokenCount;

    @Override
    public Long getId() {
        return id;
    }
}
