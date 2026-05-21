package com.kbook.dto.response;

import com.kbook.entity.ChatMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageVO {

    private Long id;

    private Long conversationId;

    private Long senderId;

    private Long recipientId;

    private String messageType;

    private String content;

    private String fileName;

    private Long fileSize;

    private String fileUrl;

    private Integer voiceDuration;

    private Boolean read;

    private LocalDateTime createdAt;

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