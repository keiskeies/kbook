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
public class ConversationVO {

    private Long id;
    private Long otherUserId;
    private String otherUserNickname;
    private String otherUserAvatar;
    private String lastMessageContent;
    private String lastMessageType;
    private LocalDateTime lastMessageTime;
    private Integer unreadCount;
    private Boolean otherUserIsFollowingMe;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}