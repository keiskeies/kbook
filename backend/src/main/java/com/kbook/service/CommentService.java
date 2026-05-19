package com.kbook.service;

import com.kbook.common.api.PageResult;
import com.kbook.common.exception.BusinessException;
import com.kbook.dto.CommentVO;
import com.kbook.entity.*;
import com.kbook.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 评论服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentRepository commentRepository;
    private final CommentLikeRepository commentLikeRepository;
    private final CommentFavoriteRepository commentFavoriteRepository;
    private final NotificationService notificationService;
    private final EmailNotificationService emailNotificationService;
    private final UserRepository userRepository;
    private final BookRepository bookRepository;

    /** 发表评论 */
    @Transactional
    public CommentVO createComment(Long userId, Long bookId, String chapterId, Long parentId, String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException("评论内容不能为空");
        }
        if (content.length() > 2000) {
            throw new BusinessException("评论内容不能超过2000字");
        }

        // 如果是回复，校验父评论存在
        if (parentId != null) {
            Comment parent = commentRepository.findById(parentId)
                    .orElseThrow(() -> new BusinessException("回复的评论不存在"));
            // 更新父评论回复数
            parent.setReplyCount(parent.getReplyCount() + 1);
            commentRepository.save(parent);

            // 获取回复者信息（提前获取，供后续使用）
            User replier = userRepository.findById(userId).orElse(null);
            Book book = bookRepository.findById(bookId).orElse(null);

            // 通知父评论作者
            if (!parent.getUserId().equals(userId)) {
                notificationService.notifyCommentReply(userId, parent.getUserId(), parent.getId(), bookId);

                // 发送回复邮件通知
                if (replier != null && book != null) {
                    User parentUser = userRepository.findById(parent.getUserId()).orElse(null);
                    if (parentUser != null && parentUser.getEmail() != null) {
                        String preview = content.length() > 50 ? content.substring(0, 50) + "..." : content;
                        emailNotificationService.sendHtmlEmail(
                            parentUser.getEmail(),
                            "【KBook】" + replier.getNickname() + " 回复了你的书评",
                            EmailNotificationService.EmailType.COMMENT_REPLY,
                            java.util.Map.of(
                                "userName", replier.getNickname(),
                                "bookTitle", book.getTitle(),
                                "content", preview
                            )
                        );
                    }
                }
            }

            // 检查回复数是否达到阈值，发送达标邮件
            if (book != null) {
                User parentUser = userRepository.findById(parent.getUserId()).orElse(null);
                if (parentUser != null && parentUser.getEmail() != null) {
                    String preview = parent.getContent().length() > 50
                        ? parent.getContent().substring(0, 50) + "..."
                        : parent.getContent();
                    emailNotificationService.checkAndSendReplyThresholdNotification(
                        parent.getId(),
                        parentUser.getEmail(),
                        replier != null ? replier.getNickname() : "用户",
                        book.getTitle(),
                        preview,
                        parent.getReplyCount()
                    );
                }
            }
        }

        Comment comment = Comment.builder()
                .userId(userId)
                .bookId(bookId)
                .chapterId(chapterId)
                .parentId(parentId)
                .content(content.trim())
                .build();

        Comment saved = commentRepository.save(comment);
        return toVO(saved, userId);
    }

    /** 删除评论 */
    @Transactional
    public void deleteComment(Long commentId, Long userId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException("评论不存在"));
        if (!comment.getUserId().equals(userId)) {
            throw new BusinessException("只能删除自己的评论");
        }

        // 如果有父评论，减少回复数
        if (comment.getParentId() != null) {
            commentRepository.findById(comment.getParentId()).ifPresent(parent -> {
                parent.setReplyCount(Math.max(0, parent.getReplyCount() - 1));
                commentRepository.save(parent);
            });
        }

        // 删除关联的点赞和收藏
        // 批量删除子评论的关联数据（简单处理：子评论也一并删除）
        List<Comment> replies = commentRepository.findByParentIdOrderByCreatedAtAsc(commentId);
        for (Comment reply : replies) {
            commentLikeRepository.deleteByCommentIdAndUserId(reply.getId(), reply.getUserId());
            commentFavoriteRepository.deleteByCommentIdAndUserId(reply.getId(), reply.getUserId());
        }
        commentRepository.deleteAll(replies);

        // 删除本评论的点赞和收藏
        commentLikeRepository.findByCommentIdAndUserId(commentId, userId)
                .ifPresent(cl -> commentLikeRepository.deleteByCommentIdAndUserId(commentId, userId));

        commentRepository.delete(comment);
    }

    /** 获取书籍顶级评论列表 */
    public PageResult<CommentVO> getBookComments(Long bookId, int page, int size, Long currentUserId) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "likeCount").and(Sort.by(Sort.Direction.DESC, "createdAt")));
        Page<Comment> pageData = commentRepository.findBookTopComments(bookId, pageable);
        List<CommentVO> vos = pageData.getContent().stream()
                .map(c -> toVO(c, currentUserId))
                .toList();
        return PageResult.of(vos, pageData.getTotalElements(), page, size);
    }

    /** 获取章节顶级评论列表 */
    public PageResult<CommentVO> getChapterComments(Long bookId, String chapterId, int page, int size, Long currentUserId) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "likeCount").and(Sort.by(Sort.Direction.DESC, "createdAt")));
        Page<Comment> pageData = commentRepository.findChapterTopComments(bookId, chapterId, pageable);
        List<CommentVO> vos = pageData.getContent().stream()
                .map(c -> toVO(c, currentUserId))
                .toList();
        return PageResult.of(vos, pageData.getTotalElements(), page, size);
    }

    /** 获取评论的回复列表 */
    public List<CommentVO> getReplies(Long parentId, Long currentUserId) {
        List<Comment> replies = commentRepository.findByParentIdOrderByCreatedAtAsc(parentId);
        return replies.stream().map(c -> toVO(c, currentUserId)).toList();
    }

    /** 高分书评列表 */
    public PageResult<CommentVO> getTopRatedComments(int minLikes, int page, int size, Long currentUserId) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "likeCount").and(Sort.by(Sort.Direction.DESC, "createdAt")));
        Page<Comment> pageData = commentRepository.findTopRatedComments(minLikes, pageable);
        List<CommentVO> vos = pageData.getContent().stream()
                .map(c -> toVO(c, currentUserId))
                .toList();
        return PageResult.of(vos, pageData.getTotalElements(), page, size);
    }

    /** 点赞评论 */
    @Transactional
    public void likeComment(Long commentId, Long userId) {
        if (commentLikeRepository.existsByCommentIdAndUserId(commentId, userId)) {
            throw new BusinessException("已经点赞过了");
        }
        commentLikeRepository.save(CommentLike.builder().commentId(commentId).userId(userId).build());

        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException("评论不存在"));
        int newLikeCount = comment.getLikeCount() + 1;
        comment.setLikeCount(newLikeCount);
        commentRepository.save(comment);

        // 通知评论作者
        if (!comment.getUserId().equals(userId)) {
            notificationService.notifyCommentLiked(userId, comment.getUserId(), commentId, comment.getBookId());

            // 发送点赞邮件通知
            User liker = userRepository.findById(userId).orElse(null);
            Book book = bookRepository.findById(comment.getBookId()).orElse(null);
            User commentOwner = userRepository.findById(comment.getUserId()).orElse(null);
            if (liker != null && book != null && commentOwner != null && commentOwner.getEmail() != null) {
                String preview = comment.getContent().length() > 50
                    ? comment.getContent().substring(0, 50) + "..."
                    : comment.getContent();
                emailNotificationService.sendHtmlEmail(
                    commentOwner.getEmail(),
                    "【KBook】" + liker.getNickname() + " 赞了你的书评",
                    EmailNotificationService.EmailType.COMMENT_LIKE,
                    java.util.Map.of(
                        "userName", liker.getNickname(),
                        "bookTitle", book.getTitle(),
                        "content", preview,
                        "count", newLikeCount,
                        "actionText", "查看书评"
                    )
                );
            }
        }

        // 检查点赞数是否达到阈值
        User commentOwner = userRepository.findById(comment.getUserId()).orElse(null);
        Book book = bookRepository.findById(comment.getBookId()).orElse(null);
        if (commentOwner != null && commentOwner.getEmail() != null && book != null) {
            String preview = comment.getContent().length() > 50
                ? comment.getContent().substring(0, 50) + "..."
                : comment.getContent();
            User liker = userRepository.findById(userId).orElse(null);
            emailNotificationService.checkAndSendLikeThresholdNotification(
                comment.getId(),
                commentOwner.getEmail(),
                liker != null ? liker.getNickname() : "用户",
                book.getTitle(),
                preview,
                newLikeCount
            );
        }
    }

    /** 取消点赞 */
    @Transactional
    public void unlikeComment(Long commentId, Long userId) {
        if (!commentLikeRepository.existsByCommentIdAndUserId(commentId, userId)) {
            throw new BusinessException("尚未点赞");
        }
        commentLikeRepository.deleteByCommentIdAndUserId(commentId, userId);

        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException("评论不存在"));
        comment.setLikeCount(Math.max(0, comment.getLikeCount() - 1));
        commentRepository.save(comment);
    }

    /** 收藏评论 */
    @Transactional
    public void favoriteComment(Long commentId, Long userId) {
        if (commentFavoriteRepository.existsByCommentIdAndUserId(commentId, userId)) {
            throw new BusinessException("已经收藏过了");
        }
        commentFavoriteRepository.save(CommentFavorite.builder().commentId(commentId).userId(userId).build());

        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException("评论不存在"));
        comment.setFavoriteCount(comment.getFavoriteCount() + 1);
        commentRepository.save(comment);

        // 通知评论作者
        if (!comment.getUserId().equals(userId)) {
            notificationService.notifyCommentFavorited(userId, comment.getUserId(), commentId, comment.getBookId());
        }
    }

    /** 取消收藏 */
    @Transactional
    public void unfavoriteComment(Long commentId, Long userId) {
        if (!commentFavoriteRepository.existsByCommentIdAndUserId(commentId, userId)) {
            throw new BusinessException("尚未收藏");
        }
        commentFavoriteRepository.deleteByCommentIdAndUserId(commentId, userId);

        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException("评论不存在"));
        comment.setFavoriteCount(Math.max(0, comment.getFavoriteCount() - 1));
        commentRepository.save(comment);
    }

    /** 查询用户的评论列表 */
    public PageResult<CommentVO> getUserComments(Long userId, int page, int size, Long currentUserId) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Comment> pageData = commentRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        List<CommentVO> vos = pageData.getContent().stream()
                .map(c -> toVO(c, currentUserId))
                .toList();
        return PageResult.of(vos, pageData.getTotalElements(), page, size);
    }

    /** 统计书籍评论数 */
    public long countBookComments(Long bookId) {
        return commentRepository.countByBookIdAndChapterIdIsNull(bookId);
    }

    // ==================== VO 转换 ====================

    private CommentVO toVO(Comment comment, Long currentUserId) {
        CommentVO vo = new CommentVO();
        vo.setId(comment.getId());
        vo.setUserId(comment.getUserId());
        vo.setBookId(comment.getBookId());
        vo.setChapterId(comment.getChapterId());
        vo.setParentId(comment.getParentId());
        vo.setContent(comment.getContent());
        vo.setLikeCount(comment.getLikeCount());
        vo.setReplyCount(comment.getReplyCount());
        vo.setFavoriteCount(comment.getFavoriteCount());
        vo.setCreatedAt(comment.getCreatedAt() != null ? comment.getCreatedAt().toString() : null);

        // 当前用户的交互状态
        if (currentUserId != null) {
            vo.setLiked(commentLikeRepository.existsByCommentIdAndUserId(comment.getId(), currentUserId));
            vo.setFavorited(commentFavoriteRepository.existsByCommentIdAndUserId(comment.getId(), currentUserId));
        }
        return vo;
    }

}
