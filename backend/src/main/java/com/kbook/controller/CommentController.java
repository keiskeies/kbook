package com.kbook.controller;

import com.kbook.common.api.PageResult;
import com.kbook.common.api.Result;
import com.kbook.dto.CommentVO;
import com.kbook.entity.Book;
import com.kbook.entity.User;
import com.kbook.repository.BookRepository;
import com.kbook.repository.UserRepository;
import com.kbook.service.CommentService;
import com.kbook.dto.CreateCommentRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评论控制器
 */
@RestController
@RequestMapping("/api/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;
    private final UserRepository userRepository;
    private final BookRepository bookRepository;

    /** 发表评论 */
    @PostMapping
    public Result<CommentVO> createComment(Authentication auth, @Valid @RequestBody CreateCommentRequest req) {
        Long userId = (Long) auth.getPrincipal();
        CommentVO vo = commentService.createComment(userId, req.getBookId(), req.getChapterId(), req.getParentId(), req.getContent());
        fillUserInfo(List.of(vo));
        return Result.ok(vo);
    }

    /** 删除评论 */
    @DeleteMapping("/{id}")
    public Result<Void> deleteComment(Authentication auth, @PathVariable Long id) {
        Long userId = (Long) auth.getPrincipal();
        commentService.deleteComment(id, userId);
        return Result.ok();
    }

    /** 获取书籍评论 */
    @GetMapping("/book/{bookId}")
    public Result<PageResult<CommentVO>> getBookComments(
            @PathVariable Long bookId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        Long currentUserId = auth != null ? (Long) auth.getPrincipal() : null;
        PageResult<CommentVO> result = commentService.getBookComments(bookId, page, size, currentUserId);
        fillUserInfo(result.getList());
        return Result.ok(result);
    }

    /** 获取章节评论 */
    @GetMapping("/chapter/{bookId}")
    public Result<PageResult<CommentVO>> getChapterComments(
            @PathVariable Long bookId,
            @RequestParam String chapterId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        Long currentUserId = auth != null ? (Long) auth.getPrincipal() : null;
        PageResult<CommentVO> result = commentService.getChapterComments(bookId, chapterId, page, size, currentUserId);
        fillUserInfo(result.getList());
        return Result.ok(result);
    }

    /** 获取评论回复 */
    @GetMapping("/{commentId}/replies")
    public Result<List<CommentVO>> getReplies(@PathVariable Long commentId, Authentication auth) {
        Long currentUserId = auth != null ? (Long) auth.getPrincipal() : null;
        List<CommentVO> replies = commentService.getReplies(commentId, currentUserId);
        fillUserInfo(replies);
        return Result.ok(replies);
    }

    /** 高分书评 */
    @GetMapping("/top-rated")
    public Result<PageResult<CommentVO>> getTopRatedComments(
            @RequestParam(defaultValue = "1") int minLikes,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        Long currentUserId = auth != null ? (Long) auth.getPrincipal() : null;
        PageResult<CommentVO> result = commentService.getTopRatedComments(minLikes, page, size, currentUserId);
        fillUserInfo(result.getList());
        return Result.ok(result);
    }

    /** 点赞 */
    @PostMapping("/{id}/like")
    public Result<Void> likeComment(Authentication auth, @PathVariable Long id) {
        Long userId = (Long) auth.getPrincipal();
        commentService.likeComment(id, userId);
        return Result.ok();
    }

    /** 取消点赞 */
    @DeleteMapping("/{id}/like")
    public Result<Void> unlikeComment(Authentication auth, @PathVariable Long id) {
        Long userId = (Long) auth.getPrincipal();
        commentService.unlikeComment(id, userId);
        return Result.ok();
    }

    /** 收藏 */
    @PostMapping("/{id}/favorite")
    public Result<Void> favoriteComment(Authentication auth, @PathVariable Long id) {
        Long userId = (Long) auth.getPrincipal();
        commentService.favoriteComment(id, userId);
        return Result.ok();
    }

    /** 取消收藏 */
    @DeleteMapping("/{id}/favorite")
    public Result<Void> unfavoriteComment(Authentication auth, @PathVariable Long id) {
        Long userId = (Long) auth.getPrincipal();
        commentService.unfavoriteComment(id, userId);
        return Result.ok();
    }

    /** 统计书籍评论数 */
    @GetMapping("/count/book/{bookId}")
    public Result<Long> countBookComments(@PathVariable Long bookId) {
        return Result.ok(commentService.countBookComments(bookId));
    }

    // ==================== 辅助方法 ====================

    /**
     * 填充评论 VO 中的用户昵称/头像和图书标题/封面信息
     * @param comments 评论 VO 列表
     */
    private void fillUserInfo(List<CommentVO> comments) {
        if (comments == null || comments.isEmpty()) return;
        // 用户信息
        List<Long> userIds = comments.stream().map(CommentVO::getUserId).distinct().toList();
        Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        // 图书信息
        List<Long> bookIds = comments.stream().map(CommentVO::getBookId).distinct().toList();
        Map<Long, Book> bookMap = bookRepository.findAllById(bookIds).stream()
                .collect(Collectors.toMap(Book::getId, b -> b));

        for (CommentVO vo : comments) {
            User user = userMap.get(vo.getUserId());
            if (user != null) {
                vo.setUserNickname(user.getNickname());
                vo.setUserAvatar(user.getAvatar());
            }
            Book book = bookMap.get(vo.getBookId());
            if (book != null) {
                vo.setBookTitle(book.getTitle());
                vo.setBookCoverUrl(book.getCoverUrl());
            }
        }
    }

}
