package com.kbook.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话视图对象（dto根目录版本）
 * 用于展示会话列表中的会话信息，包含对方用户信息和最后一条消息
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
    private String lastMessageContent;
    /** 最后一条消息类型 */
    private String lastMessageType;
    /** 最后一条消息时间 */
    private LocalDateTime lastMessageTime;
    /** 未读消息数 */
    private Integer unreadCount;
    /** 对方用户是否关注了当前用户 */
    private Boolean otherUserIsFollowingMe;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新时间 */
    private LocalDateTime updatedAt;
}