package com.kbook.dto;

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
    private String senderNickname;
    private String senderAvatar;
    private String type;
    private String content;
    private String mediaUrl;
    private Long mediaSize;
    private String mediaName;
    private Boolean isRead;
    private LocalDateTime createdAt;
    private Boolean isMine;
}