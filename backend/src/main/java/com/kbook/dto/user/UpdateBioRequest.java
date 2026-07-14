package com.kbook.dto.user;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新个人简介请求
 * 用于用户修改个人简介内容
 * <p>
 * 安全要求：
 * - bio 字段强制最大长度校验（防止存储膨胀/内存耗尽）
 * - bio 字段在 Service 层进行 HTML 实体编码（防止存储型 XSS）
 */
@Data
public class UpdateBioRequest {
    /** 个人简介 */
    @Size(max = 500, message = "个人简介过长，最多500字符")
    private String bio;
}
