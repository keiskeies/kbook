package com.kbook.dto;

import lombok.Data;

import java.util.List;

/**
 * 曝光记录请求
 * 用于记录向用户展示了哪些推荐图书，便于后续分析推荐效果
 */
@Data
public class ImpressionRequest {
    /** 曝光项列表 */
    private List<ImpressionItem> items;
}
