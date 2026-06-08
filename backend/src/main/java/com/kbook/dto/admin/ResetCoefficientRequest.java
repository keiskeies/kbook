package com.kbook.dto.admin;

import lombok.Data;

/**
 * 重置推荐系数请求
 * 用于重置指定分类和键的推荐系数为默认值
 */
@Data
public class ResetCoefficientRequest {
    /** 系数分类 */
    private String category;
    /** 系数键名 */
    private String key;
}
