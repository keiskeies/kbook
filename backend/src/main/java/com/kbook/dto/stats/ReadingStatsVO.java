package com.kbook.dto.stats;

import lombok.*;

/**
 * 阅读统计视图对象
 * 用于展示用户的阅读统计数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReadingStatsVO {
    /** 总图书数 */
    private long totalBooks;
    
    /** 已读完的图书数 */
    private long completedBooks;
    
    /** 正在阅读的图书数 */
    private long readingBooks;

    /**
     * 从 ReadingStats 转换
     */
    public static ReadingStatsVO from(ReadingStats stats) {
        return ReadingStatsVO.builder()
                .totalBooks(stats.getTotalBooks())
                .completedBooks(stats.getCompletedBooks())
                .readingBooks(stats.getReadingBooks())
                .build();
    }
}
