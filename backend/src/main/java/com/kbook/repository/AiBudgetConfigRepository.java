package com.kbook.repository;

import com.kbook.common.repository.BaseRepository;
import com.kbook.entity.AiBudgetConfig;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * AI 成本预算配置数据访问层（单行表）
 */
public interface AiBudgetConfigRepository extends BaseRepository<AiBudgetConfig, Long> {

    /**
     * 抢占当月告警资格 — 多副本同时跑定时任务时只有一个副本能更新成功，
     * 避免管理员收到重复告警邮件。返回 0 表示本月已告警过。
     */
    @Modifying
    @Query("UPDATE AiBudgetConfig c SET c.lastAlertMonth = :month " +
            "WHERE c.id = :id AND (c.lastAlertMonth IS NULL OR c.lastAlertMonth <> :month)")
    int claimAlert(@Param("id") Long id, @Param("month") String month);
}