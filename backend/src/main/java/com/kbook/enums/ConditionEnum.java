package com.kbook.enums;

/**
 * 动态查询条件操作符枚举
 * <p>
 * 对应 JPA Criteria API 的查询谓词类型
 */
public enum ConditionEnum {
    /** 等于 */
    EQ,
    /** 不等于 */
    NE,
    /** 大于 */
    GT,
    /** 大于等于 */
    GE,
    /** 小于 */
    LT,
    /** 小于等于 */
    LE,
    /** 模糊匹配（前后都加%） */
    LIKE,
    /** 左模糊（右边加%） */
    LL,
    /** 右模糊（左边加%） */
    LR,
    /** IN 查询 */
    IN,
    /** NOT IN 查询 */
    NI,
    /** BETWEEN 区间查询 */
    BT,
    /** 为空 */
    IS_NULL,
    /** 不为空 */
    NOT_NULL,
    /** 等于或为空 */
    OR_NULL;

    public static ConditionEnum fromString(String op) {
        if (op == null || op.isBlank()) return EQ;
        return switch (op.trim().toUpperCase()) {
            case "EQ", "=", "等于" -> EQ;
            case "NE", "!=", "不等于" -> NE;
            case "GT", ">", "大于" -> GT;
            case "GE", ">=", "大于等于" -> GE;
            case "LT", "<", "小于" -> LT;
            case "LE", "<=", "小于等于" -> LE;
            case "LIKE", "CONTAINS", "包含" -> LIKE;
            case "LL", "STARTS_WITH", "左匹配" -> LL;
            case "LR", "ENDS_WITH", "右匹配" -> LR;
            case "IN", "在" -> IN;
            case "NI", "NOT_IN", "不在" -> NI;
            case "BT", "BETWEEN", "区间" -> BT;
            case "IS_NULL", "NULL", "为空" -> IS_NULL;
            case "NOT_NULL", "不为空" -> NOT_NULL;
            case "OR_NULL" -> OR_NULL;
            default -> {
                // 尝试直接解析
                try {
                    yield valueOf(op.toUpperCase());
                } catch (IllegalArgumentException e) {
                    yield EQ;
                }
            }
        };
    }
}
