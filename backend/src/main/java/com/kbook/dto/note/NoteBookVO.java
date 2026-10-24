package com.kbook.dto.note;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 按书聚合的笔记概览 — 笔记页「按书回顾」视图
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NoteBookVO {

    private Long bookId;
    private String bookTitle;
    private String bookCover;
    private Long count;
    private LocalDateTime lastOccurredAt;
}
