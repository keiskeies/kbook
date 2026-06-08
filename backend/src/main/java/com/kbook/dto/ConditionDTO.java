package com.kbook.dto;

import com.kbook.common.enums.ConditionEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 动态查询条件数据传输对象
 * <p>
 * 用于构建 JPA Criteria 查询条件
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConditionDTO {

    /**
     * 字段名（对应实体属性名，驼峰命名）
     */
    private String column;

    /**
     * 操作符
     */
    private ConditionEnum op;

    /**
     * 值（支持单个值或多个值，BT 需要两个值，IN/NI 需要多个值）
     */
    private List<Object> values;

    public ConditionDTO(String column, ConditionEnum op, Object... values) {
        this.column = column;
        this.op = op;
        this.values = values != null ? List.of(values) : List.of();
    }

    /**
     * 快速创建单个值的等值条件
     */
    public static ConditionDTO eq(String column, Object value) {
        return new ConditionDTO(column, ConditionEnum.EQ, value);
    }

    /**
     * 快速创建模糊匹配条件
     */
    public static ConditionDTO like(String column, String value) {
        return new ConditionDTO(column, ConditionEnum.LIKE, value);
    }

    /**
     * 快速创建区间条件
     */
    public static ConditionDTO between(String column, Object min, Object max) {
        return new ConditionDTO(column, ConditionEnum.BT, min, max);
    }

    /**
     * 快速创建 IN 条件
     */
    public static ConditionDTO in(String column, Object... values) {
        return new ConditionDTO(column, ConditionEnum.IN, values);
    }

    /**
     * 获取第一个值
     */
    public Object firstValue() {
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    /**
     * 获取值列表
     */
    public List<Object> getValues() {
        return values != null ? values : List.of();
    }
}
