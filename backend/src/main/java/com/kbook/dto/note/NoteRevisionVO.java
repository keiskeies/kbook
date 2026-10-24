package com.kbook.dto.note;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 笔记历史版本视图对象
 */
@Data
@Builder
public class NoteRevisionVO {

    private Long id;
    private Integer revisionNo;
    private String title;
    private String contentMd;
    private LocalDateTime occurredAt;
    /** 这一版被覆盖（即发生编辑）的时间 */
    private LocalDateTime editedAt;
}
