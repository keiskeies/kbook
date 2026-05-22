package com.kbook.dto;

import com.kbook.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfo {
    private Long id;
    private String email;
    private String nickname;
    private String avatar;
    private String role;
    private String status;
    private Boolean emailBound;
    private LocalDate birthday;
    private String gender;
    private Boolean married;
    private Boolean hasChildren;
    private String mbti;
    private String occupation;
    private String education;
    private String entrepreneurship;
    private String annualIncome;
    private String mood;
    private String bio;
    private Integer followerCount;
    private Integer followingCount;

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
                .mbti(user.getMbti())
                .occupation(user.getOccupation())
                .education(user.getEducation())
                .entrepreneurship(user.getEntrepreneurship())
                .annualIncome(user.getAnnualIncome())
                .mood(user.getMood())
                .bio(user.getBio())
                .followerCount(user.getFollowerCount())
                .followingCount(user.getFollowingCount())
                .build();
    }
}
