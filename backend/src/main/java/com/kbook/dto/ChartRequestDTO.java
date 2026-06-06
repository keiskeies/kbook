package com.kbook.dto;

import com.kbook.enums.ConditionEnum;
import com.kbook.enums.chart.CalcType;
import com.kbook.enums.chart.ColumnType;
import com.kbook.enums.chart.TimeDeltaEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 图表统计请求参数
 * <p>
 * 支持按时间维度或字段维度聚合统计，可指定计算方式（COUNT/SUM/AVG）和分组字段。
 * <p>
 * 示例：
 * <pre>
 * // 按天统计本周入库数量
 * ChartRequestDTO.builder()
 *     .field("createdAt").fieldType(ColumnType.TIME).fieldDelta(TimeDeltaEnum.ALL_DAYS)
 *     .calcType(CalcType.COUNT)
 *     .start(weekStart).end(weekEnd)
 *     .build();
 *
 * // 按格式分组统计图书数量
 * ChartRequestDTO.builder()
 *     .field("format").fieldType(ColumnType.FIELD)
 *     .calcType(CalcType.COUNT)
 *     .build();
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChartRequestDTO {

    /** 计算方式：COUNT(计数)、SUM(求和)、AVG(平均值) */
    private CalcType calcType;

    /** 求和/平均值字段（calcType 为 SUM/AVG 时使用） */
    private String sumField;

    /** 时间起点 */
    private LocalDateTime start;

    /** 时间终点 */
    private LocalDateTime end;

    /** 时间字段名，默认 createdAt */
    private String timeField;

    /** 时间粒度（按小时/天/周/月/季度/年） */
    private TimeDeltaEnum fieldDelta;

    /** X轴类型：TIME(时间维度) 或 FIELD(字段维度) */
    private ColumnType fieldType;

    /** 统计字段 */
    private String field;

    /** 分组字段（可选，用于多系列图表） */
    private String groupField;

    /** 额外过滤条件 */
    @Builder.Default
    private List<ConditionDTO> conditions = new ArrayList<>();

    /**
     * 快速添加等值条件
     */
    public ChartRequestDTO eq(String column, Object value) {
        this.conditions.add(new ConditionDTO(column, ConditionEnum.EQ, value));
        return this;
    }

    /**
     * 快速添加区间条件
     */
    public ChartRequestDTO between(String column, Object min, Object max) {
        this.conditions.add(ConditionDTO.between(column, min, max));
        return this;
    }
}
