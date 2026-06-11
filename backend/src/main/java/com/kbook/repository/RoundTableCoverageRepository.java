package com.kbook.repository;

import com.kbook.entity.RoundTableCoverage;

import java.util.Optional;

/**
 * 圆桌派覆盖度数据访问层
 */
public interface RoundTableCoverageRepository extends BaseRepository<RoundTableCoverage, Long> {

    /**
     * 根据会话ID查询覆盖度记录
     */
    Optional<RoundTableCoverage> findBySessionId(String sessionId);
}
