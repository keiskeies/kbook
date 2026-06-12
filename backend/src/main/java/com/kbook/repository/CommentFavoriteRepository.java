package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.CommentFavorite;

/**
 * 评论收藏数据访问层
 * <p>
 * 查询统一使用 BaseRepository.query() 的 Fluent API
 */
public interface CommentFavoriteRepository extends BaseRepository<CommentFavorite, Long> {
}
