package com.kbook.dto;

import com.kbook.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 用户信息数据传输对象
 * 包含用户的完整信息，用于登录后返回和前端展示
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfo {
    /** 用户ID */
    private Long id;
    /** 邮箱地址 */
    private String email;
    /** 昵称 */
    private String nickname;
    /** 头像URL */
    private String avatar;
    /** 角色：USER/ADMIN */
    private String role;
    /** 账号状态：ACTIVE/PENDING/BANNED */
    private String status;
    /** 邮箱是否已绑定 */
    private Boolean emailBound;
    /** 出生日期 */
    private LocalDate birthday;
    /** 性别 */
    private String gender;
    /** 是否已婚 */
    private Boolean married;
    /** 是否有子女（旧字段，保留兼容） */
    private Boolean hasChildren;
    /** 孩子年龄区间（逗号分隔）：0_2,3_6,7_12,13_17,18_plus,no_children */
    private String childrenAgeRanges;
    /** MBTI人格类型 */
    private String mbti;
    /** 职业 */
    private String occupation;
    /** 期望学历 */
    private String aspirationEducation;
    /** 创业状态 */
    private String entrepreneurship;
    /** 期望年收入范围 */
    private String aspirationIncome;
    /** 心情状态 */
    private String mood;
    /** 个人简介 */
    private String bio;
    /** 粉丝数 */
    private Integer followerCount;
    /** 关注数 */
    private Integer followingCount;

    /**
     * 从用户实体构建用户信息对象
     * @param user 用户实体
     * @return 用户信息数据传输对象
     */
    public static UserInfo from(User user) {
        return UserInfo.builder()
                .id(user.getId())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .role(user.getRole())
                .status(user.getStatus())
                .emailBound(user.getEmailBound())
                .birthday(user.getBirthday())
                .gender(user.getGender())
                .married(user.getMarried())
                .hasChildren(user.getHasChildren())
                .childrenAgeRanges(user.getChildrenAgeRanges())
                .mbti(user.getMbti())
                .occupation(user.getOccupation())
                .aspirationEducation(user.getAspirationEducation())
                .entrepreneurship(user.getEntrepreneurship())
                .aspirationIncome(user.getAspirationIncome())
                .mood(user.getMood())
                .bio(user.getBio())
                .followerCount(user.getFollowerCount())
                .followingCount(user.getFollowingCount())
                .build();
    }
}
