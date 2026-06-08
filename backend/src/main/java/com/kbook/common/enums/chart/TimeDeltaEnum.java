package com.kbook.common.enums.chart;

import lombok.Getter;

/**
 * 时间粒度枚举
 * <p>
 * 用于图表统计时按不同时间维度聚合数据
 */
@Getter
public enum TimeDeltaEnum {
    /** 按小时（0-23） */
    HOUR("按小时"),
    /** 所有小时间（含日期，如 2024-01-01 08） */
    ALL_HOURS("按小时(含日期)"),
    /** 星期几（0=周一 ~ 6=周日） */
    WEEK_DAY("按星期"),
    /** 月份中的天（01-31） */
    MONTH_DAYS("按月中天"),
    /** 所有的天（含年月，如 2024-01-15） */
    ALL_DAYS("按天"),
    /** 月份（01-12） */
    MONTH("按月份"),
    /** 所有月份（含年，如 2024-03） */
    ALL_MONTHS("按月(含年)"),
    /** 季度（1-4） */
    QUARTER("按季度"),
    /** 所有季度（含年，如 2024-2） */
    ALL_QUARTERS("按季度(含年)"),
    /** 年份（如 2024） */
    YEAR("按年");

    private final String label;

    TimeDeltaEnum(String label) {
        this.label = label;
    }
}
