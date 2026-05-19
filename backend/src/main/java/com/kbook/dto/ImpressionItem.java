package com.kbook.dto;

import lombok.Data;

/**
 * 曝光项
 * 记录推荐给用户的图书及召回路径，用于后续反馈追踪
 */
@Data
public class ImpressionItem {
    /** 图书ID */
    private Long bookId;
    
    /** 召回路径（记录推荐来源，如：向量搜索、协同过滤等） */
    private String recallPaths;
}
