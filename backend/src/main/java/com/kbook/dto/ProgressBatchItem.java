package com.kbook.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批量阅读进度项
 * 用于批量上报或获取单本图书的阅读进度
 */
@Data
public class ProgressBatchItem {
    /** 图书ID */
    private Long bookId;
    
    /** 阅读进度（0-100） */
    private Double progress;
    
    /** 当前位置标识 */
    private String currentPosition;
    
    /** 客户端时间戳 */
    private LocalDateTime clientTimestamp;
}
