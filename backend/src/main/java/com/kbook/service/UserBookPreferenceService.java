package com.kbook.service;

import com.kbook.common.service.AbstractServiceImpl;
import com.kbook.entity.UserBookPreference;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.repository.UserBookPreferenceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户书籍偏好服务
 * 管理用户不喜欢的书籍标签/作者/格式（排除），以及喜欢的书籍标签/作者/格式（想看）
 */
@Slf4j
@Service
@LogModule("用户偏好")
public class UserBookPreferenceService extends AbstractServiceImpl<UserBookPreference, Long> {

    /** 用户偏好数据仓库 */
    @Autowired
    private UserBookPreferenceRepository preferenceRepository;
    /** 推荐服务（偏好变更时触发推荐重算） */
    @Autowired
    private RecommendService recommendService;

    /**
     * 添加用户排除偏好（不想看某类书）
     */
    @LogAction("添加排除偏好")
    @Transactional
    public UserBookPreference addExcludePreference(Long userId, String category, String value) {
        // 检查是否已存在
        var existing = preferenceRepository.findByUserIdAndCategoryAndValue(userId, category, value);
        if (existing.isPresent()) {
            // 已存在则更新类型为 EXCLUDE
            UserBookPreference pref = existing.get();
            pref.setType("EXCLUDE");
            UserBookPreference saved = updateOne(pref);
            recommendService.clearUserCache(userId);
            recommendService.asyncRecompute(userId);
            return saved;
        }

        UserBookPreference pref = UserBookPreference.builder()
                .userId(userId)
                .category(category.toUpperCase())
                .value(value)
                .type("EXCLUDE")
                .build();
        UserBookPreference saved = saveOne(pref);
        recommendService.clearUserCache(userId);
        recommendService.asyncRecompute(userId);
        log.info("用户添加排除偏好: userId={}, category={}, value={}", userId, category, value);
        return saved;
    }

    /**
     * 恢复/取消排除偏好（用户反悔，想看该类书了）
     */
    @LogAction("移除排除偏好")
    @Transactional
    public boolean removeExcludePreference(Long userId, String category, String value) {
        var existing = preferenceRepository.findByUserIdAndCategoryAndValue(userId, category, value);
        if (existing.isPresent()) {
            deleteOneById(existing.get().getId());
            recommendService.clearUserCache(userId);
            recommendService.asyncRecompute(userId);
            log.info("用户取消排除偏好: userId={}, category={}, value={}", userId, category, value);
            return true;
        }
        return false;
    }

    /**
     * 获取用户所有排除偏好
     */
    @LogAction("获取排除偏好")
    public List<UserBookPreference> getExcludePreferences(Long userId) {
        return preferenceRepository.findByUserIdAndType(userId, "EXCLUDE");
    }

    /**
     * 获取用户排除的标签列表
     */
    @LogAction("获取排除标签")
    public List<String> getExcludedTags(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "TAG", "EXCLUDE")
                .stream()
                .map(UserBookPreference::getValue)
                .collect(Collectors.toList());
    }

    /**
     * 获取用户排除的作者列表
     */
    @LogAction("获取排除作者")
    public List<String> getExcludedAuthors(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "AUTHOR", "EXCLUDE")
                .stream()
                .map(UserBookPreference::getValue)
                .collect(Collectors.toList());
    }

    /**
     * 获取用户排除的格式列表
     */
    @LogAction("获取排除格式")
    public List<String> getExcludedFormats(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "FORMAT", "EXCLUDE")
                .stream()
                .map(UserBookPreference::getValue)
                .collect(Collectors.toList());
    }

    /**
     * 获取用户所有偏好
     */
    @LogAction("获取所有偏好")
    public List<UserBookPreference> getAllPreferences(Long userId) {
        return preferenceRepository.findByUserId(userId);
    }

    // ==================== INCLUDE（喜欢/想看）偏好 ====================

    /**
     * 添加用户喜欢偏好（想看某类书）
     */
    @LogAction("添加喜欢偏好")
    @Transactional
    public UserBookPreference addIncludePreference(Long userId, String category, String value) {
        var existing = preferenceRepository.findByUserIdAndCategoryAndValue(userId, category, value);
        if (existing.isPresent()) {
            UserBookPreference pref = existing.get();
            pref.setType("INCLUDE");
            UserBookPreference saved = updateOne(pref);
            recommendService.clearUserCache(userId);
            recommendService.asyncRecompute(userId);
            return saved;
        }
        UserBookPreference pref = UserBookPreference.builder()
                .userId(userId).category(category.toUpperCase()).value(value).type("INCLUDE").build();
        UserBookPreference saved = saveOne(pref);
        recommendService.clearUserCache(userId);
        recommendService.asyncRecompute(userId);
        log.info("用户添加喜欢偏好: userId={}, category={}, value={}", userId, category, value);
        return saved;
    }

    /**
     * 取消喜欢偏好
     */
    @LogAction("取消喜欢偏好")
    @Transactional
    public boolean removeIncludePreference(Long userId, String category, String value) {
        var existing = preferenceRepository.findByUserIdAndCategoryAndValueAndType(userId, category, value, "INCLUDE");
        if (existing.isPresent()) {
            deleteOneById(existing.get().getId());
            recommendService.clearUserCache(userId);
            recommendService.asyncRecompute(userId);
            log.info("用户取消喜欢偏好: userId={}, category={}, value={}", userId, category, value);
            return true;
        }
        return false;
    }

    /** 获取用户所有喜欢偏好 */
    @LogAction("获取喜欢偏好")
    public List<UserBookPreference> getIncludePreferences(Long userId) {
        return preferenceRepository.findByUserIdAndType(userId, "INCLUDE");
    }

    /** 获取用户喜欢的标签列表 */
    @LogAction("获取喜欢标签")
    public List<String> getIncludedTags(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "TAG", "INCLUDE")
                .stream().map(UserBookPreference::getValue).collect(Collectors.toList());
    }

    /** 获取用户喜欢的作者列表 */
    @LogAction("获取喜欢作者")
    public List<String> getIncludedAuthors(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "AUTHOR", "INCLUDE")
                .stream().map(UserBookPreference::getValue).collect(Collectors.toList());
    }

    /** 获取用户喜欢的格式列表 */
    @LogAction("获取喜欢格式")
    public List<String> getIncludedFormats(Long userId) {
        return preferenceRepository.findByUserIdAndCategoryAndType(userId, "FORMAT", "INCLUDE")
                .stream().map(UserBookPreference::getValue).collect(Collectors.toList());
    }
}
