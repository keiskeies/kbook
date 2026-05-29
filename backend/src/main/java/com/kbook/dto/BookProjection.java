package com.kbook.dto;

import com.kbook.entity.Book;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class BookProjection {
    private Long id;
    private String title;
    private String author;
    private String coverUrl;
    private String format;
    private Long fileSize;
    private String fileUrl;
    private String formatTags;
    private Double rating;
    private Long readCount;
    private Long totalUnits;
    private String description;
    private String relevanceScores;
    private LocalDateTime createdAt;

    public static BookProjection from(Book book) {
        return new BookProjection(
                book.getId(), book.getTitle(), book.getAuthor(),
                book.getCoverUrl(), book.getFormat(), book.getFileSize(),
                book.getFileUrl(), book.getFormatTags(), book.getRating(),
                book.getReadCount(), book.getTotalUnits(),
                book.getDescription(), book.getRelevanceScores(), book.getCreatedAt()
        );
    }
}
