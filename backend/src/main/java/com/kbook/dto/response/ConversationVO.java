package com.kbook.dto.response;

import com.kbook.entity.Conversation;
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

    private String lastMessage;

    private Integer unreadCount;

    private LocalDateTime updatedAt;

    public static ConversationVO fromEntity(Conversation conversation, Long currentUserId, String otherUserNickname, String otherUserAvatar) {
        Long otherUserId = conversation.getUser1Id().equals(currentUserId) ? conversation.getUser2Id() : conversation.getUser1Id();
        Integer unreadCount = conversation.getUser1Id().equals(currentUserId) ? conversation.getUnreadCountUser1() : conversation.getUnreadCountUser2();

        return ConversationVO.builder()
                .id(conversation.getId())
                .otherUserId(otherUserId)
                .otherUserNickname(otherUserNickname)
                .otherUserAvatar(otherUserAvatar)
                .lastMessage(conversation.getLastMessage())
                .unreadCount(unreadCount)
                .updatedAt(conversation.getUpdatedAt())
                .build();
    }
}