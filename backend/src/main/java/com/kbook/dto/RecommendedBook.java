package com.kbook.dto;

import com.kbook.entity.Book;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendedBook {
    private Long id;
    private String title;
    private String author;
    private String coverUrl;
    private String format;
    private Double rating;
    private String description;
    private Double matchScore;
    private Long readCount;

    public static RecommendedBook from(Book book, double matchScore) {
        return RecommendedBook.builder()
                .id(book.getId())
                .title(book.getTitle())
                .author(book.getAuthor())
                .coverUrl(book.getCoverUrl())
                .format(book.getFormat())
                .rating(book.getRating())
                .readCount(book.getReadCount())
                .description(book.getDescription() != null && book.getDescription().length() > 80
                        ? book.getDescription().substring(0, 80) + "..." : book.getDescription())
                .matchScore(Math.round(matchScore * 100.0) / 100.0)
                .build();
    }

    public static RecommendedBook fromRecommendItem(RecommendedItem item) {
        return RecommendedBook.builder()
                .id(item.getBookId())
                .title(item.getTitle())
                .author(item.getAuthor())
                .coverUrl(item.getCoverUrl())
                .format(item.getFormat())
                .rating(item.getRating())
                .description(item.getDescription())
                .matchScore(item.getMatchScore())
                .readCount(item.getReadCount())
                .build();
    }
}
