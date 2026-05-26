package com.kbook.repository;

import com.kbook.entity.CommentFavorite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 评论收藏数据访问层
 */
public interface CommentFavoriteRepository extends JpaRepository<CommentFavorite, Long> {

    /**
     * 查询用户对指定评论的收藏记录
     */
    Optional<CommentFavorite> findByCommentIdAndUserId(Long commentId, Long userId);

    /**
     * 判断用户是否收藏了指定评论
     */
    boolean existsByCommentIdAndUserId(Long commentId, Long userId);

    /**
     * 取消用户对指定评论的收藏
     */
    void deleteByCommentIdAndUserId(Long commentId, Long userId);
}
