package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.UserBehaviorProfile;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 用户行为画像数据访问层。
 */
public interface UserBehaviorProfileRepository extends BaseRepository<UserBehaviorProfile, Long> {

    Optional<UserBehaviorProfile> findByUserId(Long userId);

    /**
     * 找出超过指定小时未抽取的用户画像（用于定时补偿抽取）。
     * 仅返回最近活跃过的用户（基于 updatedAt）。
     */
    @Query("SELECT p FROM UserBehaviorProfile p " +
           "WHERE p.lastInferredAt IS NULL " +
           "OR p.lastInferredAt < :threshold")
    List<UserBehaviorProfile> findStaleProfiles(@Param("threshold") java.time.LocalDateTime threshold);
}
