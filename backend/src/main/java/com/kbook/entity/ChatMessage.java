package com.kbook.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 聊天消息实体
 * 用户间私信消息，支持文本、图片、语音和文件类型
 */
@Entity
@Table(name = "chat_message", indexes = {
        @Index(name = "idx_message_conversation", columnList = "conversation_id"),
        @Index(name = "idx_message_sender", columnList = "sender_id"),
        @Index(name = "idx_message_created", columnList = "created_at")
})
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage extends BaseEntity {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属会话 ID */
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** 发送者用户 ID */
    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    /** 接收者用户 ID */
    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    /** 消息类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 20)
    private MessageType messageType;

    /** 消息文本内容 */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** 附件文件名 */
    @Column(name = "file_name", length = 255)
    private String fileName;

    /** 附件文件大小（字节） */
    @Column(name = "file_size")
    private Long fileSize;

    /** 附件文件 URL */
    @Column(name = "file_url", length = 500)
    private String fileUrl;

    /** 语音时长（秒） */
    @Column(name = "voice_duration")
    private Integer voiceDuration;

    /** 是否已读 */
    @Column(name = "`read`", columnDefinition = "TINYINT(1) DEFAULT 0")
    @Builder.Default
    private Boolean read = false;

    /** 创建时间 */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 消息类型枚举 */
    public enum MessageType {
        /** 文本消息 */
        TEXT,
        /** 图片消息 */
        IMAGE,
        /** 语音消息 */
        VOICE,
        /** 文件消息 */
        FILE
    }

    @Override
    public Long getId() {
        return id;
    }
}