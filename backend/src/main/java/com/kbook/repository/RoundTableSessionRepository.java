package com.kbook.repository;

import com.kbook.entity.RoundTableSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 圆桌派会话数据访问层
 */
public interface RoundTableSessionRepository extends BaseRepository<RoundTableSession, Long> {

    /**
     * 根据会话ID查询会话
     */
    Optional<RoundTableSession> findBySessionId(String sessionId);

    /**
     * 按用户和书籍查询会话列表，按更新时间降序排列
     */
    List<RoundTableSession> findByUserIdAndBookIdOrderByUpdatedAtDesc(Long userId, Long bookId);

    /**
     * 按用户查询所有会话，按更新时间降序排列
     */
    List<RoundTableSession> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
