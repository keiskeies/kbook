package com.kbook.dto;

import lombok.Data;

/**
 * 反馈请求
 * 用于用户对推荐结果进行反馈（点赞/点踩）
 */
@Data
public class FeedbackRequest {
    /** 图书ID */
    private Long bookId;
    
    /** 召回路径（用于追踪推荐来源） */
    private String recallPaths;
}
