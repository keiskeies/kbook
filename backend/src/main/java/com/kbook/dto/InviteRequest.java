package com.kbook.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 邀请请求
 * 用于邀请好友注册并赠送图书
 */
@Data
public class InviteRequest {
    /** 被邀请人邮箱 */
    @Email(message = "邮箱格式不正确")
    @NotBlank(message = "邮箱不能为空")
    private String email;

    /** 赠送的图书ID（可选） */
    private Long bookId;
}
