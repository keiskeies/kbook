package com.kbook.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 评分请求
 * 用户对图书进行评分（1.0-5.0分）
 */
@Data
public class RateRequest {
    /** 评分值（1.0-5.0） */
    @NotNull(message = "评分不能为空")
    @DecimalMin(value = "1.0", message = "评分最低 1.0")
    @DecimalMax(value = "5.0", message = "评分最高 5.0")
    private Double rating;
}
