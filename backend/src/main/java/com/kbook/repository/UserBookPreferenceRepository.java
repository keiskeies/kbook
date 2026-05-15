package com.kbook.repository;

import com.kbook.entity.UserBookPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 用户书籍偏好数据访问层
 */
public interface UserBookPreferenceRepository extends JpaRepository<UserBookPreference, Long> {

    /**
     * 查询用户的所有排除偏好
     */
    List<UserBookPreference> findByUserIdAndType(Long userId, String type);

    /**
     * 查询用户指定类别的排除偏好
     */
    List<UserBookPreference> findByUserIdAndCategoryAndType(Long userId, String category, String type);

    /**
     * 查询用户特定偏好（用于检查是否已存在）
     */
    Optional<UserBookPreference> findByUserIdAndCategoryAndValue(Long userId, String category, String value);

    /**
     * 查询用户特定偏好（按 type 过滤）
     */
    Optional<UserBookPreference> findByUserIdAndCategoryAndValueAndType(Long userId, String category, String value, String type);

    /**
     * 查询用户所有偏好
     */
    List<UserBookPreference> findByUserId(Long userId);
}
