package com.kbook.service.comment;
import com.kbook.service.notification.EmailNotificationService;
import com.kbook.service.notification.NotificationService;
import com.kbook.service.book.BookService;

import com.kbook.common.api.PageResult;
import com.kbook.common.util.CommonUtils;
import com.kbook.common.exception.BusinessException;
import com.kbook.config.annotation.LogAction;
import com.kbook.config.annotation.LogModule;
import com.kbook.config.annotation.RedisLock;
import com.kbook.dto.book.BookProjection;
import com.kbook.dto.comment.CommentVO;
import com.kbook.entity.*;
import com.kbook.repository.CommentFavoriteRepository;
import com.kbook.repository.CommentLikeRepository;
import com.kbook.repository.CommentRepository;
import com.kbook.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static com.kbook.common.util.QueryBuilder.*;

/**
 * 评论服务类
 * 提供书籍评论和章节评论的完整功能，包括创建、删除、查询、点赞、收藏等操作
 * 支持评论回复功能，并在用户互动时发送通知和邮件提醒
 */
@Slf4j
@Service
@LogModule("评论")
public class CommentService {

    @Autowired
    private CommentRepository commentRepository; // 评论数据访问层
    @Autowired
    private CommentLikeRepository commentLikeRepository; // 评论点赞数据访问层
    @Autowired
    private CommentFavoriteRepository commentFavoriteRepository; // 评论收藏数据访问层
    @Autowired
    private NotificationService notificationService; // 站内通知服务
    @Autowired
    private EmailNotificationService emailNotificationService; // 邮件通知服务
    @Autowired
    private UserRepository userRepository; // 用户数据访问层
    @Autowired
    private BookService bookService; // 书籍服务

    /**
     * 发表评论或回复评论
     * 支持发表顶级评论和回复已有评论，回复时会更新父评论的回复数并发送通知
     * @param userId 发表评论的用户ID
     * @param bookId 书籍ID
     * @param chapterId 章节ID（可选，为空表示书籍评论）
     * @param parentId 父评论ID（可选，不为空表示回复评论）
     * @param content 评论内容
     * @return 评论视图对象
     */
    @Transactional // 开启事务保证数据一致性
    @LogAction("发表评论")
    public CommentVO createComment(Long userId, Long bookId, String chapterId, Long parentId, String content) {
        // 验证评论内容不为空
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException("评论内容不能为空");
        }
        // 验证评论内容长度不超过2000字
        if (content.length() > 2000) {
            throw new BusinessException("评论内容不能超过2000字");
        }

        // 如果是回复评论，需要校验父评论存在并更新相关数据
        if (parentId != null) {
            // 查找父评论，不存在则抛出异常
            Comment parent = commentRepository.findOneById(parentId);
            if (parent == null) {
                throw new BusinessException("回复的评论不存在");
            }
            // 更新父评论的回复数（加1）
            parent.setReplyCount(parent.getReplyCount() + 1);
            commentRepository.save(parent); // 保存更新后的父评论

            // 获取回复者信息（用于后续邮件通知）
            User replier = userRepository.findById(userId).orElse(null); // 查询回复者用户信息
            BookProjection book = bookService.getBookProjectionById(bookId); // 查询书籍信息

            // 如果回复的不是自己的评论，则发送通知给父评论作者
            if (!parent.getUserId().equals(userId)) {
                // 发送站内通知：评论被回复
                notificationService.notifyCommentReply(userId, parent.getUserId(), parent.getId(), bookId);

                // 发送回复邮件通知给父评论作者
                if (replier != null && book != null) { // 确保回复者和书籍信息存在
                    User parentUser = userRepository.findById(parent.getUserId()).orElse(null); // 查询父评论作者
                    // 如果父评论作者存在且有邮箱地址
                    if (parentUser != null && parentUser.getEmail() != null) {
                        // 截取评论内容前50字符作为预览
                        String preview = CommonUtils.truncateText(content, 50);
                        // 发送HTML格式邮件通知
                        emailNotificationService.sendHtmlEmail(
                            parentUser.getEmail(), // 收件人邮箱
                            "【KBook】" + replier.getNickname() + " 回复了你的书评", // 邮件标题
                            EmailNotificationService.EmailType.COMMENT_REPLY, // 邮件类型：评论回复
                            java.util.Map.of( // 邮件模板参数
                                "userName", replier.getNickname(), // 回复者昵称
                                "bookTitle", book.getTitle(), // 书籍标题
                                "content", preview // 评论内容预览
                            )
                        );
                    }
                }
            }

            // 检查父评论的回复数是否达到阈值，如果达到则发送达标邮件通知
            if (book != null) { // 确保书籍信息存在
                User parentUser = userRepository.findById(parent.getUserId()).orElse(null); // 查询父评论作者
                // 如果父评论作者存在且有邮箱地址
                if (parentUser != null && parentUser.getEmail() != null) {
                    // 截取父评论内容前50字符作为预览
                    String preview = CommonUtils.truncateText(parent.getContent(), 50);
                    // 检查并发送回复数达标邮件通知
                    emailNotificationService.checkAndSendReplyThresholdNotification(
                        parent.getId(), // 父评论ID
                        parentUser.getEmail(), // 收件人邮箱
                        replier != null ? replier.getNickname() : "用户", // 最新回复者昵称
                        book.getTitle(), // 书籍标题
                        preview, // 父评论内容预览
                        parent.getReplyCount() // 当前回复数
                    );
                }
            }
        }

