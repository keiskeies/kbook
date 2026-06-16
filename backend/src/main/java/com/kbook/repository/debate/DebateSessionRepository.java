package com.kbook.repository.debate;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.debate.DebateSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 奇葩说辩论会话数据访问层
 * <p>
 * 查询统一使用 BaseRepository.query() 的 Fluent API
 */
public interface DebateSessionRepository extends BaseRepository<DebateSession, Long> {

    /** 查询全局会话（排除已废弃的） */
    @Query("SELECT s FROM DebateSession s WHERE s.status <> 'ABANDONED'")
    Page<DebateSession> findPublicOrOwnSessions(Pageable pageable);

    /** 只查询当前用户的会话 */
    @Query("SELECT s FROM DebateSession s WHERE s.userId = :userId AND s.status <> 'ABANDONED'")
    Page<DebateSession> findByUserId(@Param("userId") Long userId, Pageable pageable);
}
