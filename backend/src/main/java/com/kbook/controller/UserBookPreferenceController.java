package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.entity.UserBookPreference;
import com.kbook.service.UserBookPreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户阅读偏好控制器
 * 管理用户不喜欢的书籍标签/作者/格式（排除），以及喜欢的标签/作者/格式（想看）
 */
@RestController
@RequestMapping("/api/preferences")
@RequiredArgsConstructor
public class UserBookPreferenceController {

    private final UserBookPreferenceService preferenceService;

    // ==================== 排除偏好（不想看） ====================

    @PostMapping("/exclude")
    public Result<UserBookPreference> addExcludePreference(
            Authentication auth,
            @RequestBody Map<String, String> body) {
        Long userId = Long.parseLong(auth.getName());
        String category = body.get("category");
        String value = body.get("value");
        if (category == null || value == null) {
            return Result.fail("category 和 value 不能为空");
        }
        return Result.ok(preferenceService.addExcludePreference(userId, category, value));
    }

    @DeleteMapping("/exclude")
    public Result<Void> removeExcludePreference(
            Authentication auth,
            @RequestParam String category,
            @RequestParam String value) {
        Long userId = Long.parseLong(auth.getName());
        return preferenceService.removeExcludePreference(userId, category, value)
                ? Result.ok(null) : Result.fail("偏好不存在");
    }

    @GetMapping("/exclude")
    public Result<List<UserBookPreference>> getExcludePreferences(Authentication auth) {
        Long userId = Long.parseLong(auth.getName());
        return Result.ok(preferenceService.getExcludePreferences(userId));
    }

    // ==================== 喜欢偏好（想看） ====================

    @PostMapping("/include")
    public Result<UserBookPreference> addIncludePreference(
            Authentication auth,
            @RequestBody Map<String, String> body) {
        Long userId = Long.parseLong(auth.getName());
        String category = body.get("category");
        String value = body.get("value");
        if (category == null || value == null) {
            return Result.fail("category 和 value 不能为空");
        }
        return Result.ok(preferenceService.addIncludePreference(userId, category, value));
    }

    @DeleteMapping("/include")
    public Result<Void> removeIncludePreference(
            Authentication auth,
            @RequestParam String category,
            @RequestParam String value) {
        Long userId = Long.parseLong(auth.getName());
        return preferenceService.removeIncludePreference(userId, category, value)
                ? Result.ok(null) : Result.fail("偏好不存在");
    }

    @GetMapping("/include")
    public Result<List<UserBookPreference>> getIncludePreferences(Authentication auth) {
        Long userId = Long.parseLong(auth.getName());
        return Result.ok(preferenceService.getIncludePreferences(userId));
    }

    // ==================== 全部偏好 ====================

    @GetMapping
    public Result<List<UserBookPreference>> getAllPreferences(Authentication auth) {
        Long userId = Long.parseLong(auth.getName());
        return Result.ok(preferenceService.getAllPreferences(userId));
    }
}
