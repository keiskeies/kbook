package com.kbook.dto.user;

import lombok.Data;

import java.time.LocalDate;

/**
 * 更新用户特征请求
 * 用于用户修改个人特征信息（人口统计学属性），用于个性化推荐
 */
@Data
public class UpdateTraitsRequest {
    /** 出生日期 */
    private LocalDate birthday;
    /** 性别 */
    private String gender;
    /** 是否已婚 */
    private Boolean married;
    /** 是否有子女（旧字段，保留兼容） */
    private Boolean hasChildren;
    /** 孩子年龄区间（逗号分隔）：0_2,3_6,7_12,13_17,18_plus,no_children */
    private String childrenAgeRanges;
    /** MBTI人格类型 */
    private String mbti;
    /** 职业 */
    private String occupation;
    /** 期望学历 */
    private String aspirationEducation;
    /** 创业状态 */
    private String entrepreneurship;
    /** 期望年收入范围 */
    private String aspirationIncome;
}
