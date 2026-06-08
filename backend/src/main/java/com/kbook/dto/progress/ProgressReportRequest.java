package com.kbook.dto.progress;

import lombok.Data;

/**
 * 阅读进度上报请求
 * 用于用户上报单本图书的阅读进度
 */
@Data
public class ProgressReportRequest {
    /** 图书ID */
    private Long bookId;
    
    /** 阅读进度（0-100） */
    private Double progress;
    
    /** 当前位置标识 */
    private String currentPosition;
}
