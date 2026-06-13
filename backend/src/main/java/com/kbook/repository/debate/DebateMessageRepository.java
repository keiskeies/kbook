package com.kbook.repository.debate;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.debate.DebateMessage;

import java.util.List;

/**
 * 奇葩说辩论消息数据访问层
 * <p>
 * 简单查询统一使用 BaseRepository.query() 的 Fluent API
 */
public interface DebateMessageRepository extends BaseRepository<DebateMessage, Long> {

    /** 按会话ID和轮次号查询（按 phaseOrder 排序） */
    List<DebateMessage> findBySessionIdAndRoundNumberOrderByPhaseOrder(String sessionId, Integer roundNumber);

    /** 按会话ID查询所有消息（按ID排序） */
    List<DebateMessage> findBySessionIdOrderById(String sessionId);

    /** 统计会话消息数 */
    int countBySessionId(String sessionId);
}
