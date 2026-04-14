package com.kbook.service;

import com.kbook.common.exception.BusinessException;
import com.kbook.entity.User;
import com.kbook.entity.UserFollow;
import com.kbook.repository.UserFollowRepository;
import com.kbook.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户关注服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserFollowService {

    private final UserFollowRepository userFollowRepository;
    private final UserRepository userRepository;

    /** 关注用户 */
    @Transactional
    public void followUser(Long followerId, Long followingId) {
        if (followerId.equals(followingId)) {
            throw new BusinessException("不能关注自己");
        }
        if (!userRepository.existsById(followingId)) {
            throw new BusinessException("用户不存在");
        }
        if (userFollowRepository.existsByFollowerIdAndFollowingId(followerId, followingId)) {
            throw new BusinessException("已经关注了该用户");
        }

        userFollowRepository.save(UserFollow.builder()
                .followerId(followerId)
                .followingId(followingId)
                .build());

        // 更新计数（处理 null 值）
        User follower = userRepository.findById(followerId).orElseThrow();
        User following = userRepository.findById(followingId).orElseThrow();
        follower.setFollowingCount((follower.getFollowingCount() == null ? 0 : follower.getFollowingCount()) + 1);
        following.setFollowerCount((following.getFollowerCount() == null ? 0 : following.getFollowerCount()) + 1);
        userRepository.save(follower);
        userRepository.save(following);

        log.info("用户关注: follower={}, following={}", followerId, followingId);
    }

    /** 取消关注 */
    @Transactional
    public void unfollowUser(Long followerId, Long followingId) {
        if (!userFollowRepository.existsByFollowerIdAndFollowingId(followerId, followingId)) {
            throw new BusinessException("尚未关注该用户");
        }

        userFollowRepository.deleteByFollowerIdAndFollowingId(followerId, followingId);

        // 更新计数（处理 null 值）
        User follower = userRepository.findById(followerId).orElseThrow();
        User following = userRepository.findById(followingId).orElseThrow();
        follower.setFollowingCount(Math.max(0, (follower.getFollowingCount() == null ? 0 : follower.getFollowingCount()) - 1));
        following.setFollowerCount(Math.max(0, (following.getFollowerCount() == null ? 0 : following.getFollowerCount()) - 1));
        userRepository.save(follower);
        userRepository.save(following);

        log.info("取消关注: follower={}, following={}", followerId, followingId);
    }

    /** 是否已关注 */
    public boolean isFollowing(Long followerId, Long followingId) {
        return userFollowRepository.existsByFollowerIdAndFollowingId(followerId, followingId);
    }

    /** 获取用户的关注ID列表 */
    public List<Long> getFollowingIds(Long userId) {
        return userFollowRepository.findFollowingIds(userId);
    }

    /** 获取粉丝列表 */
    public List<UserFollow> getFollowers(Long userId) {
        return userFollowRepository.findFollowers(userId);
    }

    /** 获取关注列表 */
    public List<UserFollow> getFollowings(Long userId) {
        return userFollowRepository.findFollowings(userId);
    }
}
