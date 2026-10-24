package com.kbook.dto.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 写书评请求
 */
@Data
public class ReviewCreateRequest {

    /** 评分 1-5，可空 */
    @Min(value = 1, message = "评分最低 1 分")
    @Max(value = 5, message = "评分最高 5 分")
    private Integer rating;

    /** 标题，可空 */
    @Size(max = 100, message = "标题不能超过 100 字")
    private String title;

    /** 正文 Markdown */
    @NotBlank(message = "书评内容不能为空")
    @Size(max = 20000, message = "书评内容不能超过 20000 字")
    private String contentMd;

    /** 是否剧透 */
    private Boolean isSpoiler;

    /** 写作时所在章节，可空；不传则由服务端取当前阅读位置 */
    @Size(max = 200, message = "章节标识过长")
    private String chapterId;
}
