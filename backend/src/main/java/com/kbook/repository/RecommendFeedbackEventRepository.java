package com.kbook.repository;

import com.kbook.entity.RecommendFeedbackEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface RecommendFeedbackEventRepository extends JpaRepository<RecommendFeedbackEvent, Long> {

    /**
     * 统计指定时间窗口内的反馈事件数量
     */
    long countByCreatedAtBetween(LocalDateTime from, LocalDateTime to);

    /**
     * 按反馈类型统计数量
     */
    @Query("SELECT e.feedbackType, COUNT(e), AVG(e.strength) FROM RecommendFeedbackEvent e " +
            "WHERE e.createdAt BETWEEN :from AND :to GROUP BY e.feedbackType")
    List<Object[]> countByFeedbackTypeInRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 按召回路径统计正反馈率
     */
    @Query("SELECT e.recallPaths, COUNT(e), SUM(CASE WHEN e.strength > 0 THEN 1 ELSE 0 END) " +
            "FROM RecommendFeedbackEvent e WHERE e.createdAt BETWEEN :from AND :to " +
            "AND e.recallPaths IS NOT NULL GROUP BY e.recallPaths")
    List<Object[]> countByRecallPathInRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 清理指定时间之前的旧数据（保留最近N天的数据）
     */
    long deleteByCreatedAtBefore(LocalDateTime before);
}
