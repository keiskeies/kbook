package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.RoundTableReport;

/**
 * 圆桌派解读报告数据访问层
 */
public interface RoundTableReportRepository extends BaseRepository<RoundTableReport, Long> {

    /** 根据 sessionId 查询报告 */
    RoundTableReport findOneBySessionId(String sessionId);
}
