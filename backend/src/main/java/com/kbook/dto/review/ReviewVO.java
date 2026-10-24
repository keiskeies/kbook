package com.kbook.dto.review;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 书评视图对象 — 列表与详情共用，自带书籍与作者信息，前端无需二次请求
 */
@Data
@Builder
public class ReviewVO {

    private Long id;
    private Long bookId;
    private String bookTitle;
    private String bookAuthor;
    private String bookCoverUrl;

    private Long userId;
    private String nickname;
    private String avatar;

    private Integer rating;
    private String title;
    private String contentMd;
    private Boolean isSpoiler;
    private Integer captureCount;

    /** 写作时的阅读进度百分比 */
    private Integer progressPercent;
    private String chapterId;

    /** 当前登录用户是否为作者 */
    private Boolean mine;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
