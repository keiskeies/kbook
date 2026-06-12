package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.CommentLike;

/**
 * 评论点赞数据访问层
 * <p>
 * 查询统一使用 BaseRepository.query() 的 Fluent API
 */
public interface CommentLikeRepository extends BaseRepository<CommentLike, Long> {
}
