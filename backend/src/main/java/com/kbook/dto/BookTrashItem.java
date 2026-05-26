package com.kbook.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookTrashItem {

    private Long trashId;

    private Long bookId;

    private String title;

    private String author;

    private String coverUrl;

    private String format;

    private String formatTags;

    private Long fileSize;

    private Double rating;

    private LocalDateTime trashedAt;
}
