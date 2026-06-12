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
 * 用户间私信会话实体
 * 记录两个用户之间的会话信息，包括最后一条消息和未读计数
 */
@Entity
@Table(name = "conversation", indexes = {
        @Index(name = "idx_conversation_user1", columnList = "user1_id"),
        @Index(name = "idx_conversation_user2", columnList = "user2_id"),
        @Index(name = "idx_conversation_updated", columnList = "updated_at")
})
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Conversation extends BaseEntity {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户1 ID（ID 较小的用户） */
    @Column(name = "user1_id", nullable = false)
    private Long user1Id;

    /** 用户2 ID（ID 较大的用户） */
    @Column(name = "user2_id", nullable = false)
    private Long user2Id;

    /** 最后一条消息内容摘要 */
    @Column(name = "last_message", length = 500)
    private String lastMessage;

    /** 用户1的未读消息数 */
    @Column(name = "unread_count_user1", columnDefinition = "INT DEFAULT 0")
    @Builder.Default
    private Integer unreadCountUser1 = 0;

    /** 用户2的未读消息数 */
    @Column(name = "unread_count_user2", columnDefinition = "INT DEFAULT 0")
    @Builder.Default
    private Integer unreadCountUser2 = 0;

    /** 用户1是否已删除该会话 */
    @Column(name = "user1_deleted", columnDefinition = "TINYINT(1) DEFAULT 0")
    @Builder.Default
    private Boolean user1Deleted = false;

    /** 用户2是否已删除该会话 */
    @Column(name = "user2_deleted", columnDefinition = "TINYINT(1) DEFAULT 0")
    @Builder.Default
    private Boolean user2Deleted = false;

    @Override
    public Long getId() {
        return id;
    }
}
