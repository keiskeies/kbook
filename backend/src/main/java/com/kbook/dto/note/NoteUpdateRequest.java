package com.kbook.dto.note;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 编辑笔记 — 只改自己的标题/正文/时间/标签，不动来源快照
 */
@Data
public class NoteUpdateRequest {

    @Size(max = 100, message = "标题不能超过 100 字")
    private String title;

    @Size(max = 20000, message = "笔记内容不能超过 20000 字")
    private String contentMd;

    private LocalDateTime occurredAt;

    /** 传 null 表示不修改标签；传空数组表示清空 */
    private List<@Size(max = 20, message = "单个标签不能超过 20 字") String> tags;
}
