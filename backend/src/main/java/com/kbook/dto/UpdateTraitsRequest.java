package com.kbook.dto;

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
    /** 是否有子女 */
    private Boolean hasChildren;
    /** MBTI人格类型 */
    private String mbti;
    /** 职业 */
    private String occupation;
    /** 学历 */
    private String education;
    /** 创业状态 */
    private String entrepreneurship;
    /** 年收入范围 */
    private String annualIncome;
}
