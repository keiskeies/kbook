package com.kbook.service.admin;

import com.kbook.repository.AiUsageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * AI 用量明细保留策略：每天凌晨清理超过保留期的记录，避免表无限增长。
 * 配置项 kbook.ai.usage-retention-days，小于等于 0 表示不清理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiUsageRetentionJob {

    private final AiUsageLogRepository usageLogRepository;

    @Value("${kbook.ai.usage-retention-days:365}")
    private int retentionDays;

    @Scheduled(cron = "0 30 3 * * *")
    public void purgeExpired() {
        if (retentionDays <= 0) {
            return;
        }
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
            int deleted = usageLogRepository.deleteBefore(cutoff);
            if (deleted > 0) {
                log.info("AI 用量明细清理完成: deleted={}, cutoff={}", deleted, cutoff);
            }
        } catch (Exception e) {
            log.warn("AI 用量明细清理失败: {}", e.getMessage());
        }
    }
}
