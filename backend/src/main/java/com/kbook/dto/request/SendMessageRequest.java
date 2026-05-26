package com.kbook.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发送消息请求
 * 用于用户在会话中发送各类消息（文本、文件、语音等）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendMessageRequest {
    /** 接收者用户ID */
    private Long recipientId;
    /** 消息内容 */
    private String content;
    /** 消息类型：TEXT/FILE/VOICE等 */
    private String messageType;
    /** 文件名（文件消息时使用） */
    private String fileName;
    /** 文件大小（字节，文件消息时使用） */
    private Long fileSize;
    /** 文件访问URL（文件消息时使用） */
    private String fileUrl;
    /** 语音时长（秒，语音消息时使用） */
    private Integer voiceDuration;
}