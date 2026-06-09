package com.kbook.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 圆桌派会话实体 — 管理多角色 AI 讨论的会话
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "round_table_sessions", indexes = {
        @Index(name = "idx_rt_session_user_id", columnList = "user_id"),
        @Index(name = "idx_rt_session_book_id", columnList = "book_id"),
        @Index(name = "idx_rt_session_session_id", columnList = "session_id")
})
public class RoundTableSession extends BaseEntity {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 书籍 ID */
    @Column(name = "book_id", nullable = false)
    private Long bookId;

    /** 会话唯一标识（UUID，如 rt-123-uuid） */
    @Column(name = "session_id", nullable = false, length = 100, unique = true)
    private String sessionId;

    /** 会话标题（如"《书名》圆桌派讨论"） */
    @Column(length = 200)
    private String title;

    /** 参与角色键名列表（逗号分隔，如 HOST,PHILOSOPHER,PSYCHOLOGIST） */
    @Column(name = "role_keys", length = 500)
    private String roleKeys;

    /** 角色配置 JSON（包含 LLM 赋值的 domainRelevance 等动态参数） */
    @Column(name = "role_configs", columnDefinition = "TEXT")
    private String roleConfigs;

    /** 会话状态：ACTIVE / COMPLETED / ABANDONED */
    @Column(length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Override
    public Long getId() {
        return id;
    }
}
