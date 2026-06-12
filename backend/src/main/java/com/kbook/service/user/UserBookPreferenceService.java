package com.kbook.service.user;

import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.entity.UserBookPreference;
import com.kbook.repository.UserBookPreferenceRepository;
import com.kbook.service.recommend.RecommendService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

import static com.kbook.common.util.QueryBuilder.*;

@Slf4j
@Service
@LogModule("用户偏好")
public class UserBookPreferenceService {

    @Autowired
    private UserBookPreferenceRepository preferenceRepository;
    @Autowired
    private RecommendService recommendService;

    @LogAction("添加排除偏好")
    @Transactional
    public UserBookPreference addExcludePreference(Long userId, String category, String value) {
        var existing = preferenceRepository.query()
                .where(UserBookPreference::getUserId, eq(userId))
                .and(UserBookPreference::getCategory, eq(category.toUpperCase()))
                .and(UserBookPreference::getValue, eq(value))
                .list(1).stream().findFirst();
        if (existing.isPresent()) {
            UserBookPreference pref = existing.get();
            pref.setType("EXCLUDE");
            UserBookPreference saved = preferenceRepository.save(pref);
            recommendService.clearUserCache(userId);
            recommendService.asyncRecompute(userId);
            return saved;
        }
        UserBookPreference pref = UserBookPreference.builder()
                .userId(userId).category(category.toUpperCase()).value(value).type("EXCLUDE").build();
        UserBookPreference saved = preferenceRepository.save(pref);
        recommendService.clearUserCache(userId);
        recommendService.asyncRecompute(userId);
        log.info("用户添加排除偏好: userId={}, category={}, value={}", userId, category, value);
        return saved;
    }

    @LogAction("移除排除偏好")
    @Transactional
    public boolean removeExcludePreference(Long userId, String category, String value) {
        var existing = preferenceRepository.query()
                .where(UserBookPreference::getUserId, eq(userId))
                .and(UserBookPreference::getCategory, eq(category.toUpperCase()))
                .and(UserBookPreference::getValue, eq(value))
                .list(1).stream().findFirst();
        if (existing.isPresent()) {
            preferenceRepository.deleteById(existing.get().getId());
            recommendService.clearUserCache(userId);
            recommendService.asyncRecompute(userId);
            return true;
        }
        return false;
    }

    @LogAction("获取排除偏好")
    public List<UserBookPreference> getExcludePreferences(Long userId) {
        return preferenceRepository.query()
                .where(UserBookPreference::getUserId, eq(userId))
                .and(UserBookPreference::getType, eq("EXCLUDE"))
                .list();
    }

    @LogAction("获取排除标签")
    public List<String> getExcludedTags(Long userId) {
        return preferenceRepository.query()
                .where(UserBookPreference::getUserId, eq(userId))
                .and(UserBookPreference::getCategory, eq("TAG"))
                .and(UserBookPreference::getType, eq("EXCLUDE"))
                .list().stream().map(UserBookPreference::getValue).collect(Collectors.toList());
    }

    @LogAction("获取排除作者")
    public List<String> getExcludedAuthors(Long userId) {
        return preferenceRepository.query()
                .where(UserBookPreference::getUserId, eq(userId))
                .and(UserBookPreference::getCategory, eq("AUTHOR"))
                .and(UserBookPreference::getType, eq("EXCLUDE"))
                .list().stream().map(UserBookPreference::getValue).collect(Collectors.toList());
    }

    @LogAction("获取排除格式")
    public List<String> getExcludedFormats(Long userId) {
        return preferenceRepository.query()
                .where(UserBookPreference::getUserId, eq(userId))
                .and(UserBookPreference::getCategory, eq("FORMAT"))
                .and(UserBookPreference::getType, eq("EXCLUDE"))
                .list().stream().map(UserBookPreference::getValue).collect(Collectors.toList());
    }

    @LogAction("获取所有偏好")
    public List<UserBookPreference> getAllPreferences(Long userId) {
        return preferenceRepository.query()
                .where(UserBookPreference::getUserId, eq(userId))
                .list();
    }

    @LogAction("添加喜欢偏好")
    @Transactional
    public UserBookPreference addIncludePreference(Long userId, String category, String value) {
        var existing = preferenceRepository.query()
                .where(UserBookPreference::getUserId, eq(userId))
                .and(UserBookPreference::getCategory, eq(category.toUpperCase()))
                .and(UserBookPreference::getValue, eq(value))
                .list(1).stream().findFirst();
        if (existing.isPresent()) {
            UserBookPreference pref = existing.get();
            pref.setType("INCLUDE");
            UserBookPreference saved = preferenceRepository.save(pref);
            recommendService.clearUserCache(userId);
            recommendService.asyncRecompute(userId);
            return saved;
        }
        UserBookPreference pref = UserBookPreference.builder()
                .userId(userId).category(category.toUpperCase()).value(value).type("INCLUDE").build();
        UserBookPreference saved = preferenceRepository.save(pref);
        recommendService.clearUserCache(userId);
        recommendService.asyncRecompute(userId);
        log.info("用户添加喜欢偏好: userId={}, category={}, value={}", userId, category, value);
        return saved;
    }

    @LogAction("取消喜欢偏好")
    @Transactional
    public boolean removeIncludePreference(Long userId, String category, String value) {
        var existing = preferenceRepository.query()
                .where(UserBookPreference::getUserId, eq(userId))
                .and(UserBookPreference::getCategory, eq(category.toUpperCase()))
                .and(UserBookPreference::getValue, eq(value))
                .and(UserBookPreference::getType, eq("INCLUDE"))
                .list(1).stream().findFirst();
        if (existing.isPresent()) {
            preferenceRepository.deleteById(existing.get().getId());
            recommendService.clearUserCache(userId);
            recommendService.asyncRecompute(userId);
            return true;
        }
        return false;
    }

    @LogAction("获取喜欢偏好")
    public List<UserBookPreference> getIncludePreferences(Long userId) {
        return preferenceRepository.query()
                .where(UserBookPreference::getUserId, eq(userId))
                .and(UserBookPreference::getType, eq("INCLUDE"))
                .list();
    }

    @LogAction("获取喜欢标签")
    public List<String> getIncludedTags(Long userId) {
        return preferenceRepository.query()
                .where(UserBookPreference::getUserId, eq(userId))
                .and(UserBookPreference::getCategory, eq("TAG"))
                .and(UserBookPreference::getType, eq("INCLUDE"))
                .list().stream().map(UserBookPreference::getValue).collect(Collectors.toList());
    }

    @LogAction("获取喜欢作者")
    public List<String> getIncludedAuthors(Long userId) {
        return preferenceRepository.query()
                .where(UserBookPreference::getUserId, eq(userId))
                .and(UserBookPreference::getCategory, eq("AUTHOR"))
                .and(UserBookPreference::getType, eq("INCLUDE"))
                .list().stream().map(UserBookPreference::getValue).collect(Collectors.toList());
    }

    @LogAction("获取喜欢格式")
    public List<String> getIncludedFormats(Long userId) {
        return preferenceRepository.query()
                .where(UserBookPreference::getUserId, eq(userId))
                .and(UserBookPreference::getCategory, eq("FORMAT"))
                .and(UserBookPreference::getType, eq("INCLUDE"))
                .list().stream().map(UserBookPreference::getValue).collect(Collectors.toList());
    }
}
