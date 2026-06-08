package com.kbook.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;

/**
 * 用户实体
 * 状态字段支持：PENDING(待审核) / APPROVED(已通过) / BANNED(封禁)
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(name = "uk_email", columnNames = "email")
}, indexes = {
        @Index(name = "idx_users_status", columnList = "status")
})
public class User extends BaseEntity {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 邮箱（登录账号） */
    @Column(nullable = false, length = 100)
    private String email;

    /** 密码（BCrypt 加密） */
    @Column(length = 100)
    private String password;

    /** 昵称 */
    @Column(length = 50)
    private String nickname;

    /** 头像 URL */
    @Column(length = 500)
    private String avatar;

    /** 角色：USER / ADMIN */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String role = "USER";

    /** 状态：PENDING / APPROVED / BANNED */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    /** 管理员是否已绑定邮箱 */
    @Column(name = "email_bound")
    @Builder.Default
    private Boolean emailBound = false;

    /** 出生日期 */
    @Column(name = "birthday")
    private LocalDate birthday;

    /** 性别：MALE / FEMALE / OTHER */
    @Column(length = 10)
    private String gender;

    /** 是否已婚 */
    @Column(name = "is_married")
    private Boolean married;

    /** 是否有孩子（旧字段，保留兼容。新代码请用 childrenAgeRanges） */
    @Column(name = "has_children")
    private Boolean hasChildren;

    /** 孩子年龄区间（多选，逗号分隔）：0_2 / 3_6 / 7_12 / 13_17 / 18_plus / no_children */
    @Column(name = "children_age_ranges", length = 100)
    private String childrenAgeRanges;

    /** MBTI 人格类型 */
    @Column(length = 10)
    private String mbti;

    /** 职业（多选，逗号分隔）：STUDENT / TECH / FINANCE / EDUCATION / MEDICAL / ARTS / MANAGEMENT / FREELANCE / RETIRED / OTHER */
    @Column(length = 200)
    private String occupation;

    /** 期望学历：HIGH_SCHOOL / COLLEGE / BACHELOR / MASTER / DOCTORATE / OTHER */
    @Column(name = "education", length = 20)
    private String aspirationEducation;

    /** 创业意向：ENTREPRENEUR_OR_WANT(正在创业/想创业) / NOT_INTERESTED(暂不考虑) */
    @Column(name = "entrepreneurship", length = 30)
    private String entrepreneurship;

    /** 期望年收入：UNDER_50K / 50K_150K / 150K_300K / 300K_500K / 500K_1M / OVER_1M / PREFER_NOT_TO_SAY */
    @Column(name = "annual_income", length = 30)
    private String aspirationIncome;

    /** 阅读意图+心情状态，格式 "INTENT|MOOD"，如 "GROWTH|CALM"。兼容旧格式纯 MOOD 值。 */
    @Column(length = 50)
    private String mood;

    /** AI 图书问答的对话风格：CASUAL=随和聊天(默认) / DEEP=深度分析 / CONCISE=简洁直接 / WITTY=幽默风趣 */
    @Column(name = "book_chat_style", length = 20)
    @Builder.Default
    private String bookChatStyle = "DEEP";

    /** 个人简介 */
    @Column(length = 500)
    private String bio;

    /** 粉丝数（冗余计数） */
    @Column(name = "follower_count")
    @Builder.Default
    private Integer followerCount = 0;

    /** 关注数（冗余计数） */
    @Column(name = "following_count")
    @Builder.Default
    private Integer followingCount = 0;

    @Override
    public Long getId() {
        return id;
    }
}
