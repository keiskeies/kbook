package com.kbook.service.admin;

import com.kbook.common.exception.BusinessException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 管理后台时间窗的两条共享判定：预设天数归一化 + 自定义区间校验。
 *
 * 抽出来只有一个原因：`AdminDashboardService` 与 `BookProfileService` 必须给同一个窗口出同一个数，
 * 而这两段逻辑此前各写一份。加一档预设（例如「近 15 天」）时只改一处，另一处会静默按 7 天算——
 * 那种漂移单测钉不住，只能靠「没有第二份」来防。区间本体（`DateRange` / `Window`）仍在各自服务里，
 * 它们携带的派生量不同（环比、粒度、缓存键），统一反而会把看板拖进画像的改动面里。
 */
final class AdminRangeRules {

    private AdminRangeRules() {
    }

    /** 预设档位：只有 30 / 90 / 0（全部）有意义，其余一律按近 7 天 */
    static int normalizeDays(int days) {
        return switch (days) {
            case 30 -> 30;
            case 90 -> 90;
            case 0 -> 0;
            default -> 7;
        };
    }

    /**
     * 自定义区间的三条硬校验，报错文案是前端直接展示的，改动会让两处文案分叉。
     * 上限沿用 {@code AdminDashboardService.MAX_CUSTOM_RANGE_DAYS}，不在这里再声明一遍。
     */
    static void requireValidCustomRange(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new BusinessException("自定义区间需要同时提供开始和结束日期");
        }
        if (start.isAfter(end)) {
            throw new BusinessException("开始日期不能晚于结束日期");
        }
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        if (span > AdminDashboardService.MAX_CUSTOM_RANGE_DAYS) {
            throw new BusinessException("自定义区间最长支持 " + AdminDashboardService.MAX_CUSTOM_RANGE_DAYS
                    + " 天，全部数据请使用「全部」");
        }
    }

    /** 窗口只由起止日表达；{@code startDate == null} 即「全部时间」。 */
    record Range(LocalDate startDate, LocalDate endDate) {
    }

    /**
     * 三个入参归一成一个窗口，主看板与画像抽屉必须走这一个函数。
     *
     * 优先级是第 11 节点名要测的一条：`days` 与 `start/end` 同时给时以 `start/end` 为准；
     * `days` 走 30/90/0 档位，其余值按近 7 天。`today` 由调用方传入而不是就地取，
     * 否则这条优先级规则只能靠起服务打接口来验（跨零点时两端各取各的今天也会错位）。
     */
    static Range resolve(LocalDate start, LocalDate end, int days, LocalDate today) {
        if (start != null || end != null) {
            requireValidCustomRange(start, end);
            return new Range(start, end);
        }
        int normalized = normalizeDays(days);
        if (normalized == 0) {
            return new Range(null, null);
        }
        return new Range(today.minusDays(normalized - 1L), today);
    }
}
