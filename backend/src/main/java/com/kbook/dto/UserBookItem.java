package com.kbook.dto;

import lombok.Data;

@Data
public class UserBookItem {
    private Long bookId;
    private String title;
    private String author;
    private String coverUrl;
    private String format;
    private Double progress;
}
