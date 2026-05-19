package com.kbook.dto;

import lombok.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecentBookVO {
    private Long bookId;
    private String title;
    private String author;
    private String coverUrl;
    private String format;
    private Double progress;
    private LocalDateTime lastReadAt;

    public static RecentBookVO from(BookshelfItem item) {
        return RecentBookVO.builder()
                .bookId(item.getBookId())
                .title(item.getTitle())
                .author(item.getAuthor())
                .coverUrl(item.getCoverUrl())
                .format(item.getFormat())
                .progress(item.getProgress())
                .lastReadAt(item.getLastReadAt())
                .build();
    }
}
