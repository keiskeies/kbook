package com.kbook.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 注册请求
 * 用于用户通过邮箱验证码注册新账号
 */
@Data
public class RegisterRequest {
    /** 邮箱地址 */
    @Email @NotBlank
    private String email;
    /** 邮箱验证码 */
    @NotBlank
    private String code;
    /** 登录密码（6-20位） */
    @NotBlank @Size(min = 6, max = 20, message = "密码长度应为6-20位")
    private String password;
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
}
