package com.kbook.controller;

import com.kbook.common.api.PageResult;
import com.kbook.common.api.Result;
import com.kbook.common.exception.BusinessException;
import com.kbook.dto.CommentVO;
import com.kbook.entity.ReadingProgress;
import com.kbook.entity.User;
import com.kbook.repository.BookRepository;
import com.kbook.repository.ReadingProgressRepository;
import com.kbook.repository.UserRepository;
import com.kbook.service.CommentService;
import com.kbook.service.UserFollowService;
import com.kbook.service.UserService;
import com.kbook.dto.UpdateBioRequest;
import com.kbook.dto.UserBookItem;
import com.kbook.dto.UserBooksVO;
import com.kbook.dto.UserProfileVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户主页控制器
 */
@RestController
@RequestMapping("/api/user-profile")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserRepository userRepository;
    private final ReadingProgressRepository readingProgressRepository;
    private final BookRepository bookRepository;
    private final CommentService commentService;
    private final UserFollowService userFollowService;
    private final UserService userService;

    /** 获取用户主页信息 */
    @GetMapping("/{userId}")
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

        // 计算年龄
        if (user.getBirthday() != null) {
            vo.setAge(Period.between(user.getBirthday(), LocalDate.now()).getYears());
        }
        vo.setGender(user.getGender());
        vo.setMbti(user.getMbti());

        // 是否已关注（当前用户视角）
        if (currentUserId != null && !currentUserId.equals(userId)) {
            vo.setIsFollowing(userFollowService.isFollowing(currentUserId, userId));
        }

        // 阅读统计
        long completedCount = readingProgressRepository.countCompletedByUserId(userId);
        List<ReadingProgress> recentReading = readingProgressRepository.findRecentReading(userId, org.springframework.data.domain.PageRequest.of(0, 100));
        vo.setCompletedBooks((int) completedCount);
        vo.setReadingBooks(recentReading.size());

        return Result.ok(vo);
    }

    /** 获取用户在读/已读书籍 */
    @GetMapping("/{userId}/books")
    public Result<UserBooksVO> getUserBooks(@PathVariable Long userId) {
        UserBooksVO vo = new UserBooksVO();

        // 已读完
        List<ReadingProgress> completed = readingProgressRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .filter(rp -> rp.getProgress() >= 1.0)
                .toList();
        vo.setCompletedBooks(completed.stream().map(this::toBookItem).toList());

        // 在读
        List<ReadingProgress> reading = readingProgressRepository.findRecentReading(userId, org.springframework.data.domain.PageRequest.of(0, 50));
        vo.setReadingBooks(reading.stream().map(this::toBookItem).toList());

        return Result.ok(vo);
    }

    /** 获取用户书评 */
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

    /** 更新个人简介 */
    @PutMapping("/bio")
    public Result<User> updateBio(Authentication auth, @RequestBody UpdateBioRequest req) {
        Long userId = (Long) auth.getPrincipal();
        User user = userService.getUserById(userId);
        user.setBio(req.getBio());
        userRepository.save(user);
        return Result.ok(user);
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
        // 用户信息
        List<Long> userIds = comments.stream().map(CommentVO::getUserId).distinct().toList();
        Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        // 图书信息
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
