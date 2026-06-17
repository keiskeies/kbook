package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.common.util.CommonUtils;
import com.kbook.config.properties.BookStorageProperties;
import com.kbook.dto.user.UpdateBioRequest;
import com.kbook.dto.user.UpdateTraitsRequest;
import com.kbook.dto.user.UserInfo;
import com.kbook.dto.user.UpsertPreferenceRequest;
import com.kbook.entity.User;
import com.kbook.entity.UserBookPreference;
import com.kbook.repository.UserRepository;
import com.kbook.service.user.UserBookPreferenceService;
import com.kbook.service.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Tag(name = "用户")
public class UserController {

    private final UserService userService;
    private final BookStorageProperties storageProps;
    private final UserRepository userRepository;
    private final UserBookPreferenceService preferenceService;

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/me")
    public Result<UserInfo> getCurrentUser(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(UserInfo.from(userService.getUserById(userId)));
    }

    @Operation(summary = "更新用户资料")
    @PutMapping("/profile")
    public Result<UserInfo> updateProfile(Authentication authentication,
                                       @RequestParam(required = false) String nickname,
                                       @RequestParam(required = false) String avatar,
                                       @RequestParam(required = false) String bio) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(UserInfo.from(userService.updateProfile(userId, nickname, avatar, bio)));
    }

    @Operation(summary = "更新用户特征")
    @PutMapping("/profile/traits")
    public Result<UserInfo> updateTraits(Authentication authentication,
                                      @RequestBody UpdateTraitsRequest req) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(UserInfo.from(userService.updateTraits(userId, req.getBirthday(), req.getGender(),
                req.getMarried(), req.getHasChildren(), req.getChildrenAgeRanges(),
                req.getMbti(), req.getOccupation(),
                req.getAspirationEducation(), req.getEntrepreneurship(), req.getAspirationIncome())));
    }

    @Operation(summary = "更新心情")
    @PutMapping("/profile/mood")
    public Result<UserInfo> updateMood(Authentication authentication,
                                    @RequestParam(required = false) String mood) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(UserInfo.from(userService.updateMood(userId, mood)));
    }

    @Operation(summary = "更新图书对话风格")
    @PutMapping("/profile/book-chat-style")
    public Result<UserInfo> updateBookChatStyle(Authentication authentication,
                                             @RequestParam(required = false) String style) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(UserInfo.from(userService.updateBookChatStyle(userId, style)));
    }

    @Operation(summary = "上传头像")
    @PostMapping("/avatar")
    public Result<UserInfo> uploadAvatar(Authentication authentication,
                                      @RequestParam("file") MultipartFile file) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(UserInfo.from(userService.uploadAvatar(userId, file)));
    }

    @Operation(summary = "获取头像")
    @GetMapping("/avatar/{filename:.+}")
    public ResponseEntity<Resource> getAvatar(@PathVariable String filename) {
        Path avatarDir = Paths.get(storageProps.getUpload().getAvatarDir());
        Path imagePath = CommonUtils.safeResolvePath(avatarDir, filename);

        if (imagePath == null || !Files.exists(imagePath)) {
            return ResponseEntity.notFound().build();
        }

        return CommonUtils.buildImageResponse(imagePath, filename);
    }

    @Operation(summary = "更新个人简介")
    @PutMapping("/profile/bio")
    public Result<UserInfo> updateBio(Authentication auth, @RequestBody UpdateBioRequest req) {
        Long userId = (Long) auth.getPrincipal();
        User user = userService.getUserById(userId);
        user.setBio(req.getBio());
        userRepository.save(user);
        return Result.ok(UserInfo.from(user));
    }

    // ==================== 阅读偏好 ====================

    @Operation(summary = "获取所有偏好")
    @GetMapping("/preferences")
    public Result<List<UserBookPreference>> getAllPreferences(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(preferenceService.getAllPreferences(userId));
    }

    @Operation(summary = "获取排除偏好")
    @GetMapping("/preferences/exclude")
    public Result<List<UserBookPreference>> getExcludePreferences(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(preferenceService.getExcludePreferences(userId));
    }

    @Operation(summary = "添加排除偏好")
    @PostMapping("/preferences/exclude")
    public Result<UserBookPreference> addExcludePreference(Authentication authentication,
                                                            @RequestBody UpsertPreferenceRequest req) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(preferenceService.addExcludePreference(userId, req.getCategory(), req.getValue()));
    }

    @Operation(summary = "取消排除偏好")
    @DeleteMapping("/preferences/exclude")
    public Result<Void> removeExcludePreference(Authentication authentication,
                                                 @RequestParam String category,
                                                 @RequestParam String value) {
        Long userId = (Long) authentication.getPrincipal();
        preferenceService.removeExcludePreference(userId, category, value);
        return Result.ok();
    }

    @Operation(summary = "获取喜欢偏好")
    @GetMapping("/preferences/include")
    public Result<List<UserBookPreference>> getIncludePreferences(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(preferenceService.getIncludePreferences(userId));
    }

    @Operation(summary = "添加喜欢偏好")
    @PostMapping("/preferences/include")
    public Result<UserBookPreference> addIncludePreference(Authentication authentication,
                                                            @RequestBody UpsertPreferenceRequest req) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(preferenceService.addIncludePreference(userId, req.getCategory(), req.getValue()));
    }

    @Operation(summary = "取消喜欢偏好")
    @DeleteMapping("/preferences/include")
    public Result<Void> removeIncludePreference(Authentication authentication,
                                                 @RequestParam String category,
                                                 @RequestParam String value) {
        Long userId = (Long) authentication.getPrincipal();
        preferenceService.removeIncludePreference(userId, category, value);
        return Result.ok();
    }
}
