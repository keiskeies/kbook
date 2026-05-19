package com.kbook.dto;

import lombok.Data;

/**
 * 通知视图对象
 * 用于展示用户收到的各种通知（评论回复、点赞等）
 */
@Data
public class NotificationVO {
    /** 通知ID */
    private Long id;
    
    /** 触发通知的用户ID */
    private Long triggerUserId;
    
    /** 触发通知的用户昵称 */
    private String triggerUserNickname;
    
    /** 触发通知的用户头像 */
    private String triggerUserAvatar;
    
    /** 通知类型：COMMENT_REPLY-评论回复, LIKE-点赞等 */
    private String type;
    
    /** 关联的评论ID */
    private Long commentId;
    
    /** 关联的图书ID */
    private Long bookId;
    
    /** 是否已读 */
    private Boolean isRead;
    
    /** 创建时间 */
    private String createdAt;
}
