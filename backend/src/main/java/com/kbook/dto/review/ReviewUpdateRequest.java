package com.kbook.dto.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑书评请求 — 仅允许改表达，不改写作时的阅读进度快照
 */
@Data
public class ReviewUpdateRequest {

    @Min(value = 1, message = "评分最低 1 分")
    @Max(value = 5, message = "评分最高 5 分")
    private Integer rating;

    @Size(max = 100, message = "标题不能超过 100 字")
    private String title;

    @NotBlank(message = "书评内容不能为空")
    @Size(max = 20000, message = "书评内容不能超过 20000 字")
    private String contentMd;

    private Boolean isSpoiler;
}
