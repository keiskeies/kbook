package com.kbook.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 成本预算状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiBudgetDTO {
    private double monthlyBudget;
    private boolean enabled;
    private int alertPercent;
    private double monthCost;
    private double usedPercent;
    /** DISABLED / OK / WARNING / EXCEEDED */
    private String status;
    private String lastAlertMonth;
}
