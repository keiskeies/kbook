package com.kbook.dto.comment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建评论请求
 * 用于用户发表新评论或回复评论
 */
@Data
public class CreateCommentRequest {
    /** 图书ID */
    @NotNull(message = "图书ID不能为空")
    @Positive(message = "图书ID必须为正数")
    private Long bookId;

    /** 章节ID（可选，针对特定章节评论） */
    @Size(max = 100, message = "章节ID长度不能超过 100")
    private String chapterId;

    /** 父评论ID（回复评论时填写） */
    @Positive(message = "父评论ID必须为正数")
    private Long parentId;

    /** 评论内容 */
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 2000, message = "评论内容不能超过 2000 字符")
    private String content;
}
