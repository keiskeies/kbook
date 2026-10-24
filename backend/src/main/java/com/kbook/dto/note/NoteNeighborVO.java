package com.kbook.dto.note;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 相邻笔记 — 详情页「上一条 / 下一条」
 */
@Data
@Builder
public class NoteNeighborVO {

    private Long id;
    private String title;
    private LocalDateTime occurredAt;
}
