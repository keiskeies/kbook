package com.kbook.repository;

import com.kbook.entity.CommentFavorite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 评论收藏数据访问层
 */
public interface CommentFavoriteRepository extends JpaRepository<CommentFavorite, Long> {

    Optional<CommentFavorite> findByCommentIdAndUserId(Long commentId, Long userId);

    boolean existsByCommentIdAndUserId(Long commentId, Long userId);

    void deleteByCommentIdAndUserId(Long commentId, Long userId);
}
