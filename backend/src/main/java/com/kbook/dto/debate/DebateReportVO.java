package com.kbook.dto.debate;

import com.kbook.entity.debate.DebateReport;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 辩论报告视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebateReportVO {

    private Long id;
    private String sessionId;
    private Long bookId;
    private String topic;
    private String content;
    private String summaryJson;
    private String bestDebater;
    private String bestDebaterPosition;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 从实体构建视图对象
     */
    public static DebateReportVO from(DebateReport entity) {
        return DebateReportVO.builder()
                .id(entity.getId())
                .sessionId(entity.getSessionId())
                .bookId(entity.getBookId())
                .topic(entity.getTopic())
                .content(entity.getContent())
                .summaryJson(entity.getSummaryJson())
                .bestDebater(entity.getBestDebater())
                .bestDebaterPosition(entity.getBestDebaterPosition())
                .status(entity.getStatus())
                .errorMessage(entity.getErrorMessage())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
