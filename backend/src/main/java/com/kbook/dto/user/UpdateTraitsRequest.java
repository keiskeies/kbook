package com.kbook.dto.user;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 更新用户特征请求
 * 用于用户修改个人特征信息（人口统计学属性），用于个性化推荐
 * <p>
 * 安全要求：
 * - 所有文本字段强制最大长度校验（防止存储膨胀/内存耗尽）
 * - 所有文本字段在 Service 层进行 HTML 实体编码（防止存储型 XSS）
 * - mbti 字段在 Service 层有白名单校验
 */
@Data
public class UpdateTraitsRequest {
    /** 出生日期 */
    private LocalDate birthday;
    /** 性别 */
    @Size(max = 10, message = "性别字段过长")
    private String gender;
    /** 是否已婚 */
    private Boolean married;
    /** 是否有子女（旧字段，保留兼容） */
    private Boolean hasChildren;
    /** 孩子年龄区间（逗号分隔）：0_2,3_6,7_12,13_17,18_plus,no_children */
    @Size(max = 100, message = "孩子年龄区间字段过长")
    private String childrenAgeRanges;
    /** MBTI人格类型 */
    @Size(max = 10, message = "MBTI字段过长")
    private String mbti;
    /** 职业 */
    @Size(max = 200, message = "职业字段过长")
    private String occupation;
    /** 期望学历 */
    @Size(max = 20, message = "期望学历字段过长")
    private String aspirationEducation;
    /** 创业状态 */
    @Size(max = 30, message = "创业状态字段过长")
    private String entrepreneurship;
    /** 期望年收入范围 */
    @Size(max = 30, message = "期望年收入字段过长")
    private String aspirationIncome;
}
