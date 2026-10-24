package com.kbook.dto.note;

import lombok.Builder;
import lombok.Data;

import java.util.List;

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
    /** 累计有记录的天数（近 400 天内） */
    private long activeDays;

    private long thoughtCount;
    private long captureCount;
    private long excerptCount;
    /** 收藏数量 */
    private long favoriteCount;

    /** 最近 7 天每天的笔记数，按日期升序 */
    private List<NoteDailyCountVO> recentDays;

    /** 最近 364 天每天的笔记数，按日期升序（写作热力图） */
    private List<NoteDailyCountVO> heatmap;
}
