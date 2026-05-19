package com.kbook.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 阅读统计数据
 * 包含用户的总体阅读统计信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReadingStats {
    /** 总图书数 */
    private long totalBooks;
    
    /** 已读完的图书数 */
    private long completedBooks;
    
    /** 正在阅读的图书数 */
    private long readingBooks;
}