        // 构建新的评论对象
        Comment comment = Comment.builder()
                .userId(userId) // 设置评论者ID
                .bookId(bookId) // 设置书籍ID
                .chapterId(chapterId) // 设置章节ID（可为null）
                .parentId(parentId) // 设置父评论ID（可为null）
                .content(content.trim()) // 设置评论内容（去除首尾空格）
                .build();

        Comment saved = commentRepository.save(comment); // 保存评论到数据库
        return toVO(saved, userId); // 转换为视图对象并返回
    }

    /**
     * 删除评论
     * 删除评论时会同时删除其所有子评论，并清理相关的点赞和收藏记录
     * 如果是回复评论，还会减少父评论的回复数
     * @param commentId 要删除的评论ID
     * @param userId 操作用户ID（只能删除自己的评论）
     */
    @Transactional // 开启事务保证数据一致性
    @LogAction("删除评论")
    public void deleteComment(Long commentId, Long userId) {
        // 查找要删除的评论，不存在则抛出异常
        Comment comment = commentRepository.findOneById(commentId);
        if (comment == null) {
            throw new BusinessException("评论不存在");
        }
        // 验证只能删除自己的评论
        if (!comment.getUserId().equals(userId)) {
            throw new BusinessException("只能删除自己的评论");
        }

        // 如果是回复评论，需要减少父评论的回复数
        if (comment.getParentId() != null) {
            // 查找父评论并更新回复数
            Comment parent = commentRepository.findOneById(comment.getParentId());
            if (parent != null) {
                // 确保回复数不会小于0
                parent.setReplyCount(Math.max(0, parent.getReplyCount() - 1));
                commentRepository.save(parent); // 保存更新后的父评论
            }
        }

        // 删除关联的点赞和收藏记录（批量）
        List<Comment> replies = commentRepository.findByParentIdOrderByCreatedAtAsc(commentId);
        if (!replies.isEmpty()) {
            Set<Long> replyIds = replies.stream().map(Comment::getId).collect(Collectors.toSet());
            commentLikeRepository.deleteAll(commentLikeRepository.query()
                    .where(CommentLike::getCommentId, in(replyIds))
                    .list());
            commentFavoriteRepository.deleteAll(commentFavoriteRepository.query()
                    .where(CommentFavorite::getCommentId, in(replyIds))
                    .list());
        }
        commentRepository.deleteAllById(replies.stream().map(Comment::getId).toList());

        // 删除本评论的点赞和收藏记录
        commentLikeRepository.deleteAll(commentLikeRepository.query()
                .where(CommentLike::getCommentId, eq(commentId))
                .and(CommentLike::getUserId, eq(userId))
                .list());

        commentRepository.deleteById(comment.getId()); // 删除主评论
    }

    /**
     * 获取书籍的顶级评论列表（分页）
     * 按点赞数降序、创建时间降序排序，不包含章节评论和回复评论
     * @param bookId 书籍ID
     * @param page 页码（从1开始）
     * @param size 每页大小
     * @param currentUserId 当前用户ID（用于判断点赞和收藏状态）
     * @return 分页的评论视图对象列表
     */
    @LogAction("获取书籍评论列表")
    public PageResult<CommentVO> getBookComments(Long bookId, int page, int size, Long currentUserId) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "likeCount").and(Sort.by(Sort.Direction.DESC, "createdAt")));
        Page<Comment> pageData = commentRepository.findBookTopComments(bookId, pageable);
        List<CommentVO> vos = batchToVO(pageData.getContent(), currentUserId);
        return PageResult.of(vos, pageData.getTotalElements(), page, size);
    }

    @LogAction("获取章节评论列表")
    public PageResult<CommentVO> getChapterComments(Long bookId, String chapterId, int page, int size, Long currentUserId) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "likeCount").and(Sort.by(Sort.Direction.DESC, "createdAt")));
        Page<Comment> pageData = commentRepository.findChapterTopComments(bookId, chapterId, pageable);
        List<CommentVO> vos = batchToVO(pageData.getContent(), currentUserId);
        return PageResult.of(vos, pageData.getTotalElements(), page, size);
    }

    @LogAction("获取评论回复列表")
    public List<CommentVO> getReplies(Long parentId, Long currentUserId) {
        List<Comment> replies = commentRepository.findByParentIdOrderByCreatedAtAsc(parentId);
        return batchToVO(replies, currentUserId);
    }

    @LogAction("获取高赞书评列表")
    public PageResult<CommentVO> getTopRatedComments(int minLikes, int page, int size, Long currentUserId) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "likeCount").and(Sort.by(Sort.Direction.DESC, "createdAt")));
        Page<Comment> pageData = commentRepository.findTopRatedComments(minLikes, pageable);
        List<CommentVO> vos = batchToVO(pageData.getContent(), currentUserId);
        return PageResult.of(vos, pageData.getTotalElements(), page, size);
    }

    /**
     * 点赞评论
     * 为用户添加对评论的点赞，增加评论点赞数，并发送通知和邮件给评论作者
     * @param commentId 要点赞的评论ID
     * @param userId 点赞用户ID
     */
    @Transactional // 开启事务保证数据一致性
    @LogAction("点赞评论")
    @RedisLock(key = "'comment:like:' + #commentId + ':' + #userId", leaseTime = 10)
    public void likeComment(Long commentId, Long userId) {
        // 检查用户是否已经点赞过，避免重复点赞
        if (commentLikeRepository.query()
                .where(CommentLike::getCommentId, eq(commentId))
                .and(CommentLike::getUserId, eq(userId))
                .exists()) {
            throw new BusinessException("已经点赞过了");
        }
        // 创建点赞记录
        commentLikeRepository.save(CommentLike.builder().commentId(commentId).userId(userId).build());

        // 查找被点赞的评论
        Comment comment = commentRepository.findOneById(commentId);
        if (comment == null) {
            throw new BusinessException("评论不存在");
        }
        // 计算新的点赞数
        int newLikeCount = comment.getLikeCount() + 1;
        comment.setLikeCount(newLikeCount); // 更新评论点赞数
        commentRepository.save(comment); // 保存更新后的评论

        // 如果点赞的不是自己的评论，则发送通知给评论作者
        if (!comment.getUserId().equals(userId)) {
            // 发送站内通知：评论被点赞
            notificationService.notifyCommentLiked(userId, comment.getUserId(), commentId, comment.getBookId());

            // 发送点赞邮件通知给评论作者
            User liker = userRepository.findById(userId).orElse(null); // 查询点赞者信息
            BookProjection book = bookService.getBookProjectionById(comment.getBookId()); // 查询书籍信息
            User commentOwner = userRepository.findById(comment.getUserId()).orElse(null); // 查询评论作者
            // 如果点赞者、书籍、评论作者都存在且评论作者有邮箱
            if (liker != null && book != null && commentOwner != null && commentOwner.getEmail() != null) {
                // 截取评论内容前50字符作为预览
                String preview = CommonUtils.truncateText(comment.getContent(), 50);
                // 发送HTML格式邮件通知
                emailNotificationService.sendHtmlEmail(
                    commentOwner.getEmail(), // 收件人邮箱
                    "【KBook】" + liker.getNickname() + " 赞了你的书评", // 邮件标题
                    EmailNotificationService.EmailType.COMMENT_LIKE, // 邮件类型：评论点赞
                    java.util.Map.of( // 邮件模板参数
                        "userName", liker.getNickname(), // 点赞者昵称
                        "bookTitle", book.getTitle(), // 书籍标题
                        "content", preview, // 评论内容预览
                        "count", newLikeCount, // 当前点赞数
                        "actionText", "查看书评" // 操作按钮文本
                    )
                );
            }
        }

        // 检查点赞数是否达到阈值，如果达到则发送达标邮件通知
        User commentOwner = userRepository.findById(comment.getUserId()).orElse(null); // 查询评论作者
        BookProjection book = bookService.getBookProjectionById(comment.getBookId()); // 查询书籍信息
        // 如果评论作者存在且有邮箱，且书籍信息存在
        if (commentOwner != null && commentOwner.getEmail() != null && book != null) {
            // 截取评论内容前50字符作为预览
            String preview = CommonUtils.truncateText(comment.getContent(), 50);
            User liker = userRepository.findById(userId).orElse(null); // 查询点赞者信息
            // 检查并发送点赞数达标邮件通知
            emailNotificationService.checkAndSendLikeThresholdNotification(
                comment.getId(), // 评论ID
                commentOwner.getEmail(), // 收件人邮箱
                liker != null ? liker.getNickname() : "用户", // 点赞者昵称
                book.getTitle(), // 书籍标题
                preview, // 评论内容预览
                newLikeCount // 当前点赞数
            );
        }
    }

    /**
     * 取消点赞评论
     * 删除用户的点赞记录，减少评论点赞数
     * @param commentId 要取消点赞的评论ID
     * @param userId 取消点赞的用户ID
     */
    @Transactional // 开启事务保证数据一致性
    @LogAction("取消点赞评论")
    @RedisLock(key = "'comment:like:' + #commentId + ':' + #userId", leaseTime = 10)
    public void unlikeComment(Long commentId, Long userId) {
        // 检查用户是否已经点赞过，未点赞则抛出异常
        CommentLike like = commentLikeRepository.query()
                .where(CommentLike::getCommentId, eq(commentId))
                .and(CommentLike::getUserId, eq(userId))
                .list(1)
                .stream().findFirst().orElse(null);
        if (like == null) {
            throw new BusinessException("尚未点赞");
        }
        // 删除点赞记录
        commentLikeRepository.delete(like);

        // 查找评论并更新点赞数
        Comment comment = commentRepository.findOneById(commentId);
        if (comment == null) {
            throw new BusinessException("评论不存在");
        }
        // 减少点赞数，确保不会小于0
        comment.setLikeCount(Math.max(0, comment.getLikeCount() - 1));
        commentRepository.save(comment); // 保存更新后的评论
    }

    /**
     * 收藏评论
     * 为用户添加对评论的收藏，增加评论收藏数，并发送通知给评论作者
     * @param commentId 要收藏的评论ID
     * @param userId 收藏用户ID
     */
    @Transactional // 开启事务保证数据一致性
    @LogAction("收藏评论")
    @RedisLock(key = "'comment:fav:' + #commentId + ':' + #userId", leaseTime = 10)
    public void favoriteComment(Long commentId, Long userId) {
        // 检查用户是否已经收藏过，避免重复收藏
        if (commentFavoriteRepository.query()
                .where(CommentFavorite::getCommentId, eq(commentId))
                .and(CommentFavorite::getUserId, eq(userId))
                .exists()) {
            throw new BusinessException("已经收藏过了");
        }
        // 创建收藏记录
        commentFavoriteRepository.save(CommentFavorite.builder().commentId(commentId).userId(userId).build());

        // 查找被收藏的评论
        Comment comment = commentRepository.findOneById(commentId);
        if (comment == null) {
            throw new BusinessException("评论不存在");
        }
        // 增加评论收藏数
        comment.setFavoriteCount(comment.getFavoriteCount() + 1);
        commentRepository.save(comment); // 保存更新后的评论

        // 如果收藏的不是自己的评论，则发送通知给评论作者
        if (!comment.getUserId().equals(userId)) {
            // 发送站内通知：评论被收藏
            notificationService.notifyCommentFavorited(userId, comment.getUserId(), commentId, comment.getBookId());
        }
    }

    /**
     * 取消收藏评论
     * 删除用户的收藏记录，减少评论收藏数
     * @param commentId 要取消收藏的评论ID
     * @param userId 取消收藏的用户ID
     */
    @Transactional // 开启事务保证数据一致性
    @LogAction("取消收藏评论")
    @RedisLock(key = "'comment:fav:' + #commentId + ':' + #userId", leaseTime = 10)
    public void unfavoriteComment(Long commentId, Long userId) {
        // 检查用户是否已经收藏过，未收藏则抛出异常
        CommentFavorite fav = commentFavoriteRepository.query()
                .where(CommentFavorite::getCommentId, eq(commentId))
                .and(CommentFavorite::getUserId, eq(userId))
                .list(1)
                .stream().findFirst().orElse(null);
        if (fav == null) {
            throw new BusinessException("尚未收藏");
        }
        // 删除收藏记录
        commentFavoriteRepository.delete(fav);

        // 查找评论并更新收藏数
        Comment comment = commentRepository.findOneById(commentId);
        if (comment == null) {
            throw new BusinessException("评论不存在");
        }
        // 减少收藏数，确保不会小于0
        comment.setFavoriteCount(Math.max(0, comment.getFavoriteCount() - 1));
        commentRepository.save(comment); // 保存更新后的评论
    }

    /**
     * 查询用户的评论列表（分页）
     * 按创建时间降序排列，返回指定用户发表的所有评论
     * @param userId 用户ID
     * @param page 页码（从1开始）
     * @param size 每页大小
     * @param currentUserId 当前用户ID（用于判断点赞和收藏状态）
     * @return 分页的评论视图对象列表
     */
    @LogAction("获取用户评论列表")
    public PageResult<CommentVO> getUserComments(Long userId, int page, int size, Long currentUserId) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Comment> pageData = commentRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        List<CommentVO> vos = batchToVO(pageData.getContent(), currentUserId);
        return PageResult.of(vos, pageData.getTotalElements(), page, size);
    }

    /**
     * 统计书籍的评论数量
     * 只统计书籍级别的顶级评论，不包括章节评论
     * @param bookId 书籍ID
     * @return 评论数量
     */
    @LogAction("统计书籍评论数")
    public long countBookComments(Long bookId) {
        // 查询书籍ID匹配且章节ID为空的评论数量（即书籍顶级评论）
        return commentRepository.countByBookIdAndChapterIdIsNull(bookId);
    }

    // ==================== VO 转换 ====================

    /**
     * 批量将评论实体转换为视图对象（解决 N+1 查询问题）
     * 预先批量查询当前用户对所有评论的点赞/收藏状态，避免逐条查询
     */
    private List<CommentVO> batchToVO(List<Comment> comments, Long currentUserId) {
        Set<Long> commentIds = comments.stream().map(Comment::getId).collect(Collectors.toSet());

        Set<Long> likedIds = Set.of();
        Set<Long> favoritedIds = Set.of();

        if (currentUserId != null && !commentIds.isEmpty()) {
            likedIds = commentLikeRepository.query()
                    .where(CommentLike::getCommentId, in(commentIds))
                    .and(CommentLike::getUserId, eq(currentUserId))
                    .list().stream()
                    .map(CommentLike::getCommentId)
                    .collect(Collectors.toSet());

            favoritedIds = commentFavoriteRepository.query()
                    .where(CommentFavorite::getCommentId, in(commentIds))
                    .and(CommentFavorite::getUserId, eq(currentUserId))
                    .list().stream()
                    .map(CommentFavorite::getCommentId)
                    .collect(Collectors.toSet());
        }

        final Set<Long> finalLikedIds = likedIds;
        final Set<Long> finalFavoritedIds = favoritedIds;

        return comments.stream().map(comment -> {
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
            if (currentUserId != null) {
                vo.setLiked(finalLikedIds.contains(comment.getId()));
                vo.setFavorited(finalFavoritedIds.contains(comment.getId()));
            }
            return vo;
        }).toList();
    }

    /**
     * 单条评论转 VO（用于 createComment 等单条场景）
     */
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

        if (currentUserId != null) {
            vo.setLiked(commentLikeRepository.query()
                    .where(CommentLike::getCommentId, eq(comment.getId()))
                    .and(CommentLike::getUserId, eq(currentUserId))
                    .exists());
            vo.setFavorited(commentFavoriteRepository.query()
                    .where(CommentFavorite::getCommentId, eq(comment.getId()))
                    .and(CommentFavorite::getUserId, eq(currentUserId))
                    .exists());
        }
        return vo;
    }

}
