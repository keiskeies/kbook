package com.kbook.service.admin;

import com.kbook.dto.admin.DashboardResponse;
import com.kbook.dto.admin.DashboardResponse.*;
import com.kbook.entity.AiScene;
import com.kbook.entity.User;
import com.kbook.repository.*;
import com.kbook.repository.debate.DebateSessionRepository;
import com.kbook.service.ai.BookChatScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 管理后台仪表盘服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    /** 历史会话的 type 曾使用 BOOK，当前写入值为 book_chat，聚合时两者都算（定义见 {@link BookChatScope}） */
    private static final String CHAT_TYPE_FILTER = BookChatScope.sqlFilter("");
    public static final int MAX_CUSTOM_RANGE_DAYS = 366;

    private final EntityManager em;
    private final UserRepository userRepository;
    private final BookRepository bookRepository;
    private final DebateSessionRepository debateSessionRepository;
    private final RoundTableSessionRepository roundTableSessionRepository;
    private final AiUsageStatsService usageStatsService;
    private final AiBudgetService budgetService;

    @Value("${kbook.ai.usage-retention-days:365}")
    private int retentionDays;

    /**
     * 获取完整仪表盘数据
     *
     * @param days  预设范围：7 / 30 / 90 / 0（全部）
     * @param start 自定义开始日期（与 end 同时传入时优先于 days）
     * @param end   自定义结束日期
     */
    public DashboardResponse getDashboard(int days, LocalDate start, LocalDate end) {
        DateRange range = resolveRange(days, start, end);
        DateRange previous = range.previous();

        FeatureUsage featureUsage = getFeatureUsage(range, previous);
        ContentHeat contentHeat = getContentHeat(range, previous);
        CostMonitor costMonitor = getCostMonitor(range);

        return DashboardResponse.builder()
                .rangeDays(range.allTime() ? 0 : range.days())
                .rangeStart(range.startDate() != null ? range.startDate().toString() : null)
                .rangeEnd(range.endDate() != null ? range.endDate().toString() : null)
                .overview(getOverview(range))
                .featureUsage(featureUsage)
                .contentHeat(contentHeat)
                .costMonitor(costMonitor)
                .userProfile(getUserProfile())
                .insights(buildInsights(range, featureUsage, contentHeat, costMonitor))
                .build();
    }

    private DateRange resolveRange(int days, LocalDate start, LocalDate end) {
        AdminRangeRules.Range range = AdminRangeRules.resolve(start, end, days, LocalDate.now());
        return new DateRange(range.startDate(), range.endDate());
    }

    /**
     * 区间定义：startDate 为空表示全部时间；endDate 为闭区间最后一天。
     */
    private record DateRange(LocalDate startDate, LocalDate endDate) {
        boolean allTime() {
            return startDate == null;
        }

        LocalDateTime start() {
            return startDate != null ? startDate.atStartOfDay() : null;
        }

        LocalDateTime endExclusive() {
            return endDate != null ? endDate.plusDays(1).atStartOfDay() : null;
        }

        int days() {
            return startDate != null ? (int) (ChronoUnit.DAYS.between(startDate, endDate) + 1) : 0;
        }

        DateRange previous() {
            if (allTime()) {
                return null;
            }
            int span = days();
            LocalDate prevEnd = startDate.minusDays(1);
            return new DateRange(prevEnd.minusDays(span - 1L), prevEnd);
        }
    }

    // ==================== 平台健康度 ====================

    private Overview getOverview(DateRange range) {
        long totalUsers = userRepository.count();
        long newUsers = range.allTime() ? totalUsers : executeCount(
                "SELECT COUNT(*) FROM users WHERE created_at >= :start AND created_at < :end", range);
        long activeUsers = getActiveUsers(range);
        long totalBooks = bookRepository.count();
        long embeddedBooks = bookRepository.countByContentEmbeddedTrue();

        return Overview.builder()
                .totalUsers(totalUsers)
                .newUsers(newUsers)
                .activeUsers(activeUsers)
                .totalBooks(totalBooks)
                .embeddedBooks(embeddedBooks)
                .build();
    }

    private long getActiveUsers(DateRange range) {
        String filter = range.allTime() ? "" : " WHERE created_at >= :start AND created_at < :end";
        String sql = "SELECT COUNT(DISTINCT uid) FROM (" +
                "  SELECT user_id AS uid FROM ai_sessions" + filter +
                "  UNION " +
                "  SELECT user_id AS uid FROM debate_sessions" + filter +
                "  UNION " +
                "  SELECT user_id AS uid FROM round_table_sessions" + filter +
                ") t";
        Query q = em.createNativeQuery(sql);
        bindRange(q, range);
        return ((Number) q.getSingleResult()).longValue();
    }

    // ==================== 功能使用 ====================

    private FeatureUsage getFeatureUsage(DateRange range, DateRange previous) {
        long chat = countChatSessions(range);
        long roundTable = countSessions("round_table_sessions", range);
        long debate = countSessions("debate_sessions", range);

        List<FeatureCount> features = new ArrayList<>();
        features.add(new FeatureCount("AI 问答", chat,
                previous != null ? countChatSessions(previous) : -1));
        features.add(new FeatureCount("圆桌讨论", roundTable,
                previous != null ? countSessions("round_table_sessions", previous) : -1));
        features.add(new FeatureCount("AI 辩论", debate,
                previous != null ? countSessions("debate_sessions", previous) : -1));

        TrendGranularity granularity = resolveGranularity(range);
        List<DailyTrend> trend = getTrend(range, granularity);

        return FeatureUsage.builder()
                .features(features)
                .trend(trend)
                .trendGranularity(granularity.name())
                .avgChatRounds(getAvgChatRounds(range))
                .debateCompletionRate(getDebateCompletionRate(range))
                .build();
    }

    private long countChatSessions(DateRange range) {
        String sql = "SELECT COUNT(DISTINCT session_id) FROM ai_sessions WHERE " + CHAT_TYPE_FILTER
                + andRangeSql(range);
        return executeCount(sql, range);
    }

    private long countSessions(String table, DateRange range) {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE 1 = 1" + andRangeSql(range);
        return executeCount(sql, range);
    }

    private long executeCount(String sql, DateRange range) {
        Query q = em.createNativeQuery(sql);
        bindRange(q, range);
        return ((Number) q.getSingleResult()).longValue();
    }

    private double getAvgChatRounds(DateRange range) {
        String sql = "SELECT AVG(cnt) FROM (" +
                "  SELECT COUNT(*) AS cnt FROM ai_conversations " +
                "  WHERE role = 'user' AND " + CHAT_TYPE_FILTER + andRangeSql(range) +
                "  GROUP BY session_id" +
                ") t";
        Query q = em.createNativeQuery(sql);
        bindRange(q, range);
        Object result = q.getSingleResult();
        return result != null ? Math.round(((Number) result).doubleValue() * 10.0) / 10.0 : 0;
    }

    private double getDebateCompletionRate(DateRange range) {
        String sql = "SELECT COUNT(*), SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) " +
                "FROM debate_sessions WHERE 1 = 1" + andRangeSql(range);
        Query q = em.createNativeQuery(sql);
        bindRange(q, range);
        Object[] row = (Object[]) q.getSingleResult();
        long total = ((Number) row[0]).longValue();
        long completed = row[1] != null ? ((Number) row[1]).longValue() : 0;
        return total > 0 ? (double) completed / total : 0;
    }

    // ==================== 趋势 ====================

    private enum TrendGranularity {
        DAY, WEEK, MONTH
    }

    private TrendGranularity resolveGranularity(DateRange range) {
        if (range.allTime()) return TrendGranularity.MONTH;
        int days = range.days();
        if (days <= 31) return TrendGranularity.DAY;
        if (days <= 180) return TrendGranularity.WEEK;
        return TrendGranularity.MONTH;
    }

    private record TrendBucket(String label, LocalDate start, LocalDate end) {
    }

    private List<DailyTrend> getTrend(DateRange range, TrendGranularity granularity) {
        // 全部时间：趋势固定展示近 12 个月，避免把全部历史压成一张图
        DateRange trendRange = range.allTime()
                ? new DateRange(YearMonth.now().minusMonths(11).atDay(1), LocalDate.now())
                : range;

        String chatSql = "SELECT DATE(created_at), COUNT(DISTINCT session_id) FROM ai_sessions " +
                "WHERE " + CHAT_TYPE_FILTER + andRangeSql(trendRange) + " GROUP BY DATE(created_at)";
        Map<LocalDate, Long> chatByDay = executeCountByDay(chatSql, trendRange);

        String debateSql = "SELECT DATE(created_at), COUNT(*) FROM debate_sessions " +
                "WHERE 1 = 1" + andRangeSql(trendRange) + " GROUP BY DATE(created_at)";
        Map<LocalDate, Long> debateByDay = executeCountByDay(debateSql, trendRange);

        String rtSql = "SELECT DATE(created_at), COUNT(*) FROM round_table_sessions " +
                "WHERE 1 = 1" + andRangeSql(trendRange) + " GROUP BY DATE(created_at)";
        Map<LocalDate, Long> rtByDay = executeCountByDay(rtSql, trendRange);

        return buildBuckets(granularity, trendRange).stream()
                .map(b -> DailyTrend.builder()
                        .date(b.label())
                        .chatCount(sum(chatByDay, b.start(), b.end()))
                        .debateCount(sum(debateByDay, b.start(), b.end()))
                        .roundTableCount(sum(rtByDay, b.start(), b.end()))
                        .build())
                .collect(Collectors.toList());
    }

    private Map<LocalDate, Long> executeCountByDay(String sql, DateRange range) {
        Query q = em.createNativeQuery(sql);
        bindRange(q, range);
        Map<LocalDate, Long> result = new HashMap<>();
        for (Object[] row : (List<Object[]>) q.getResultList()) {
            if (row[0] == null) continue;
            LocalDate date = row[0] instanceof LocalDate
                    ? (LocalDate) row[0] : LocalDate.parse(row[0].toString());
            result.put(date, ((Number) row[1]).longValue());
        }
        return result;
    }

    private long sum(Map<LocalDate, Long> daily, LocalDate start, LocalDate end) {
        long total = 0L;
        for (Map.Entry<LocalDate, Long> entry : daily.entrySet()) {
            LocalDate date = entry.getKey();
            if (!date.isBefore(start) && !date.isAfter(end)) {
                total += entry.getValue();
            }
        }
        return total;
    }

    private List<TrendBucket> buildBuckets(TrendGranularity granularity, DateRange range) {
        List<TrendBucket> buckets = new ArrayList<>();
        DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("MM-dd");
        LocalDate start = range.startDate();
        LocalDate end = range.endDate();

        switch (granularity) {
            case DAY -> {
                for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                    buckets.add(new TrendBucket(d.format(dayFmt), d, d));
                }
            }
            case WEEK -> {
                LocalDate cursor = mondayOfWeek(start);
                while (!cursor.isAfter(end)) {
                    // 首尾桶按区间实际边界裁剪，否则首桶会把范围外的天数也算进周标签
                    LocalDate bucketStart = cursor.isBefore(start) ? start : cursor;
                    LocalDate weekEnd = cursor.plusDays(6);
                    LocalDate bucketEnd = weekEnd.isAfter(end) ? end : weekEnd;
                    buckets.add(new TrendBucket(bucketStart.format(dayFmt), bucketStart, bucketEnd));
                    cursor = cursor.plusWeeks(1);
                }
            }
            case MONTH -> {
                YearMonth cursor = YearMonth.from(start);
                YearMonth endMonth = YearMonth.from(end);
                while (!cursor.isAfter(endMonth)) {
                    LocalDate monthStart = cursor.atDay(1);
                    LocalDate monthEnd = cursor.atEndOfMonth();
                    LocalDate bucketStart = monthStart.isBefore(start) ? start : monthStart;
                    LocalDate bucketEnd = monthEnd.isAfter(end) ? end : monthEnd;
                    buckets.add(new TrendBucket(cursor.format(DateTimeFormatter.ofPattern("yy/MM")),
                            bucketStart, bucketEnd));
                    cursor = cursor.plusMonths(1);
                }
            }
        }
        return buckets;
    }

    private LocalDate mondayOfWeek(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    // ==================== 内容热度 ====================

    private ContentHeat getContentHeat(DateRange range, DateRange previous) {
        return ContentHeat.builder()
                .hotBooks(getHotBooks(10, range, previous))
                .hotDebateTopics(getHotDebateTopics(10, range))
                .build();
    }

    private List<BookItem> getHotBooks(int limit, DateRange range, DateRange previous) {
        String rangeFilter = andRangeSql(range);
        String srcSql = "  SELECT book_id, 'chat' AS src FROM ai_sessions " +
                "WHERE book_id IS NOT NULL AND " + CHAT_TYPE_FILTER + rangeFilter +
                "  UNION ALL " +
                "  SELECT book_id, 'debate' AS src FROM debate_sessions " +
                "WHERE book_id IS NOT NULL" + rangeFilter +
                "  UNION ALL " +
                "  SELECT book_id, 'rt' AS src FROM round_table_sessions " +
                "WHERE book_id IS NOT NULL" + rangeFilter;

        String sql = "SELECT book_id, " +
                "SUM(CASE WHEN src = 'chat' THEN 1 ELSE 0 END) AS qa_cnt, " +
                "SUM(CASE WHEN src = 'debate' THEN 1 ELSE 0 END) AS debate_cnt, " +
                "SUM(CASE WHEN src = 'rt' THEN 1 ELSE 0 END) AS rt_cnt, " +
                "COUNT(*) AS total_cnt " +
                "FROM (" + srcSql + ") t " +
                "GROUP BY book_id ORDER BY qa_cnt DESC, total_cnt DESC, book_id ASC LIMIT :limit";

        Query q = em.createNativeQuery(sql);
        q.setParameter("limit", limit);
        bindRange(q, range);
        List<Object[]> rows = q.getResultList();

        Map<Long, Long> previousQa = previous != null ? getQaCountsByBook(previous) : Map.of();

        List<BookItem> items = new ArrayList<>();
        for (Object[] row : rows) {
            Long bookId = ((Number) row[0]).longValue();
            try {
                var book = bookRepository.findById(bookId).orElse(null);
                if (book != null) {
                    items.add(BookItem.builder()
                            .id(bookId)
                            .title(book.getTitle())
                            .author(book.getAuthor())
                            .qaCount(((Number) row[1]).longValue())
                            .debateCount(((Number) row[2]).longValue())
                            .roundTableCount(((Number) row[3]).longValue())
                            .discussionCount(((Number) row[4]).longValue())
                            .previousQaCount(previous != null ? previousQa.getOrDefault(bookId, 0L) : null)
                            .rating(book.getRating())
                            .build());
                }
            } catch (Exception e) {
                // skip
            }
        }
        return items;
    }

    private Map<Long, Long> getQaCountsByBook(DateRange range) {
        String sql = "SELECT book_id, COUNT(*) FROM ai_sessions " +
                "WHERE book_id IS NOT NULL AND " + CHAT_TYPE_FILTER + andRangeSql(range) +
                " GROUP BY book_id";
        Query q = em.createNativeQuery(sql);
        bindRange(q, range);
        Map<Long, Long> result = new HashMap<>();
        for (Object[] row : (List<Object[]>) q.getResultList()) {
            result.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return result;
    }

    private List<DebateTopic> getHotDebateTopics(int limit, DateRange range) {
        String sql = "SELECT topic, COUNT(*) AS cnt FROM debate_sessions " +
                "WHERE 1 = 1" + andRangeSql(range) +
                " GROUP BY topic ORDER BY cnt DESC LIMIT :limit";
        Query q = em.createNativeQuery(sql);
        q.setParameter("limit", limit);
        bindRange(q, range);
        List<Object[]> rows = q.getResultList();

        return rows.stream()
                .map(r -> DebateTopic.builder()
                        .topic((String) r[0])
                        .count(((Number) r[1]).longValue())
                        .build())
                .collect(Collectors.toList());
    }

    // ==================== 成本监控 ====================

    private CostMonitor getCostMonitor(DateRange range) {
        LocalDateTime start = range.start();
        LocalDateTime end = range.endExclusive();

        AiUsageStatsService.Totals totals = usageStatsService.totals(start, end);
        AiUsageStatsService.Totals allTotals = usageStatsService.totals(null, null);
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        AiUsageStatsService.Totals monthTotals = usageStatsService.totals(monthStart, null);
        AiUsageStatsService.Quality quality = usageStatsService.quality(start, end);

        Map<String, Long> tokenByFeature = new LinkedHashMap<>();
        tokenByFeature.put("图书问答", 0L);
        tokenByFeature.put("圆桌讨论", 0L);
        tokenByFeature.put("AI 辩论", 0L);
        tokenByFeature.put("AI 助理", 0L);
        tokenByFeature.put("其他 AI", 0L);
        List<UsageBreakdown> sceneRows = toBreakdowns(usageStatsService.byScene(start, end, 50));
        for (UsageBreakdown row : sceneRows) {
            tokenByFeature.merge(categorizeScene(row.getKey()), row.getTokens(), Long::sum);
        }
        List<TokenByFeature> byFeature = tokenByFeature.entrySet().stream()
                .map(e -> new TokenByFeature(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        List<UsageBreakdown> byScene = sceneRows.stream()
                .map(r -> {
                    r.setLabel(sceneLabel(r.getKey()));
                    return r;
                })
                .collect(Collectors.toList());

        List<UsageBreakdown> byModel = toBreakdowns(usageStatsService.byModel(start, end, 20));
        List<UsageBreakdown> topUsers = enrichUsers(toBreakdowns(usageStatsService.byUser(start, end, 10)));
        List<UsageBreakdown> topBooks = enrichBooks(toBreakdowns(usageStatsService.byBook(start, end, 10)));

        return CostMonitor.builder()
                .totalTokens(allTotals.tokens())
                .rangeTokens(totals.tokens())
                .monthTokens(monthTotals.tokens())
                .totalCost(round6(allTotals.cost()))
                .rangeCost(round6(totals.cost()))
                .monthCost(round6(monthTotals.cost()))
                .unpricedTokens(usageStatsService.unpricedTokens(start, end))
                .quality(QualityMetrics.builder()
                        .requests(quality.requests())
                        .successRate(round4(quality.successRate()))
                        .avgLatencyMs(quality.avgLatencyMs())
                        .p95LatencyMs(quality.p95LatencyMs())
                        .truncationRate(round4(quality.truncationRate()))
                        .build())
                .byFeature(byFeature)
                .byScene(byScene)
                .byModel(byModel)
                .topUsers(topUsers)
                .topBooks(topBooks)
                .budget(budgetService.getStatus())
                .retentionDays(retentionDays)
                .build();
    }

    private List<UsageBreakdown> toBreakdowns(List<AiUsageStatsService.Breakdown> rows) {
        return rows.stream()
                .map(r -> UsageBreakdown.builder()
                        .key(r.key())
                        .label(r.key())
                        .tokens(r.tokens())
                        .cost(round6(r.cost()))
                        .requests(r.requests())
                        .build())
                .collect(Collectors.toList());
    }

    private List<UsageBreakdown> enrichUsers(List<UsageBreakdown> rows) {
        List<Long> ids = rows.stream().map(r -> parseLong(r.getKey())).filter(Objects::nonNull).toList();
        Map<Long, User> users = userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        for (UsageBreakdown row : rows) {
            User user = users.get(parseLong(row.getKey()));
            if (user != null) {
                row.setLabel(user.getNickname() != null && !user.getNickname().isBlank()
                        ? user.getNickname() : "用户 " + user.getId());
                row.setSubLabel(user.getEmail());
            }
        }
        return rows;
    }

    private List<UsageBreakdown> enrichBooks(List<UsageBreakdown> rows) {
        List<Long> ids = rows.stream().map(r -> parseLong(r.getKey())).filter(Objects::nonNull).toList();
        Map<Long, com.kbook.entity.Book> books = bookRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(com.kbook.entity.Book::getId, b -> b));
        for (UsageBreakdown row : rows) {
            com.kbook.entity.Book book = books.get(parseLong(row.getKey()));
            if (book != null) {
                row.setLabel(book.getTitle());
                row.setSubLabel(book.getAuthor());
            }
        }
        return rows;
    }

    // ==================== 自动洞察 ====================

    private List<String> buildInsights(DateRange range, FeatureUsage featureUsage,
                                       ContentHeat contentHeat, CostMonitor costMonitor) {
        List<String> insights = new ArrayList<>();
        String period = range.allTime() ? "累计" : "本期";

        FeatureCount chat = featureUsage.getFeatures().stream()
                .filter(f -> "AI 问答".equals(f.getName()))
                .findFirst().orElse(null);
        if (chat != null) {
            if (chat.getPreviousCount() >= 0 && chat.getPreviousCount() > 0) {
                double delta = (chat.getCount() - chat.getPreviousCount()) * 100.0 / chat.getPreviousCount();
                insights.add("AI 问答 " + chat.getCount() + " 次，较上期 "
                        + (delta >= 0 ? "+" : "") + String.format("%.1f", delta) + "%");
            } else if (chat.getPreviousCount() == 0) {
                insights.add("AI 问答 " + chat.getCount() + " 次，上期无问答");
            } else {
                insights.add(period + " AI 问答 " + chat.getCount() + " 次");
            }

            if (!contentHeat.getHotBooks().isEmpty() && chat.getCount() > 0) {
                BookItem top = contentHeat.getHotBooks().get(0);
                long share = Math.round(top.getQaCount() * 100.0 / chat.getCount());
                insights.add("最热图书《" + top.getTitle() + "》问答 " + top.getQaCount()
                        + " 次，占" + period + "问答 " + share + "%");
            }
        }

        FeatureCount debate = featureUsage.getFeatures().stream()
                .filter(f -> "AI 辩论".equals(f.getName()))
                .findFirst().orElse(null);
        if (debate != null && debate.getCount() > 0) {
            insights.add("AI 辩论 " + debate.getCount() + " 场，完成率 "
                    + String.format("%.1f", featureUsage.getDebateCompletionRate() * 100) + "%");
        }

        if (costMonitor.getBudget() != null && costMonitor.getBudget().getMonthlyBudget() > 0) {
            insights.add("本月 AI 成本 " + moneyText(costMonitor.getMonthCost())
                    + "，预算已用 " + costMonitor.getBudget().getUsedPercent() + "%");
        } else {
            insights.add("本月 AI 成本 " + moneyText(costMonitor.getMonthCost()));
        }
        return insights;
    }

    private String moneyText(double value) {
        return value >= 1 ? String.format("¥%.2f", value) : String.format("¥%.4f", value);
    }

    // ==================== 工具 ====================

    private String andRangeSql(DateRange range) {
        if (range == null || range.allTime()) {
            return "";
        }
        return " AND created_at >= :start AND created_at < :end";
    }

    private void bindRange(Query query, DateRange range) {
        if (range != null && !range.allTime()) {
            query.setParameter("start", range.start());
            query.setParameter("end", range.endExclusive());
        }
    }

    private String sceneLabel(String scene) {
        try {
            return AiScene.valueOf(scene).getDisplayName();
        } catch (Exception e) {
            return scene;
        }
    }

    private Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return null;
        }
    }

    private double round6(double value) {
        return Math.round(value * 1_000_000D) / 1_000_000D;
    }

    private double round4(double value) {
        return Math.round(value * 10000D) / 10000D;
    }

    private String categorizeScene(String scene) {
        if ("BOOK_QA".equals(scene) || "BOOK_QA_SEGMENT".equals(scene)) {
            return "图书问答";
        }
        if (scene.startsWith("ROUND_TABLE")) {
            return "圆桌讨论";
        }
        if (scene.startsWith("DEBATE")) {
            return "AI 辩论";
        }
        if ("AI_ASSISTANT".equals(scene) || "ADMIN_ASSISTANT".equals(scene)) {
            return "AI 助理";
        }
        return "其他 AI";
    }

    // ==================== 用户画像 ====================

    private UserProfile getUserProfile() {
        return UserProfile.builder()
                .mbtiDistribution(siteDistribution(BookProfileDimension.MBTI_TYPE))
                .genderDistribution(siteDistribution(BookProfileDimension.GENDER))
                .statusDistribution(getStatusCount())
                .build();
    }

    /**
     * 全站画像分布：与读者画像共用「谁算读者」（APPROVED）与白名单归一化，判定只有一处。
     * statusDistribution 保留全账号口径，它回答的是账号审核状态，不是画像。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Long> siteDistribution(BookProfileDimension dim) {
        List<Object[]> rows = em.createNativeQuery(ProfileReaderSql.siteBucket(dim.getValueSql())).getResultList();
        return BookProfileCalculator.dashboardDistribution(dim, rows);
    }

    private Map<String, Long> getStatusCount() {
        List<Object[]> rows = userRepository.countGroupByStatus();
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            result.put((String) row[0], ((Number) row[1]).longValue());
        }
        return result;
    }
}
