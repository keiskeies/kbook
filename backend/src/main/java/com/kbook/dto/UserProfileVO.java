package com.kbook.dto;

import lombok.Data;

/**
 * 用户个人资料视图对象
 * 用于展示其他用户的公开资料信息
 */
@Data
public class UserProfileVO {
    /** 用户ID */
    private Long id;
    /** 昵称 */
    private String nickname;
    /** 头像URL */
    private String avatar;
    /** 个人简介 */
    private String bio;
    /** 心情状态 */
    private String mood;
    /** 年龄 */
    private Integer age;
    /** 性别 */
    private String gender;
    /** MBTI人格类型 */
    private String mbti;
    /** 粉丝数 */
    private Integer followerCount;
    /** 关注数 */
    private Integer followingCount;
    /** 当前用户是否已关注该用户 */
    private Boolean isFollowing;
    /** 已读完图书数 */
    private Integer completedBooks;
    /** 正在阅读图书数 */
    private Integer readingBooks;
}
