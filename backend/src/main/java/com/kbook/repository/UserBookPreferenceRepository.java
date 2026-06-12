package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.UserBookPreference;

/**
 * 用户书籍偏好数据访问层
 * <p>
 * 查询统一使用 BaseRepository.query() 的 Fluent API
 */
public interface UserBookPreferenceRepository extends BaseRepository<UserBookPreference, Long> {
}
