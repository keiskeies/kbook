package com.kbook.repository;

import com.kbook.entity.UserFollow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 用户关注数据访问层
 */
public interface UserFollowRepository extends JpaRepository<UserFollow, Long> {

    Optional<UserFollow> findByFollowerIdAndFollowingId(Long followerId, Long followingId);

    boolean existsByFollowerIdAndFollowingId(Long followerId, Long followingId);

    void deleteByFollowerIdAndFollowingId(Long followerId, Long followingId);

    /** 查询用户的关注列表（我关注了谁） */
    @Query("SELECT uf FROM UserFollow uf WHERE uf.followerId = :userId ORDER BY uf.createdAt DESC")
    List<UserFollow> findFollowings(@Param("userId") Long userId);

    /** 查询用户的粉丝列表（谁关注了我） */
    @Query("SELECT uf FROM UserFollow uf WHERE uf.followingId = :userId ORDER BY uf.createdAt DESC")
    List<UserFollow> findFollowers(@Param("userId") Long userId);

    long countByFollowerId(Long followerId);

    long countByFollowingId(Long followingId);

    /** 查询我关注的人的ID列表 */
    @Query("SELECT uf.followingId FROM UserFollow uf WHERE uf.followerId = :userId")
    List<Long> findFollowingIds(@Param("userId") Long userId);
}
