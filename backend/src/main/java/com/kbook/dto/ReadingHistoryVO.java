package com.kbook.dto;

import com.kbook.entity.Book;
import com.kbook.entity.ReadingProgress;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReadingHistoryVO {

    private Long progressId;
    private Long bookId;
    private Double progress;
    private String currentPosition;
    private String updatedAt;

    private String title;
    private String author;
    private String coverUrl;
    private String format;
    private Long fileSize;
    private Double rating;
    private Long readCount;

    public static ReadingHistoryVO from(ReadingProgress rp, Book book) {
        ReadingHistoryVO vo = ReadingHistoryVO.builder()
                .progressId(rp.getId())
                .bookId(rp.getBookId())
                .progress(rp.getProgress())
                .currentPosition(rp.getCurrentPosition())
                .updatedAt(rp.getUpdatedAt() != null ? rp.getUpdatedAt().toString() : null)
                .build();
        if (book != null) {
            vo.setTitle(book.getTitle());
            vo.setAuthor(book.getAuthor());
            vo.setCoverUrl(book.getCoverUrl());
            vo.setFormat(book.getFormat());
            vo.setFileSize(book.getFileSize());
            vo.setRating(book.getRating());
            vo.setReadCount(book.getReadCount());
        }
        return vo;
    }
}
