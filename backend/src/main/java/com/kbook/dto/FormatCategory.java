package com.kbook.dto;

import lombok.*;

/**
 * 格式分类统计
 * 用于展示不同格式图书的分类信息（EPUB/PDF/TXT）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormatCategory {
    /** 格式类型：EPUB/PDF/TXT */
    private String format;
    
    /** 显示标签 */
    private String label;
    
    /** 图标标识 */
    private String icon;
    
    /** 该格式的图书数量 */
    private Long count;
}
