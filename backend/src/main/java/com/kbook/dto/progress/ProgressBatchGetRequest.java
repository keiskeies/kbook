package com.kbook.dto.progress;

import lombok.Data;

import java.util.List;

/**
 * 批量获取阅读进度请求
 * 用于一次性获取多本图书的阅读进度
 */
@Data
public class ProgressBatchGetRequest {
    /** 图书ID列表 */
    private List<Long> bookIds;
}
