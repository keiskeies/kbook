package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.entity.User;
import com.kbook.service.UserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

/**
 * 用户控制器
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 获取当前用户信息
     */
    @GetMapping("/me")
    public Result<User> getCurrentUser(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.getUserById(userId));
    }

    /**
     * 更新用户资料
     */
    @PutMapping("/profile")
    public Result<User> updateProfile(Authentication authentication,
                                       @RequestParam(required = false) String nickname,
                                       @RequestParam(required = false) String avatar,
                                       @RequestParam(required = false) String bio) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.updateProfile(userId, nickname, avatar, bio));
    }

    /**
     * 更新用户画像（出生日期/性别/婚否/孩子/MBTI/职业/学历/创业意向/年收入）
     */
    @PutMapping("/profile/traits")
    public Result<User> updateTraits(Authentication authentication,
                                      @RequestBody UpdateTraitsRequest req) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.updateTraits(userId, req.getBirthday(), req.getGender(),
                req.getMarried(), req.getHasChildren(), req.getMbti(), req.getOccupation(),
                req.getEducation(), req.getEntrepreneurship(), req.getAnnualIncome()));
    }

    /**
     * 更新当前心情状态
     */
    @PutMapping("/profile/mood")
    public Result<User> updateMood(Authentication authentication,
                                    @RequestParam(required = false) String mood) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.updateMood(userId, mood));
    }

    /**
     * 上传头像
     */
    @PostMapping("/avatar")
    public Result<User> uploadAvatar(Authentication authentication,
                                      @RequestParam("file") MultipartFile file) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.uploadAvatar(userId, file));
    }

    @Data
    public static class UpdateTraitsRequest {
        private LocalDate birthday;
        private String gender;
        private Boolean married;
        private Boolean hasChildren;
        private String mbti;
        private String occupation;
        private String education;
        private String entrepreneurship;
        private String annualIncome;
    }
}
