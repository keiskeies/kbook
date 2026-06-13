package com.kbook.repository.debate;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.debate.DebateReport;

/**
 * 奇葩说辩论报告数据访问层
 */
public interface DebateReportRepository extends BaseRepository<DebateReport, Long> {

    /** 根据 sessionId 查询报告 */
    DebateReport findOneBySessionId(String sessionId);
}
