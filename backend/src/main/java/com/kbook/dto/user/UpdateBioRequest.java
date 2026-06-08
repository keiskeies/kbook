package com.kbook.dto.user;

import lombok.Data;

/**
 * 更新个人简介请求
 * 用于用户修改个人简介内容
 */
@Data
public class UpdateBioRequest {
    /** 个人简介 */
    private String bio;
}
