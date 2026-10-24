package com.kbook.dto.note;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生成成长总结的请求 — 时间窗不传则默认最近 30 天
 */
@Data
public class NoteInsightGenerateRequest {

    private LocalDateTime from;

    private LocalDateTime to;
}
