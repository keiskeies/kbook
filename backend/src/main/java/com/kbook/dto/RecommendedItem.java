package com.kbook.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RecommendedItem {
    private Long bookId;
    private String title;
    private String author;
    private String coverUrl;
    private String format;
    private Double rating;
    private String description;
    private Double matchScore;
    private Long readCount;
    private Double ruleScore;
    private Double vectorScore;
    private Double collabScore;
    private String recallPaths;
    private LocalDateTime recommendedAt;
}
