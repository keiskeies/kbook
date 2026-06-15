package com.kbook.repository.debate;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.debate.DebateScore;

import java.util.List;

/**
 * 奇葩说辩论评分数据访问层
 */
public interface DebateScoreRepository extends BaseRepository<DebateScore, Long> {

    /** 按会话ID查询所有评分 */
    List<DebateScore> findBySessionId(String sessionId);

    /** 按会话ID和轮次号查询 */
    List<DebateScore> findBySessionIdAndRoundNumber(String sessionId, Integer roundNumber);

    /** 按角色键名查询 */
    List<DebateScore> findByRoleKey(String roleKey);

    /** 按消息ID查询评分 */
    DebateScore findByMessageId(Long messageId);

    /** 统计会话评分数 */
    int countBySessionId(String sessionId);

    /** 批量查询指定会话的评分 */
    List<DebateScore> findAllBySessionIdIn(List<String> sessionIds);
}
