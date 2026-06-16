package com.kbook.dto.user;

import lombok.Data;

/**
 * 添加/更新偏好请求
 */
@Data
public class UpsertPreferenceRequest {
    /** 偏好类别：TAG、AUTHOR、FORMAT */
    private String category;
    /** 偏好值 */
    private String value;
}
