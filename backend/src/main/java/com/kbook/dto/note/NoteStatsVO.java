package com.kbook.dto.note;

import lombok.Builder;
import lombok.Data;

/**
 * 笔记成长概览 — 笔记页顶部的数据卡片
 */
@Data
@Builder
public class NoteStatsVO {

    /** 未删除的笔记总数 */
    private long total;
    /** 本周新增（周一起算） */
    private long weekCount;
    /** 涉及的不同书籍数 */
    private long bookCount;
    /** 连续记录天数（今天或昨天有笔记即算连续中） */
    private long streakDays;

    private long thoughtCount;
    private long captureCount;
    private long excerptCount;
}
