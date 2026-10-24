package com.kbook.dto.note;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 新建纯想法笔记
 */
@Data
public class NoteCreateRequest {

    @Size(max = 100, message = "标题不能超过 100 字")
    private String title;

    @Size(max = 20000, message = "笔记内容不能超过 20000 字")
    private String contentMd;

    /** 想法发生的时间，可回填；不传取当前时间 */
    private LocalDateTime occurredAt;

    /** 标签，最多 8 个 */
    private List<@Size(max = 20, message = "单个标签不能超过 20 字") String> tags;
}
