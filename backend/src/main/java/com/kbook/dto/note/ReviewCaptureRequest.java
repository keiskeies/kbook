package com.kbook.dto.note;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 把书评收录为笔记
 */
@Data
public class ReviewCaptureRequest {

    /** FULL 展示全文 / EXCERPT 只留截取片段 / REFERENCE 仅引用卡片 */
    @NotNull(message = "请选择收录方式")
    private String mode;

    /** EXCERPT 模式下必填：我截取保留的片段 */
    @Size(max = 20000, message = "摘录内容过长")
    private String excerpt;

    /** 我为什么收这条 — 成长总结的主料，强烈建议填写 */
    @Size(max = 20000, message = "批注内容不能超过 20000 字")
    private String myThought;
}
