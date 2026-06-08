package com.kbook.dto.admin;

import lombok.Data;

/**
 * 更新推荐系数请求
 * 用于修改指定推荐系数的值和锁定状态
 */
@Data
public class UpdateCoefficientRequest {
    /** 系数分类 */
    private String category;
    /** 系数键名 */
    private String key;
    /** 系数值 */
    private Double value;
    /** 是否锁定（锁定后系统不再自动调整） */
    private Boolean locked;
}
