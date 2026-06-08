package com.kbook.dto.comment;

import lombok.Data;

/**
 * 评论视图对象
 * 用于展示评论列表，包含评论信息及关联的用户、图书信息
 */
@Data
public class CommentVO {
    /** 评论ID */
    private Long id;
    
    /** 评论者用户ID */
    private Long userId;
    
    /** 图书ID */
    private Long bookId;
    
    /** 章节ID（可选） */
    private String chapterId;
    
    /** 父评论ID（回复评论时有值） */
    private Long parentId;
    
    /** 评论内容 */
    private String content;
    
    /** 点赞数 */
    private Integer likeCount;
    
    /** 回复数 */
    private Integer replyCount;
    
    /** 收藏数 */
    private Integer favoriteCount;
    
    /** 当前用户是否已点赞 */
    private Boolean liked;
    
    /** 当前用户是否已收藏 */
    private Boolean favorited;
    
    /** 创建时间 */
    private String createdAt;
    
    /** 评论者昵称 */
    private String userNickname;
    
    /** 评论者头像URL */
    private String userAvatar;
    
    /** 图书标题 */
    private String bookTitle;
    
    /** 图书封面URL */
    private String bookCoverUrl;
}
