package com.kbook.dto.response;

import com.kbook.entity.Conversation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话视图对象（response版本）
 * 用于展示会话列表中的会话信息，包含对方用户和未读消息数
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationVO {
    /** 会话ID */
    private Long id;
    /** 对方用户ID */
    private Long otherUserId;
    /** 对方用户昵称 */
    private String otherUserNickname;
    /** 对方用户头像URL */
    private String otherUserAvatar;
    /** 最后一条消息内容 */
    private String lastMessage;
    /** 未读消息数 */
    private Integer unreadCount;
    /** 更新时间 */
    private LocalDateTime updatedAt;

    /**
     * 从会话实体构建视图对象
     * @param conversation 会话实体
     * @param currentUserId 当前登录用户ID
     * @param otherUserNickname 对方用户昵称
     * @param otherUserAvatar 对方用户头像URL
     * @return 会话视图对象
     */
    public static ConversationVO fromEntity(Conversation conversation, Long currentUserId, String otherUserNickname, String otherUserAvatar) {
        // 根据当前用户ID判断对方用户
        Long otherUserId = conversation.getUser1Id().equals(currentUserId) ? conversation.getUser2Id() : conversation.getUser1Id();
        // 根据当前用户ID获取对应的未读消息数
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