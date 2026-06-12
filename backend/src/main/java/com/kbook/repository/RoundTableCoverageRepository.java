package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.RoundTableCoverage;

/**
 * 圆桌派覆盖度数据访问层
 * <p>
 * 查询统一使用 BaseRepository.query() 的 Fluent API
 */
public interface RoundTableCoverageRepository extends BaseRepository<RoundTableCoverage, Long> {
}
