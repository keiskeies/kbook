package com.kbook.entity;

import com.kbook.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * AI 成本月预算配置（单行表，id 固定为 1）。
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_budget_config")
public class AiBudgetConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 月度预算（元），0 表示未设置 */
    @Column(name = "monthly_budget", nullable = false)
    @Builder.Default
    private Double monthlyBudget = 0D;

    /** 是否启用预算告警 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = false;

    /** 达到预算的多少百分比时告警 */
    @Column(name = "alert_percent", nullable = false)
    @Builder.Default
    private Integer alertPercent = 80;

    /** 最近一次告警的月份（yyyy-MM），用于防止重复告警 */
    @Column(name = "last_alert_month", length = 7)
    private String lastAlertMonth;

    @Override
    public Long getId() {
        return id;
    }
}
