package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.entity.User;
import com.kbook.repository.UserRepository;
import com.kbook.service.UserFollowService;
import com.kbook.dto.FollowUserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户关注控制器
 */
@RestController
@RequestMapping("/api/follow")
@RequiredArgsConstructor
public class UserFollowController {

    private final UserFollowService userFollowService;
    private final UserRepository userRepository;

    /** 关注用户 */
    @PostMapping("/{userId}")
    public Result<Void> follow(Authentication auth, @PathVariable Long userId) {
        Long currentUserId = (Long) auth.getPrincipal();
        userFollowService.followUser(currentUserId, userId);
        return Result.ok();
    }

    /** 取消关注 */
    @DeleteMapping("/{userId}")
    public Result<Void> unfollow(Authentication auth, @PathVariable Long userId) {
        Long currentUserId = (Long) auth.getPrincipal();
        userFollowService.unfollowUser(currentUserId, userId);
        return Result.ok();
    }

    /** 是否已关注 */
    @GetMapping("/is-following/{userId}")
    public Result<Boolean> isFollowing(Authentication auth, @PathVariable Long userId) {
        Long currentUserId = (Long) auth.getPrincipal();
        return Result.ok(userFollowService.isFollowing(currentUserId, userId));
    }

    /** 获取关注列表 */
    @GetMapping("/{userId}/followings")
    public Result<List<FollowUserVO>> getFollowings(@PathVariable Long userId) {
        return Result.ok(toFollowUserVOs(userFollowService.getFollowings(userId)));
    }

    /** 获取粉丝列表 */
    @GetMapping("/{userId}/followers")
    public Result<List<FollowUserVO>> getFollowers(@PathVariable Long userId) {
        return Result.ok(toFollowUserVOs(userFollowService.getFollowers(userId)));
    }

    // ==================== VO ====================

    private List<FollowUserVO> toFollowUserVOs(List<com.kbook.entity.UserFollow> follows) {
        // followings: followingId 是被关注的人; followers: followerId 是粉丝
        List<Long> userIds = follows.stream()
                .map(f -> f.getFollowingId() != null ? f.getFollowingId() : f.getFollowerId())
                .distinct().toList();
        Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        return follows.stream().map(f -> {
            // 对于 followings 列表，展示 followingId 的信息
            // 对于 followers 列表，展示 followerId 的信息
            Long targetUserId = f.getFollowingId();
            FollowUserVO vo = new FollowUserVO();
            vo.setUserId(targetUserId);
            User user = userMap.get(targetUserId);
            if (user != null) {
                vo.setNickname(user.getNickname());
                vo.setAvatar(user.getAvatar());
                vo.setBio(user.getBio());
            }
            return vo;
        }).toList();
    }
}
