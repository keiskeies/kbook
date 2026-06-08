package com.kbook.dto.book;

import com.kbook.entity.Book;
import lombok.*;

/**
 * 简易图书视图对象
 * 用于展示图书的基本信息，不包含详细描述等大字段
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimpleBookVO {
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
    /** 阅读次数 */
    private Long readCount;

    /**
     * 从图书实体构建简易图书视图对象
     * @param book 图书实体
     * @return 简易图书视图对象
     */
    public static SimpleBookVO from(Book book) {
        return SimpleBookVO.builder()
                .id(book.getId())
                .title(book.getTitle())
                .author(book.getAuthor())
                .coverUrl(book.getCoverUrl())
                .format(book.getFormat())
                .rating(book.getRating())
                .readCount(book.getReadCount())
                .build();
    }

    public static SimpleBookVO from(BookProjection book) {
        return SimpleBookVO.builder()
                .id(book.getId())
                .title(book.getTitle())
                .author(book.getAuthor())
                .coverUrl(book.getCoverUrl())
                .format(book.getFormat())
                .rating(book.getRating())
                .readCount(book.getReadCount())
                .build();
    }
}
