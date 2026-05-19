package com.kbook.dto;

import com.kbook.entity.Book;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimpleBookVO {
    private Long id;
    private String title;
    private String author;
    private String coverUrl;
    private String format;
    private Double rating;
    private Long readCount;

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
}
