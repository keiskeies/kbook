package com.kbook.repository;

import com.kbook.entity.RecommendCoefficient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 推荐系数数据访问层
 */
public interface RecommendCoefficientRepository extends BaseRepository<RecommendCoefficient, Long> {

    /**
     * 根据类别和系数键查询推荐系数
     */
    Optional<RecommendCoefficient> findByCategoryAndCoeffKey(String category, String coeffKey);
}
