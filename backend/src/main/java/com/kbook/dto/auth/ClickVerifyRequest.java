package com.kbook.dto.auth;

import lombok.Data;

import java.util.List;

/**
 * 点击验证码验证请求
 * 用于验证用户点击图片验证码的位置是否正确
 */
@Data
public class ClickVerifyRequest {
    /** 验证码ID */
    private String captchaId;
    
    /** 点击位置坐标列表 [x1, y1, x2, y2, ...] */
    private List<Integer> positions;
}
