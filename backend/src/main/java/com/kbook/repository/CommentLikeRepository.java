package com.kbook.repository;

import com.kbook.entity.CommentLike;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 评论点赞数据访问层
 */
public interface CommentLikeRepository extends JpaRepository<CommentLike, Long> {

    /**
     * 查询用户对指定评论的点赞记录
     */
    Optional<CommentLike> findByCommentIdAndUserId(Long commentId, Long userId);

    /**
     * 判断用户是否点赞了指定评论
     */
    boolean existsByCommentIdAndUserId(Long commentId, Long userId);

    /**
     * 取消用户对指定评论的点赞
     */
    void deleteByCommentIdAndUserId(Long commentId, Long userId);
}
