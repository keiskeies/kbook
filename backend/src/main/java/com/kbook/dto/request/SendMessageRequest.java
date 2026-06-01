package com.kbook.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
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
    @NotNull(message = "接收者不能为空")
    @Positive(message = "接收者 ID 必须为正数")
    private Long recipientId;

    /** 消息内容 */
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 10_000, message = "消息内容不能超过 10000 字符")
    private String content;

    /** 消息类型：TEXT/FILE/VOICE等 */
    @NotBlank(message = "消息类型不能为空")
    @Size(max = 20, message = "消息类型长度不能超过 20")
    private String messageType;

    /** 文件名（文件消息时使用） */
    @Size(max = 255, message = "文件名长度不能超过 255")
    private String fileName;

    /** 文件大小（字节，文件消息时使用） */
    @Positive(message = "文件大小必须为正数")
    private Long fileSize;

    /** 文件访问URL（文件消息时使用） */
    @Size(max = 500, message = "文件 URL 长度不能超过 500")
    private String fileUrl;

    /** 语音时长（秒，语音消息时使用） */
    @Positive(message = "语音时长必须为正数")
    private Integer voiceDuration;
}