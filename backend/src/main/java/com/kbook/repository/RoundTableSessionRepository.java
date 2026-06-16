package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.RoundTableSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 圆桌派会话数据访问层
 * <p>
 * 查询统一使用 BaseRepository.query() 的 Fluent API
 */
public interface RoundTableSessionRepository extends BaseRepository<RoundTableSession, Long> {

    /** 查询全局会话（排除已废弃的） */
    @Query("SELECT s FROM RoundTableSession s WHERE s.status <> 'ABANDONED'")
    Page<RoundTableSession> findPublicOrOwnSessions(Pageable pageable);

    /** 只查询当前用户的会话 */
    @Query("SELECT s FROM RoundTableSession s WHERE s.userId = :userId AND s.status <> 'ABANDONED'")
    Page<RoundTableSession> findByUserId(@Param("userId") Long userId, Pageable pageable);
}
