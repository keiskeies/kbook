package com.kbook.dto.note;

import com.kbook.common.enums.CaptureMode;
import com.kbook.common.enums.NoteSourceType;
import com.kbook.common.enums.NoteType;
import com.kbook.common.enums.SourceStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 笔记视图对象
 */
@Data
@Builder
public class NoteVO {

    private Long id;
    private String title;
    private String contentMd;

    private NoteType noteType;
    private NoteSourceType sourceType;
    private Long sourceId;
    private SourceStatus sourceStatus;
    private NoteSourceSnapshot snapshot;
    private CaptureMode captureMode;

    private Long bookId;
    private String bookTitle;
    private String bookCover;

    private List<String> tags;
    private boolean pinned;
    private boolean favorited;

    private LocalDateTime occurredAt;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
