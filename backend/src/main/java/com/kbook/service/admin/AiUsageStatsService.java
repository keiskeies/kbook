package com.kbook.service.admin;

import com.kbook.repository.AiUsageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 用量聚合查询 — 供成本看板使用。
 * <p>
 * start 为空表示全部时间；否则按 [start, end) 区间统计。
 */
@Service
@RequiredArgsConstructor
public class AiUsageStatsService {

    private final AiUsageLogRepository repository;

    public record Totals(long tokens, double cost) {
    }

    public record Breakdown(String key, long tokens, double cost, long requests) {
    }

    public record Quality(long requests, long success, double avgLatencyMs, long p95LatencyMs, long truncated) {
        public double successRate() {
            return requests > 0 ? (double) success / requests : 0D;
        }

        public double truncationRate() {
            return requests > 0 ? (double) truncated / requests : 0D;
        }
    }

    public Totals totals(LocalDateTime start, LocalDateTime end) {
        List<Object[]> rows;
        if (start == null) {
            rows = repository.sumTotals();
        } else if (end == null) {
            // 开放区间（如"本月至今"）：不能走 Between，否则 created_at < NULL 恒为未知
            rows = repository.sumTotalsSince(start);
        } else {
            rows = repository.sumTotalsBetween(start, end);
        }
        return rows.isEmpty() ? new Totals(0L, 0D) : toTotals(rows.get(0));
    }

    public long unpricedTokens(LocalDateTime start, LocalDateTime end) {
        return start == null ? repository.sumUnpricedTokens() : repository.sumUnpricedTokensBetween(start, end);
    }

    public List<Breakdown> byScene(LocalDateTime start, LocalDateTime end, int limit) {
        return map(start == null ? repository.groupByScene() : repository.groupBySceneBetween(start, end), limit);
    }

    public List<Breakdown> byModel(LocalDateTime start, LocalDateTime end, int limit) {
        return map(start == null ? repository.groupByModel() : repository.groupByModelBetween(start, end), limit);
    }

    public List<Breakdown> byUser(LocalDateTime start, LocalDateTime end, int limit) {
        return map(start == null ? repository.groupByUser() : repository.groupByUserBetween(start, end), limit);
    }

    public List<Breakdown> byBook(LocalDateTime start, LocalDateTime end, int limit) {
        return map(start == null ? repository.groupByBook() : repository.groupByBookBetween(start, end), limit);
    }

    public Quality quality(LocalDateTime start, LocalDateTime end) {
        List<Object[]> rows = start == null ? repository.qualityTotals() : repository.qualityBetween(start, end);
        if (rows.isEmpty()) {
            return new Quality(0, 0, 0D, 0L, 0L);
        }
        Object[] row = rows.get(0);
        long requests = number(row[0]);
        long success = row[1] != null ? ((Number) row[1]).longValue() : 0L;
        double avgLatency = row[2] != null ? ((Number) row[2]).doubleValue() : 0D;
        long truncated = row[3] != null ? ((Number) row[3]).longValue() : 0L;
        long latencyCount = row[4] != null ? ((Number) row[4]).longValue() : 0L;

        long p95 = 0L;
        if (latencyCount > 0) {
            long offset = Math.min(latencyCount - 1, (long) Math.floor(latencyCount * 0.95));
            Integer value = start == null
                    ? repository.p95Latency(offset)
                    : repository.p95LatencyBetween(start, end, offset);
            p95 = value != null ? value : 0L;
        }
        return new Quality(requests, success, Math.round(avgLatency), p95, truncated);
    }

    private Totals toTotals(Object[] row) {
        long tokens = number(row[0]);
        double cost = row[1] != null ? ((Number) row[1]).doubleValue() : 0D;
        return new Totals(tokens, cost);
    }

    private List<Breakdown> map(List<Object[]> rows, int limit) {
        return rows.stream()
                .limit(limit)
                .map(r -> new Breakdown(
                        r[0] != null ? r[0].toString() : "unknown",
                        number(r[1]),
                        r[2] != null ? ((Number) r[2]).doubleValue() : 0D,
                        number(r[3])))
                .toList();
    }

    private long number(Object value) {
        return value != null ? ((Number) value).longValue() : 0L;
    }
}
