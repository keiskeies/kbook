package com.kbook.dto;

import lombok.Data;

@Data
public class UserProfileVO {
    private Long id;
    private String nickname;
    private String avatar;
    private String bio;
    private Integer age;
    private String gender;
    private String mbti;
    private Integer followerCount;
    private Integer followingCount;
    private Boolean isFollowing;
    private Integer completedBooks;
    private Integer readingBooks;
}
