package com.kbook.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 渐进式语音播放凭据申请
 */
@Data
public class TtsStreamPrepareRequest {

    /** 待朗读文本（通常是经过前端清洗的纯文本） */
    @NotBlank(message = "文本不能为空")
    @Size(max = 50_000, message = "单次朗读文本不能超过 50000 字符")
    private String text;

    /** TTS 配置ID，为空时使用当前默认配置 */
    @Positive(message = "configId 必须为正数")
    private Long configId;
}