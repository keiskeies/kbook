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

    /** 查询公开会话或当前用户的会话 */
    @Query("SELECT s FROM RoundTableSession s WHERE s.visibility = 'PUBLIC' OR s.userId = :userId")
    Page<RoundTableSession> findPublicOrOwnSessions(@Param("userId") Long userId, Pageable pageable);

    /** 只查询当前用户的会话 */
    @Query("SELECT s FROM RoundTableSession s WHERE s.userId = :userId")
    Page<RoundTableSession> findByUserId(@Param("userId") Long userId, Pageable pageable);
}
