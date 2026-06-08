package com.kbook.controller;

import com.kbook.common.api.PageResult;
import com.kbook.common.api.Result;
import com.kbook.common.exception.BusinessException;
import com.kbook.common.util.CommonUtils;
import com.kbook.config.properties.BookStorageProperties;
import com.kbook.dto.comment.CommentVO;
import com.kbook.dto.user.FollowUserVO;
import com.kbook.dto.user.UpdateBioRequest;
import com.kbook.dto.user.UpdateTraitsRequest;
import com.kbook.dto.book.UserBookItem;
import com.kbook.dto.book.UserBooksVO;
import com.kbook.dto.user.UserProfileVO;
import com.kbook.entity.ReadingProgress;
import com.kbook.entity.User;
import com.kbook.entity.UserBookPreference;
import com.kbook.repository.BookRepository;
import com.kbook.repository.ReadingProgressRepository;
import com.kbook.repository.UserRepository;
import com.kbook.service.comment.CommentService;
import com.kbook.service.user.UserBookPreferenceService;
import com.kbook.service.user.UserFollowService;
import com.kbook.service.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Tag(name = "用户")
public class UserController {

    private final UserService userService;
    private final BookStorageProperties storageProps;
    private final UserFollowService userFollowService;
    private final UserRepository userRepository;
    private final ReadingProgressRepository readingProgressRepository;
    private final BookRepository bookRepository;
    private final CommentService commentService;
    private final UserBookPreferenceService preferenceService;

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/me")
    public Result<User> getCurrentUser(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.getUserById(userId));
    }

    @Operation(summary = "更新用户资料")
    @PutMapping("/profile")
    public Result<User> updateProfile(Authentication authentication,
                                       @RequestParam(required = false) String nickname,
                                       @RequestParam(required = false) String avatar,
                                       @RequestParam(required = false) String bio) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.updateProfile(userId, nickname, avatar, bio));
    }

    @Operation(summary = "更新用户特征")
    @PutMapping("/profile/traits")
    public Result<User> updateTraits(Authentication authentication,
                                      @RequestBody UpdateTraitsRequest req) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.updateTraits(userId, req.getBirthday(), req.getGender(),
                req.getMarried(), req.getHasChildren(), req.getChildrenAgeRanges(),
                req.getMbti(), req.getOccupation(),
                req.getAspirationEducation(), req.getEntrepreneurship(), req.getAspirationIncome()));
    }

    @Operation(summary = "更新心情")
    @PutMapping("/profile/mood")
    public Result<User> updateMood(Authentication authentication,
                                    @RequestParam(required = false) String mood) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.updateMood(userId, mood));
    }

    @Operation(summary = "更新图书对话风格")
    @PutMapping("/profile/book-chat-style")
    public Result<User> updateBookChatStyle(Authentication authentication,
                                             @RequestParam(required = false) String style) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.updateBookChatStyle(userId, style));
    }

    @Operation(summary = "上传头像")
    @PostMapping("/avatar")
    public Result<User> uploadAvatar(Authentication authentication,
                                      @RequestParam("file") MultipartFile file) {
        Long userId = (Long) authentication.getPrincipal();
        return Result.ok(userService.uploadAvatar(userId, file));
    }

    @Operation(summary = "获取头像")
    @GetMapping("/avatar/{filename:.+}")
    public ResponseEntity<Resource> getAvatar(@PathVariable String filename) {
        Path avatarDir = Paths.get(storageProps.getUpload().getAvatarDir());
        Path imagePath = CommonUtils.safeResolvePath(avatarDir, filename);

        if (imagePath == null || !Files.exists(imagePath)) {
            return ResponseEntity.notFound().build();
        }

        return CommonUtils.buildImageResponse(imagePath, filename);
    }

    @Operation(summary = "关注用户")
    @PostMapping("/follow/{userId}")
    public Result<Void> follow(Authentication auth, @PathVariable Long userId) {
        Long currentUserId = (Long) auth.getPrincipal();
        userFollowService.followUser(currentUserId, userId);
        return Result.ok();
    }

    @Operation(summary = "取关用户")
    @DeleteMapping("/follow/{userId}")
    public Result<Void> unfollow(Authentication auth, @PathVariable Long userId) {
        Long currentUserId = (Long) auth.getPrincipal();
        userFollowService.unfollowUser(currentUserId, userId);
        return Result.ok();
    }

    @Operation(summary = "检查是否关注")
    @GetMapping("/follow/is-following/{userId}")
    public Result<Boolean> isFollowing(Authentication auth, @PathVariable Long userId) {
        Long currentUserId = (Long) auth.getPrincipal();
        return Result.ok(userFollowService.isFollowing(currentUserId, userId));
    }

    @Operation(summary = "获取关注列表")
    @GetMapping("/{userId}/followings")
    public Result<List<FollowUserVO>> getFollowings(@PathVariable Long userId) {
        return Result.ok(toFollowUserVOs(userFollowService.getFollowings(userId), true));
    }

    @Operation(summary = "获取粉丝列表")
    @GetMapping("/{userId}/followers")
    public Result<List<FollowUserVO>> getFollowers(@PathVariable Long userId) {
        return Result.ok(toFollowUserVOs(userFollowService.getFollowers(userId), false));
    }

    @Operation(summary = "获取用户主页")
    @GetMapping("/{userId}/profile")
    public Result<UserProfileVO> getUserProfile(@PathVariable Long userId, Authentication auth) {
        Long currentUserId = auth != null ? (Long) auth.getPrincipal() : null;
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));

        UserProfileVO vo = new UserProfileVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setBio(user.getBio());
        vo.setMood(user.getMood());
        vo.setFollowerCount(user.getFollowerCount());
        vo.setFollowingCount(user.getFollowingCount());

        if (user.getBirthday() != null) {
            vo.setAge(Period.between(user.getBirthday(), LocalDate.now()).getYears());
        }
        vo.setGender(user.getGender());
        vo.setMbti(user.getMbti());

        if (currentUserId != null && !currentUserId.equals(userId)) {
            vo.setIsFollowing(userFollowService.isFollowing(currentUserId, userId));
        }

        long completedCount = readingProgressRepository.countCompletedByUserId(userId);
        List<ReadingProgress> recentReading = readingProgressRepository.findRecentReading(userId, org.springframework.data.domain.PageRequest.of(0, 100));
        vo.setCompletedBooks((int) completedCount);
        vo.setReadingBooks(recentReading.size());

        return Result.ok(vo);
    }

    @Operation(summary = "获取用户图书")
    @GetMapping("/{userId}/books")
    public Result<UserBooksVO> getUserBooks(@PathVariable Long userId) {
        UserBooksVO vo = new UserBooksVO();

        List<ReadingProgress> completed = readingProgressRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .filter(rp -> rp.getProgress() >= 1.0)
                .toList();
        vo.setCompletedBooks(completed.stream().map(this::toBookItem).toList());

        List<ReadingProgress> reading = readingProgressRepository.findRecentReading(userId, org.springframework.data.domain.PageRequest.of(0, 50));
        vo.setReadingBooks(reading.stream().map(this::toBookItem).toList());

        return Result.ok(vo);
    }

    @Operation(summary = "获取用户评论")
    @GetMapping("/{userId}/comments")
    public Result<PageResult<CommentVO>> getUserComments(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            Authentication auth) {
        Long currentUserId = auth != null ? (Long) auth.getPrincipal() : null;
        PageResult<CommentVO> result = commentService.getUserComments(userId, page, size, currentUserId);
        fillCommentUserInfo(result.getList());
        return Result.ok(result);
    }

    @Operation(summary = "更新个人简介")
    @PutMapping("/profile/bio")
    public Result<User> updateBio(Authentication auth, @RequestBody UpdateBioRequest req) {
        Long userId = (Long) auth.getPrincipal();
        User user = userService.getUserById(userId);
        user.setBio(req.getBio());
        userRepository.save(user);
        return Result.ok(user);
    }

    @Operation(summary = "添加排除偏好")
    @PostMapping("/preferences/exclude")
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

    @Operation(summary = "移除排除偏好")
    @DeleteMapping("/preferences/exclude")
    public Result<Void> removeExcludePreference(
            Authentication auth,
            @RequestParam String category,
            @RequestParam String value) {
        Long userId = Long.parseLong(auth.getName());
        return preferenceService.removeExcludePreference(userId, category, value)
                ? Result.ok(null) : Result.fail("偏好不存在");
    }

    @Operation(summary = "获取排除偏好")
    @GetMapping("/preferences/exclude")
    public Result<List<UserBookPreference>> getExcludePreferences(Authentication auth) {
        Long userId = Long.parseLong(auth.getName());
        return Result.ok(preferenceService.getExcludePreferences(userId));
    }

    @Operation(summary = "添加包含偏好")
    @PostMapping("/preferences/include")
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

    @Operation(summary = "移除包含偏好")
    @DeleteMapping("/preferences/include")
    public Result<Void> removeIncludePreference(
            Authentication auth,
            @RequestParam String category,
            @RequestParam String value) {
        Long userId = Long.parseLong(auth.getName());
        return preferenceService.removeIncludePreference(userId, category, value)
                ? Result.ok(null) : Result.fail("偏好不存在");
    }

    @Operation(summary = "获取包含偏好")
    @GetMapping("/preferences/include")
    public Result<List<UserBookPreference>> getIncludePreferences(Authentication auth) {
        Long userId = Long.parseLong(auth.getName());
        return Result.ok(preferenceService.getIncludePreferences(userId));
    }

    @Operation(summary = "获取全部偏好")
    @GetMapping("/preferences")
    public Result<List<UserBookPreference>> getAllPreferences(Authentication auth) {
        Long userId = Long.parseLong(auth.getName());
        return Result.ok(preferenceService.getAllPreferences(userId));
    }

    private List<FollowUserVO> toFollowUserVOs(List<com.kbook.entity.UserFollow> follows, boolean isFollowings) {
        List<Long> userIds = follows.stream()
                .map(f -> isFollowings ? f.getFollowingId() : f.getFollowerId())
                .distinct().toList();
        Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        return follows.stream().map(f -> {
            Long targetUserId = isFollowings ? f.getFollowingId() : f.getFollowerId();
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

    private UserBookItem toBookItem(ReadingProgress rp) {
        UserBookItem item = new UserBookItem();
        item.setBookId(rp.getBookId());
        item.setProgress(rp.getProgress());
        bookRepository.findById(rp.getBookId()).ifPresent(book -> {
            item.setTitle(book.getTitle());
            item.setAuthor(book.getAuthor());
            item.setCoverUrl(book.getCoverUrl());
            item.setFormat(book.getFormat());
        });
        return item;
    }

    private void fillCommentUserInfo(List<CommentVO> comments) {
        if (comments == null || comments.isEmpty()) return;
        List<Long> userIds = comments.stream().map(CommentVO::getUserId).distinct().toList();
        Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        List<Long> bookIds = comments.stream().map(CommentVO::getBookId).distinct().toList();
        Map<Long, com.kbook.entity.Book> bookMap = bookRepository.findAllById(bookIds).stream()
                .collect(Collectors.toMap(com.kbook.entity.Book::getId, b -> b));
        for (CommentVO vo : comments) {
            User user = userMap.get(vo.getUserId());
            if (user != null) {
                vo.setUserNickname(user.getNickname());
                vo.setUserAvatar(user.getAvatar());
            }
            com.kbook.entity.Book book = bookMap.get(vo.getBookId());
            if (book != null) {
                vo.setBookTitle(book.getTitle());
                vo.setBookCoverUrl(book.getCoverUrl());
            }
        }
    }
}
