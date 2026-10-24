package com.kbook.dto.note;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答引用的笔记来源
 */
@Data
@Builder
public class NoteAskSourceVO {

    private Long id;
    private String title;
    private String bookTitle;
    private LocalDateTime occurredAt;
    private String snippet;
}
