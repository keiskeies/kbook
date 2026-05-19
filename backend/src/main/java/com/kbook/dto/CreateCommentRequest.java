package com.kbook.dto;

import lombok.Data;

/**
 * 创建评论请求
 * 用于用户发表新评论或回复评论
 */
@Data
public class CreateCommentRequest {
    /** 图书ID */
    private Long bookId;
    
    /** 章节ID（可选，针对特定章节评论） */
    private String chapterId;
    
    /** 父评论ID（回复评论时填写） */
    private Long parentId;
    
    /** 评论内容 */
    private String content;
}
