package com.kbook.service.admin;

import com.kbook.dto.admin.AiBudgetDTO;
import com.kbook.dto.admin.AiBudgetUpdateRequest;
import com.kbook.entity.AiBudgetConfig;
import com.kbook.entity.User;
import com.kbook.repository.AiBudgetConfigRepository;
import com.kbook.repository.AiUsageLogRepository;
import com.kbook.repository.UserRepository;
import com.kbook.service.notification.EmailNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * AI 成本预算：配置、月度费用与阈值告警。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiBudgetService {

    private final AiBudgetConfigRepository budgetRepository;
    private final AiUsageLogRepository usageLogRepository;
    private final UserRepository userRepository;
    private final EmailNotificationService emailNotificationService;

    public AiBudgetConfig getOrCreate() {
        return budgetRepository.findAll().stream().findFirst()
                .orElseGet(() -> budgetRepository.save(AiBudgetConfig.builder().build()));
    }

    public double monthCost() {
        LocalDate firstDay = LocalDate.now().withDayOfMonth(1);
        Double cost = usageLogRepository.sumCostSince(firstDay.atStartOfDay());
        return cost != null ? cost : 0D;
    }

    public AiBudgetDTO getStatus() {
        AiBudgetConfig config = getOrCreate();
        double cost = monthCost();
        return toDto(config, cost);
    }

    @Transactional(rollbackFor = Exception.class)
    public AiBudgetDTO update(AiBudgetUpdateRequest request) {
        AiBudgetConfig config = getOrCreate();
        if (request.getMonthlyBudget() != null) {
            config.setMonthlyBudget(Math.max(0D, request.getMonthlyBudget()));
        }
        if (request.getEnabled() != null) {
            config.setEnabled(request.getEnabled());
        }
        if (request.getAlertPercent() != null) {
            config.setAlertPercent(Math.min(100, Math.max(1, request.getAlertPercent())));
        }
        budgetRepository.save(config);
        return toDto(config, monthCost());
    }

    /**
     * 每天 09:00 检查月预算；达到阈值时给所有管理员发一次邮件（每月最多一次）。
     */
    @Scheduled(cron = "0 0 9 * * *")
    @Transactional(rollbackFor = Exception.class)
    public void checkAndAlert() {
        AiBudgetConfig config = getOrCreate();
        double budget = config.getMonthlyBudget() != null ? config.getMonthlyBudget() : 0D;
        if (!Boolean.TRUE.equals(config.getEnabled()) || budget <= 0) {
            return;
        }
        double cost = monthCost();
        int alertPercent = config.getAlertPercent() != null ? config.getAlertPercent() : 80;
        if (cost < budget * alertPercent / 100D) {
            return;
        }
        String month = YearMonth.now().toString();
        // 先抢占本月告警资格：多副本并发时只有一个副本会真正发出邮件
        if (budgetRepository.claimAlert(config.getId(), month) == 0) {
            return;
        }

        List<User> admins = userRepository.findByRole("ADMIN");
        if (admins.isEmpty()) {
            log.warn("AI 预算告警：没有可通知的管理员");
        }
        int usedPercent = (int) Math.round(cost / budget * 100);
        for (User admin : admins) {
            if (admin.getEmail() != null && !admin.getEmail().isBlank()) {
                emailNotificationService.sendAiBudgetAlert(admin.getEmail(), admin.getNickname(),
                        cost, budget, usedPercent);
            }
        }
        log.warn("AI 成本预算告警已发送: monthCost={}, budget={}, usedPercent={}%, admins={}",
                String.format("%.2f", cost), String.format("%.2f", budget), usedPercent, admins.size());
    }

    private AiBudgetDTO toDto(AiBudgetConfig config, double cost) {
        double budget = config.getMonthlyBudget() != null ? config.getMonthlyBudget() : 0D;
        double usedPercent = budget > 0 ? Math.round(cost / budget * 1000D) / 10D : 0D;
        String status;
        if (!Boolean.TRUE.equals(config.getEnabled()) || budget <= 0) {
            status = "DISABLED";
        } else if (usedPercent >= 100) {
            status = "EXCEEDED";
        } else if (usedPercent >= (config.getAlertPercent() != null ? config.getAlertPercent() : 80)) {
            status = "WARNING";
        } else {
            status = "OK";
        }
        return AiBudgetDTO.builder()
                .monthlyBudget(budget)
                .enabled(Boolean.TRUE.equals(config.getEnabled()))
                .alertPercent(config.getAlertPercent() != null ? config.getAlertPercent() : 80)
                .monthCost(Math.round(cost * 1_000_000D) / 1_000_000D)
                .usedPercent(usedPercent)
                .status(status)
                .lastAlertMonth(config.getLastAlertMonth())
                .build();
    }
}
