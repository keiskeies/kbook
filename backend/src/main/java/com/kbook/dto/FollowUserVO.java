package com.kbook.dto;

import lombok.Data;

/**
 * 关注用户视图对象
 * 用于展示用户关注的其他用户信息
 */
@Data
public class FollowUserVO {
    /** 用户ID */
    private Long userId;
    
    /** 昵称 */
    private String nickname;
    
    /** 头像URL */
    private String avatar;
    
    /** 个人简介 */
    private String bio;
}
