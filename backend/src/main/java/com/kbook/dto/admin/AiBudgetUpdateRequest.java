package com.kbook.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 成本预算更新请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiBudgetUpdateRequest {
    /** 月度预算（元） */
    private Double monthlyBudget;
    /** 是否启用告警 */
    private Boolean enabled;
    /** 告警阈值百分比（1-100） */
    private Integer alertPercent;
}
