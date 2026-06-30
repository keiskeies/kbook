package com.kbook.service.admin;

import com.kbook.dto.admin.DashboardResponse;
import com.kbook.dto.admin.DashboardResponse.*;
import com.kbook.entity.User;
import com.kbook.repository.*;
import com.kbook.repository.debate.DebateSessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static com.kbook.common.util.QueryBuilder.eq;
import static com.kbook.common.util.QueryBuilder.ge;

/**
 * 管理后台仪表盘服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final EntityManager em;
    private final UserRepository userRepository;
    private final BookRepository bookRepository;
    private final DebateSessionRepository debateSessionRepository;
    private final RoundTableSessionRepository roundTableSessionRepository;

    /**
     * 获取完整仪表盘数据
     */
    public DashboardResponse getDashboard() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime weekAgo = now.minusDays(7);

        return DashboardResponse.builder()
                .overview(getOverview(now, weekAgo))
                .featureUsage(getFeatureUsage(now, weekAgo))
                .contentHeat(getContentHeat())
                .costMonitor(getCostMonitor(now, weekAgo))
                .userProfile(getUserProfile())
                .build();
    }

    // ==================== 平台健康度 ====================

    private Overview getOverview(LocalDateTime now, LocalDateTime weekAgo) {
        long totalUsers = userRepository.count();
        long weeklyNewUsers = userRepository.query()
                .where(User::getCreatedAt, ge(weekAgo))
                .value();
        long weeklyActiveUsers = getWeeklyActiveUsers(weekAgo);
        long totalBooks = bookRepository.count();
        long embeddedBooks = bookRepository.countByContentEmbeddedTrue();

        return Overview.builder()
                .totalUsers(totalUsers)
                .weeklyNewUsers(weeklyNewUsers)
                .weeklyActiveUsers(weeklyActiveUsers)
                .totalBooks(totalBooks)
                .embeddedBooks(embeddedBooks)
                .build();
    }

    private long getWeeklyActiveUsers(LocalDateTime weekAgo) {
        // 从对话、辩论、圆桌三个表去重统计活跃用户
        String sql = "SELECT COUNT(DISTINCT uid) FROM (" +
                "  SELECT user_id AS uid FROM ai_sessions WHERE created_at >= :since " +
                "  UNION " +
                "  SELECT user_id AS uid FROM debate_sessions WHERE created_at >= :since " +
                "  UNION " +
                "  SELECT user_id AS uid FROM round_table_sessions WHERE created_at >= :since" +
                ") t";
        Query q = em.createNativeQuery(sql);
        q.setParameter("since", weekAgo);
        return ((Number) q.getSingleResult()).longValue();
    }

    // ==================== 功能使用 ====================

    private FeatureUsage getFeatureUsage(LocalDateTime now, LocalDateTime weekAgo) {
        // 各功能总使用次数
        List<FeatureCount> features = new ArrayList<>();
        features.add(new FeatureCount("AI 问答", getChatSessionCount()));
        features.add(new FeatureCount("圆桌讨论", roundTableSessionRepository.count()));
        features.add(new FeatureCount("AI 辩论", debateSessionRepository.count()));

        // 近 7 天趋势
        List<DailyTrend> trend = getWeeklyTrend(weekAgo);

        // 平均对话轮数
        double avgRounds = getAvgChatRounds();

        // 辩论完成率
        long totalDebates = debateSessionRepository.count();
        long completedDebates = getDebateCompletedCount();
        double completionRate = totalDebates > 0 ? (double) completedDebates / totalDebates : 0;

        return FeatureUsage.builder()
                .features(features)
                .trend(trend)
                .avgChatRounds(avgRounds)
                .debateCompletionRate(completionRate)
                .build();
    }

    private long getChatSessionCount() {
        String sql = "SELECT COUNT(DISTINCT session_id) FROM ai_sessions WHERE type = 'BOOK'";
        Query q = em.createNativeQuery(sql);
        return ((Number) q.getSingleResult()).longValue();
    }

    private List<DailyTrend> getWeeklyTrend(LocalDateTime weekAgo) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");

        // AI 问答按天
        String chatSql = "SELECT DATE(created_at) AS d, COUNT(DISTINCT session_id) " +
                "FROM ai_sessions WHERE type = 'BOOK' AND created_at >= :since GROUP BY d ORDER BY d";
        Map<String, Long> chatByDay = executeCountByDay(chatSql, weekAgo);

        // 辩论按天
        String debateSql = "SELECT DATE(created_at) AS d, COUNT(*) FROM debate_sessions " +
                "WHERE created_at >= :since GROUP BY d ORDER BY d";
        Map<String, Long> debateByDay = executeCountByDay(debateSql, weekAgo);

        // 圆桌按天
        String rtSql = "SELECT DATE(created_at) AS d, COUNT(*) FROM round_table_sessions " +
                "WHERE created_at >= :since GROUP BY d ORDER BY d";
        Map<String, Long> rtByDay = executeCountByDay(rtSql, weekAgo);

        // 合并 7 天数据
        List<DailyTrend> trend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            String date = LocalDate.now().minusDays(i).format(fmt);
            trend.add(DailyTrend.builder()
                    .date(date)
                    .chatCount(chatByDay.getOrDefault(date, 0L))
                    .debateCount(debateByDay.getOrDefault(date, 0L))
                    .roundTableCount(rtByDay.getOrDefault(date, 0L))
                    .build());
        }
        return trend;
    }

    private Map<String, Long> executeCountByDay(String sql, LocalDateTime since) {
        Query q = em.createNativeQuery(sql);
        q.setParameter("since", since);
        Map<String, Long> result = new HashMap<>();
        for (Object[] row : (List<Object[]>) q.getResultList()) {
            LocalDate d = row[0] instanceof LocalDate ? (LocalDate) row[0] :
                    LocalDate.parse(row[0].toString());
            result.put(d.format(DateTimeFormatter.ofPattern("MM-dd")),
                    ((Number) row[1]).longValue());
        }
        return result;
    }

    private double getAvgChatRounds() {
        String sql = "SELECT AVG(cnt) FROM (" +
                "  SELECT COUNT(*) AS cnt FROM ai_conversations " +
                "  WHERE role = 'user' GROUP BY session_id" +
                ") t";
        Query q = em.createNativeQuery(sql);
        Object result = q.getSingleResult();
        return result != null ? Math.round(((Number) result).doubleValue() * 10.0) / 10.0 : 0;
    }

    private long getDebateCompletedCount() {
        return debateSessionRepository.query()
                .where("status", eq("COMPLETED"))
                .value();
    }

    // ==================== 内容热度 ====================

    private ContentHeat getContentHeat() {
        // 热门图书：按讨论次数（问答+辩论+圆桌）聚合
        List<BookItem> hotBooks = getHotBooks(10);

        // 热门辩题
        List<DebateTopic> hotDebateTopics = getHotDebateTopics(10);

        return ContentHeat.builder()
                .hotBooks(hotBooks)
                .hotDebateTopics(hotDebateTopics)
                .build();
    }

    private List<BookItem> getHotBooks(int limit) {
        String sql = "SELECT book_id, COUNT(*) AS cnt FROM (" +
                "  SELECT book_id FROM ai_sessions WHERE book_id IS NOT NULL " +
                "  UNION ALL " +
                "  SELECT book_id FROM debate_sessions " +
                "  UNION ALL " +
                "  SELECT book_id FROM round_table_sessions" +
                ") t GROUP BY book_id ORDER BY cnt DESC LIMIT :limit";
        Query q = em.createNativeQuery(sql);
        q.setParameter("limit", limit);
        List<Object[]> rows = q.getResultList();

        List<BookItem> items = new ArrayList<>();
        for (Object[] row : rows) {
            Long bookId = ((Number) row[0]).longValue();
            long count = ((Number) row[1]).longValue();
            try {
                var book = bookRepository.findById(bookId).orElse(null);
                if (book != null) {
                    items.add(BookItem.builder()
                            .id(bookId)
                            .title(book.getTitle())
                            .author(book.getAuthor())
                            .discussionCount(count)
                            .rating(book.getRating())
                            .build());
                }
            } catch (Exception e) {
                // skip
            }
        }
        return items;
    }

    private List<DebateTopic> getHotDebateTopics(int limit) {
        String sql = "SELECT topic, COUNT(*) AS cnt FROM debate_sessions " +
                "GROUP BY topic ORDER BY cnt DESC LIMIT :limit";
        Query q = em.createNativeQuery(sql);
        q.setParameter("limit", limit);
        List<Object[]> rows = q.getResultList();

        return rows.stream()
                .map(r -> DebateTopic.builder()
                        .topic((String) r[0])
                        .count(((Number) r[1]).longValue())
                        .build())
                .collect(Collectors.toList());
    }

    // ==================== 成本监控 ====================

    private CostMonitor getCostMonitor(LocalDateTime now, LocalDateTime weekAgo) {
        long totalTokens = getTotalTokens();
        long weeklyTokens = getTokensSince(weekAgo);

        List<TokenByFeature> byFeature = List.of(
                new TokenByFeature("AI 问答", getTokensByType("BOOK")),
                new TokenByFeature("圆桌讨论", getTokensBySessionType("rt-")),
                new TokenByFeature("AI 辩论", getTokensBySessionType("db-"))
        );

        return CostMonitor.builder()
                .totalTokens(totalTokens)
                .weeklyTokens(weeklyTokens)
                .byFeature(byFeature)
                .build();
    }

    private long getTotalTokens() {
        String sql = "SELECT COALESCE(SUM(token_count), 0) FROM ai_conversations WHERE token_count IS NOT NULL";
        Query q = em.createNativeQuery(sql);
        return ((Number) q.getSingleResult()).longValue();
    }

    private long getTokensSince(LocalDateTime since) {
        String sql = "SELECT COALESCE(SUM(token_count), 0) FROM ai_conversations " +
                "WHERE token_count IS NOT NULL AND created_at >= :since";
        Query q = em.createNativeQuery(sql);
        q.setParameter("since", since);
        return ((Number) q.getSingleResult()).longValue();
    }

    private long getTokensByType(String type) {
        String sql = "SELECT COALESCE(SUM(token_count), 0) FROM ai_conversations " +
                "WHERE token_count IS NOT NULL AND type = :type";
        Query q = em.createNativeQuery(sql);
        q.setParameter("type", type);
        return ((Number) q.getSingleResult()).longValue();
    }

    private long getTokensBySessionType(String prefix) {
        // 圆桌和辩论的对话也存在 ai_conversations 中，session_id 以 rt- 或 db- 开头
        String sql = "SELECT COALESCE(SUM(c.token_count), 0) FROM ai_conversations c " +
                "WHERE c.token_count IS NOT NULL AND c.session_id LIKE :prefix%";
        Query q = em.createNativeQuery(sql);
        q.setParameter("prefix", prefix);
        return ((Number) q.getSingleResult()).longValue();
    }

    // ==================== 用户画像 ====================

    private UserProfile getUserProfile() {
        return UserProfile.builder()
                .mbtiDistribution(getGroupCount("mbti"))
                .genderDistribution(getGroupCount("gender"))
                .statusDistribution(getStatusCount())
                .build();
    }

    private Map<String, Long> getGroupCount(String column) {
        String sql = "SELECT " + column + ", COUNT(*) FROM users WHERE " + column +
                " IS NOT NULL GROUP BY " + column;
        Query q = em.createNativeQuery(sql);
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : (List<Object[]>) q.getResultList()) {
            String key = row[0] != null ? row[0].toString() : "unknown";
            result.put(key, ((Number) row[1]).longValue());
        }
        return result;
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
