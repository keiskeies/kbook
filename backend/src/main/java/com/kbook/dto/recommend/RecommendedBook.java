package com.kbook.dto.recommend;

import com.kbook.entity.Book;
import lombok.*;

/**
 * 推荐图书视图对象
 * 用于展示推荐给用户的图书信息，包含匹配度评分
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendedBook {
    /** 图书ID */
    private Long id;
    /** 书名 */
    private String title;
    /** 作者 */
    private String author;
    /** 封面URL */
    private String coverUrl;
    /** 图书格式：EPUB/PDF/TXT */
    private String format;
    /** 评分 */
    private Double rating;
    /** 图书简介 */
    private String description;
    /** 匹配度评分（0-1） */
    private Double matchScore;
    /** 格式标签（JSON数组字符串） */
    private String formatTags;
    /** 阅读次数 */
    private Long readCount;

    /**
     * 从图书实体构建推荐图书视图对象
     * @param book 图书实体
     * @param matchScore 匹配度评分
     * @return 推荐图书视图对象
     */
    public static RecommendedBook from(Book book, double matchScore) {
        return RecommendedBook.builder()
                .id(book.getId())
                .title(book.getTitle())
                .author(book.getAuthor())
                .coverUrl(book.getCoverUrl())
                .format(book.getFormat())
                .rating(book.getRating())
                .formatTags(book.getFormatTags())
                .readCount(book.getReadCount())
                .description(book.getDescription())
                // 匹配度保留两位小数
                .matchScore(Math.round(matchScore * 100.0) / 100.0)
                .build();
    }

    /**
     * 从推荐项构建推荐图书视图对象
     * @param item 推荐项数据
     * @return 推荐图书视图对象
     */
    public static RecommendedBook fromRecommendItem(RecommendedItem item) {
        return RecommendedBook.builder()
                .id(item.getBookId())
                .title(item.getTitle())
                .author(item.getAuthor())
                .coverUrl(item.getCoverUrl())
                .format(item.getFormat())
                .rating(item.getRating())
                .description(item.getDescription())
                .formatTags(item.getFormatTags())
                .matchScore(item.getMatchScore())
                .readCount(item.getReadCount())
                .build();
    }
}
