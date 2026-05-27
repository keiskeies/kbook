package com.kbook.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MatchScoreDetailVO {
    private Long bookId;
    private Double overallScore;
    private Integer matchedDimensions;
    private Double coverageFactor;
    private List<DimensionScore> dimensions;

    @Data
    @Builder
    public static class DimensionScore {
        private String dimension;
        private String label;
        private Double score;
        private Double weight;
        private Double weightedScore;
    }
}
