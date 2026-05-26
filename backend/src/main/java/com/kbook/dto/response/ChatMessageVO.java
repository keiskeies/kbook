package com.kbook.dto.response;

import com.kbook.entity.ChatMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 聊天消息视图对象（response版本）
 * 用于展示聊天消息的完整信息，包含文件和语音等媒体数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageVO {
    /** 消息ID */
    private Long id;
    /** 会话ID */
    private Long conversationId;
    /** 发送者用户ID */
    private Long senderId;
    /** 接收者用户ID */
    private Long recipientId;
    /** 消息类型 */
    private String messageType;
    /** 消息内容 */
    private String content;
    /** 文件名 */
    private String fileName;
    /** 文件大小（字节） */
    private Long fileSize;
    /** 文件访问URL */
    private String fileUrl;
    /** 语音时长（秒） */
    private Integer voiceDuration;
    /** 是否已读 */
    private Boolean read;
    /** 创建时间 */
    private LocalDateTime createdAt;

    /**
     * 从聊天消息实体构建视图对象
     * @param message 聊天消息实体
     * @return 聊天消息视图对象
     */
    public static ChatMessageVO fromEntity(ChatMessage message) {
        return ChatMessageVO.builder()
                .id(message.getId())
                .conversationId(message.getConversationId())
                .senderId(message.getSenderId())
                .recipientId(message.getRecipientId())
                .messageType(message.getMessageType().name())
                .content(message.getContent())
                .fileName(message.getFileName())
                .fileSize(message.getFileSize())
                .fileUrl(message.getFileUrl())
                .voiceDuration(message.getVoiceDuration())
                .read(message.getRead())
                .createdAt(message.getCreatedAt())
                .build();
    }
}