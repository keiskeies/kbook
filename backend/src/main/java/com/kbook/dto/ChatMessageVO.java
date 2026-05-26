package com.kbook.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 聊天消息视图对象（dto根目录版本）
 * 用于展示聊天消息的基本信息，包含发送者信息和消息内容
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
    /** 发送者昵称 */
    private String senderNickname;
    /** 发送者头像URL */
    private String senderAvatar;
    /** 消息类型 */
    private String type;
    /** 消息内容 */
    private String content;
    /** 媒体文件URL */
    private String mediaUrl;
    /** 媒体文件大小（字节） */
    private Long mediaSize;
    /** 媒体文件名 */
    private String mediaName;
    /** 是否已读 */
    private Boolean isRead;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 是否为当前用户发送的消息 */
    private Boolean isMine;
}