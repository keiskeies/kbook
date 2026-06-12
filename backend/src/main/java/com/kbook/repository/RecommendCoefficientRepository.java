package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.RecommendCoefficient;

/**
 * 推荐系数数据访问层
 * <p>
 * 查询统一使用 BaseRepository.query() 的 Fluent API
 */
public interface RecommendCoefficientRepository extends BaseRepository<RecommendCoefficient, Long> {
}
