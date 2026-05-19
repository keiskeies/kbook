package com.kbook.dto;

import lombok.Data;

/**
 * 邀请结果
 * 返回邀请码和被邀请人邮箱
 */
@Data
public class InviteResult {
    /** 被邀请人邮箱 */
    private String email;
    
    /** 生成的邀请码 */
    private String inviteCode;

    public InviteResult(String email, String inviteCode) {
        this.email = email;
        this.inviteCode = inviteCode;
    }
}
