package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.AiSession;

/**
 * AI 会话数据访问层
 * <p>
 * 查询统一使用 BaseRepository.query() 的 Fluent API
 */
public interface AiSessionRepository extends BaseRepository<AiSession, Long> {
}
