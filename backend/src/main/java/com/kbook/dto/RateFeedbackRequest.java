package com.kbook.dto;

import lombok.Data;

/**
 * 评分反馈请求
 * 用户对推荐图书进行评分反馈，用于优化推荐算法
 */
@Data
public class RateFeedbackRequest {
    /** 图书ID */
    private Long bookId;
    
    /** 评分（1-5分） */
    private Integer rating;
    
    /** 召回路径（记录推荐来源） */
    private String recallPaths;
}
