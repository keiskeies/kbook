package com.kbook.dto.note;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 笔记成长总结视图对象
 */
@Data
@Builder
public class NoteInsightVO {

    private Long id;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private String title;
    private String contentMd;
    private Integer noteCount;
    private LocalDateTime createdAt;
}
