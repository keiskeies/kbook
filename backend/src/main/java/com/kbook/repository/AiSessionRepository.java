package com.kbook.repository;

import com.kbook.entity.AiSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * AI 会话数据访问层
 */
public interface AiSessionRepository extends BaseRepository<AiSession, Long> {

    /**
     * 根据会话ID查询会话
     */
    Optional<AiSession> findBySessionId(String sessionId);

    /**
     * 按用户和类型查询会话列表，按更新时间降序排列
     */
    List<AiSession> findByUserIdAndTypeOrderByUpdatedAtDesc(Long userId, String type);

    /**
     * 按用户、类型和图书ID查询会话列表，按更新时间降序排列
     */
    List<AiSession> findByUserIdAndTypeAndBookIdOrderByUpdatedAtDesc(Long userId, String type, Long bookId);

    /**
     * 按用户查询所有会话，按更新时间降序排列
     */
    List<AiSession> findByUserIdOrderByUpdatedAtDesc(Long userId);

    /**
     * 删除指定用户和会话ID的会话
     */
    void deleteByUserIdAndSessionId(Long userId, String sessionId);
}
