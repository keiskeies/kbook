package com.kbook.service.ai;

import com.kbook.entity.AiUsageLog;
import com.kbook.repository.AiUsageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * AI 调用用量记录服务。
 * <p>
 * 由模型装饰器在每次 LLM 调用完成后异步写入，失败只记日志，绝不影响业务调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageLogService {

    /** 每次调用的完整用量事件（含归因、质量与单价快照）。 */
    public record UsageEvent(
            String scene, String modelName, String configName,
            int inputTokens, int outputTokens, String callType,
            Long userId, Long bookId, String sessionId,
            Double inputPrice, Double outputPrice,
            long latencyMs, boolean success,
            String finishReason, String errorType) {
    }

    private final AiUsageLogRepository usageLogRepository;

    @Async
    public void record(UsageEvent event) {
        try {
            double cost = calculateCost(event.inputTokens(), event.inputPrice())
                    + calculateCost(event.outputTokens(), event.outputPrice());
            usageLogRepository.save(AiUsageLog.builder()
                    .scene(event.scene() != null ? event.scene() : "UNKNOWN")
                    .modelName(event.modelName())
                    .configName(event.configName())
                    .inputTokens(event.inputTokens())
                    .outputTokens(event.outputTokens())
                    .totalTokens(event.inputTokens() + event.outputTokens())
                    .callType(event.callType())
                    .userId(event.userId())
                    .bookId(event.bookId())
                    .sessionId(event.sessionId())
                    .latencyMs((int) Math.min(event.latencyMs(), Integer.MAX_VALUE))
                    .success(event.success())
                    .finishReason(event.finishReason())
                    .errorType(event.errorType())
                    .inputPrice(event.inputPrice())
                    .outputPrice(event.outputPrice())
                    .cost(cost)
                    .build());
        } catch (Exception e) {
            log.warn("AI 用量记录写入失败: scene={}, error={}", event.scene(), e.getMessage());
        }
    }

    /** 单价单位：元 / 百万 token */
    private double calculateCost(int tokens, Double pricePerMillion) {
        if (tokens <= 0 || pricePerMillion == null || pricePerMillion <= 0) {
            return 0D;
        }
        return tokens * pricePerMillion / 1_000_000D;
    }
}
