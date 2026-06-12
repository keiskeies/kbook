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
 * 圆桌派消息实体 — 存储每个角色的发言记录
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "round_table_messages", indexes = {
        @Index(name = "idx_rt_msg_session_id", columnList = "session_id"),
        @Index(name = "idx_rt_msg_user_id", columnList = "user_id"),
        @Index(name = "idx_rt_msg_role_key", columnList = "role_key")
})
public class RoundTableMessage extends BaseEntity {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 会话 ID */
    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;

    /** 书籍 ID */
    @Column(name = "book_id", nullable = false)
    private Long bookId;

    /** 发言角色键名（如 HOST、PHILOSOPHER） */
    @Column(name = "role_key", nullable = false, length = 30)
    private String roleKey;

    /** 发言角色中文名（如 主持人、哲学家） */
    @Column(name = "role_name", nullable = false, length = 30)
    private String roleName;

    /** 原始发言内容 */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 压缩后的发言内容（与 AiConversation 同一压缩模式） */
    @Column(name = "compressed_content", columnDefinition = "TEXT")
    private String compressedContent;

    /** 讨论轮次（第几轮发言） */
    @Column(name = "round")
    private Integer round;

    @Override
    public Long getId() {
        return id;
    }
}
