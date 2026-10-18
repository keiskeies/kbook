package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.RoundTableMessage;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 圆桌派消息数据访问层
 * <p>
 * 简单查询统一使用 BaseRepository.query() 的 Fluent API
 */
public interface RoundTableMessageRepository extends BaseRepository<RoundTableMessage, Long> {

    /**
     * 统计会话 compressed_content 总长度（字符数）
     */
    @Query(value = "SELECT COALESCE(SUM(CHAR_LENGTH(compressed_content)), 0) " +
            "FROM round_table_messages WHERE user_id = :userId AND session_id = :sessionId",
            nativeQuery = true)
    long sumCompressedContentLength(@Param("userId") Long userId, @Param("sessionId") String sessionId);

    /**
     * 统计会话中嘉宾（非 HOST）消息数量 —— 用于统一轮次口径 ceil(嘉宾消息数 / 嘉宾数)
     */
    @Query(value = "SELECT COUNT(*) FROM round_table_messages " +
            "WHERE session_id = :sessionId AND role_key <> 'HOST'",
            nativeQuery = true)
    long countGuestMessages(@Param("sessionId") String sessionId);

    /**
     * 查找会话中最老的一条未压缩的非 HOST 消息
     * 未压缩 = compressed_content 与 content 完全相同（创建时初始化为相等）
     */
    @Query(value = "SELECT * FROM round_table_messages " +
            "WHERE user_id = :userId AND session_id = :sessionId AND role_key != 'HOST' " +
            "AND COALESCE(compressed_content, content) = content " +
            "ORDER BY id LIMIT 1",
            nativeQuery = true)
    Optional<RoundTableMessage> findFirstUncompressibleMessage(@Param("userId") Long userId, @Param("sessionId") String sessionId);
}
