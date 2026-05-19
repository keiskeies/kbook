package com.kbook.dto;

import lombok.Data;

/**
 * 评分请求
 * 用户对图书进行评分（1.0-5.0分）
 */
@Data
public class RateRequest {
    /** 评分值（1.0-5.0） */
    private Double rating;
}
