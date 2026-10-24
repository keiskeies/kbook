package com.kbook.dto.note;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 编辑笔记 — 只改自己的标题/正文/时间，不动来源快照
 */
@Data
public class NoteUpdateRequest {

    @Size(max = 100, message = "标题不能超过 100 字")
    private String title;

    @Size(max = 20000, message = "笔记内容不能超过 20000 字")
    private String contentMd;

    private LocalDateTime occurredAt;
}
