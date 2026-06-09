package com.kbook.repository;

import com.kbook.entity.RoundTableMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 圆桌派消息数据访问层
 */
public interface RoundTableMessageRepository extends JpaRepository<RoundTableMessage, Long> {

    /**
     * 按会话ID查询消息，按创建时间升序排列
     */
    List<RoundTableMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /**
     * 按用户和会话ID查询消息，按创建时间升序排列
     */
    List<RoundTableMessage> findByUserIdAndSessionIdOrderByCreatedAtAsc(Long userId, String sessionId);

    /**
     * 统计会话 compressed_content 总长度（字符数）
     */
    @Query(value = "SELECT COALESCE(SUM(CHAR_LENGTH(compressed_content)), 0) " +
            "FROM round_table_messages WHERE user_id = :userId AND session_id = :sessionId",
            nativeQuery = true)
    long sumCompressedContentLength(@Param("userId") Long userId, @Param("sessionId") String sessionId);

    /**
     * 查找会话中最老的一条未压缩的非 HOST 消息
     * 未压缩 = compressed_content 与 content 完全相同（创建时初始化为相等）
     */
    @Query(value = "SELECT * FROM round_table_messages " +
            "WHERE user_id = :userId AND session_id = :sessionId AND role_key != 'HOST' " +
            "AND COALESCE(compressed_content, content) = content " +
            "ORDER BY created_at ASC LIMIT 1",
            nativeQuery = true)
    Optional<RoundTableMessage> findFirstUncompressibleMessage(@Param("userId") Long userId, @Param("sessionId") String sessionId);

    /**
     * 删除指定用户和会话ID的所有消息
     */
    void deleteByUserIdAndSessionId(Long userId, String sessionId);
}
