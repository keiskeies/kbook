package com.kbook.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户实体
 * 状态字段支持：PENDING(待审核) / APPROVED(已通过) / BANNED(封禁)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(name = "uk_email", columnNames = "email")
})
public class User {

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

    /** 是否有孩子 */
    @Column(name = "has_children")
    private Boolean hasChildren;

    /** MBTI 人格类型 */
    @Column(length = 10)
    private String mbti;

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

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
