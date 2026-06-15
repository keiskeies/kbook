package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.common.util.CommonUtils;
import com.kbook.config.properties.BookStorageProperties;
import com.kbook.dto.user.UpdateBioRequest;
import com.kbook.dto.user.UpdateTraitsRequest;
import com.kbook.entity.User;
import com.kbook.repository.UserRepository;
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

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Tag(name = "用户")
public class UserController {

    private final UserService userService;
    private final BookStorageProperties storageProps;
    private final UserRepository userRepository;

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/me")
    public Result<User> getCurrentUser(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.getUserById(userId));
    }

    @Operation(summary = "更新用户资料")
    @PutMapping("/profile")
    public Result<User> updateProfile(Authentication authentication,
                                       @RequestParam(required = false) String nickname,
                                       @RequestParam(required = false) String avatar,
                                       @RequestParam(required = false) String bio) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.updateProfile(userId, nickname, avatar, bio));
    }

    @Operation(summary = "更新用户特征")
    @PutMapping("/profile/traits")
    public Result<User> updateTraits(Authentication authentication,
                                      @RequestBody UpdateTraitsRequest req) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.updateTraits(userId, req.getBirthday(), req.getGender(),
                req.getMarried(), req.getHasChildren(), req.getChildrenAgeRanges(),
                req.getMbti(), req.getOccupation(),
                req.getAspirationEducation(), req.getEntrepreneurship(), req.getAspirationIncome()));
    }

    @Operation(summary = "更新心情")
    @PutMapping("/profile/mood")
    public Result<User> updateMood(Authentication authentication,
                                    @RequestParam(required = false) String mood) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.updateMood(userId, mood));
    }

    @Operation(summary = "更新图书对话风格")
    @PutMapping("/profile/book-chat-style")
    public Result<User> updateBookChatStyle(Authentication authentication,
                                             @RequestParam(required = false) String style) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.updateBookChatStyle(userId, style));
    }

    @Operation(summary = "上传头像")
    @PostMapping("/avatar")
    public Result<User> uploadAvatar(Authentication authentication,
                                      @RequestParam("file") MultipartFile file) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.uploadAvatar(userId, file));
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
    public Result<User> updateBio(Authentication auth, @RequestBody UpdateBioRequest req) {
        Long userId = (Long) auth.getPrincipal();
        User user = userService.getUserById(userId);
        user.setBio(req.getBio());
        userRepository.save(user);
        return Result.ok(user);
    }
}
