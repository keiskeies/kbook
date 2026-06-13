package com.kbook.repository.debate;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.debate.DebateSession;

/**
 * 奇葩说辩论会话数据访问层
 * <p>
 * 查询统一使用 BaseRepository.query() 的 Fluent API
 */
public interface DebateSessionRepository extends BaseRepository<DebateSession, Long> {
}
