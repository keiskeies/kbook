package com.kbook.service;

import com.kbook.common.exception.BusinessException;
import com.kbook.common.service.AbstractMiddleServiceImpl;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.entity.User;
import com.kbook.entity.UserFollow;
import com.kbook.repository.UserFollowRepository;
import com.kbook.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户关注服务
 * <p>
 * 管理用户之间的关注/取关操作，同步更新关注数和粉丝数计数器。
 */
@Slf4j
@Service
@LogModule("关注")
public class UserFollowService extends AbstractMiddleServiceImpl<UserFollow, Long, Long> {

    /** 用户关注数据仓库 */
    @Autowired
    private UserFollowRepository userFollowRepository;

    /** 用户数据仓库 */
    @Autowired
    private UserRepository userRepository;

    /** 关注用户 */
    @Transactional
    @LogAction("关注用户")
    public void followUser(Long followerId, Long followingId) {
        if (followerId.equals(followingId)) {
            throw new BusinessException("不能关注自己");
        }
        if (!userRepository.existsById(followingId)) {
            throw new BusinessException("用户不存在");
        }
        if (middleService.exist(followerId, followingId)) {
            throw new BusinessException("已经关注了该用户");
        }

        UserFollow follow = UserFollow.builder()
                .followerId(followerId)
                .followingId(followingId)
                .build();
        middleService.saveOne(follow);

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
    @LogAction("取消关注")
    public void unfollowUser(Long followerId, Long followingId) {
        if (!middleService.exist(followerId, followingId)) {
            throw new BusinessException("尚未关注该用户");
        }

        UserFollow follow = middleService.findOneById(followerId, followingId);
        if (follow != null) {
            middleService.deleteOneById(follow.getId());
        }

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
    @LogAction("检查关注状态")
    public boolean isFollowing(Long followerId, Long followingId) {
        return middleService.exist(followerId, followingId);
    }

    /** 获取用户的关注ID列表 */
    @LogAction("获取关注列表ID")
    public List<Long> getFollowingIds(Long userId) {
        return userFollowRepository.findFollowingIds(userId);
    }

    /** 获取粉丝列表 */
    @LogAction("获取粉丝列表")
    public List<UserFollow> getFollowers(Long userId) {
        return userFollowRepository.findFollowers(userId);
    }

    /** 获取关注列表 */
    @LogAction("获取关注列表")
    public List<UserFollow> getFollowings(Long userId) {
        return userFollowRepository.findFollowings(userId);
    }
}
