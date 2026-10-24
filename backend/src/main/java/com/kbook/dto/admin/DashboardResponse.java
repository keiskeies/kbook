package com.kbook.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 管理后台仪表盘响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardResponse {

    /** 统计范围天数：7 / 30 / 90 / 0（全部），自定义区间为实际天数 */
    private int rangeDays;
    /** 自定义区间开始日期（yyyy-MM-dd）；全部时为 null */
    private String rangeStart;
    /** 自定义区间结束日期（yyyy-MM-dd）；全部时为 null */
    private String rangeEnd;
    /** 自动洞察文案 */
    private List<String> insights;

    /** 平台健康度 */
    private Overview overview;

    /** 功能使用统计 */
    private FeatureUsage featureUsage;

    /** 内容热度 */
    private ContentHeat contentHeat;

    /** 成本监控 */
    private CostMonitor costMonitor;

    /** 用户画像 */
    private UserProfile userProfile;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Overview {
        private long totalUsers;
        /** 区间新增用户 */
        private long newUsers;
        /** 区间活跃用户 */
        private long activeUsers;
        private long totalBooks;
        private long embeddedBooks;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeatureUsage {
        /** 各功能区间使用次数 */
        private List<FeatureCount> features;
        /** 区间趋势 */
        private List<DailyTrend> trend;
        /** 趋势粒度：DAY / WEEK / MONTH */
        private String trendGranularity;
        /** 平均对话轮数 */
        private double avgChatRounds;
        /** 辩论完成率 */
        private double debateCompletionRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeatureCount {
        private String name;
        private long count;
        /** 上一周期次数；-1 表示无可比周期（全部） */
        private long previousCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyTrend {
        private String date;
        private long chatCount;
        private long debateCount;
        private long roundTableCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContentHeat {
        private List<BookItem> hotBooks;
        private List<DebateTopic> hotDebateTopics;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BookItem {
        private Long id;
        private String title;
        private String author;
        /** 图书问答次数 */
        private long qaCount;
        /** 辩论场次 */
        private long debateCount;
        /** 圆桌场次 */
        private long roundTableCount;
        /** 总讨论次数 */
        private long discussionCount;
        /** 上一周期问答次数；null 表示无可比周期（全部） */
        private Long previousQaCount;
        private double rating;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DebateTopic {
        private String topic;
        private long count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CostMonitor {
        /** 累计 Token */
        private long totalTokens;
        /** 区间 Token */
        private long rangeTokens;
        /** 本月 Token */
        private long monthTokens;
        /** 累计费用（元） */
        private double totalCost;
        /** 区间费用（元） */
        private double rangeCost;
        /** 本月费用（元） */
        private double monthCost;
        /** 未配置单价对应的小计 Token（费用为 0，需提示） */
        private long unpricedTokens;
        /** 调用质量 */
        private QualityMetrics quality;
        private List<TokenByFeature> byFeature;
        /** 场景明细 */
        private List<UsageBreakdown> byScene;
        /** 模型明细 */
        private List<UsageBreakdown> byModel;
        /** 用户明细 */
        private List<UsageBreakdown> topUsers;
        /** 图书明细 */
        private List<UsageBreakdown> topBooks;
        /** 月预算状态 */
        private AiBudgetDTO budget;
        /** 明细保留天数；<=0 表示永久 */
        private int retentionDays;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualityMetrics {
        private long requests;
        private double successRate;
        private double avgLatencyMs;
        private long p95LatencyMs;
        private double truncationRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UsageBreakdown {
        /** 原始标识（scene / model / userId / bookId） */
        private String key;
        /** 展示名称 */
        private String label;
        /** 次级说明（如邮箱、作者） */
        private String subLabel;
        private long tokens;
        private double cost;
        private long requests;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenByFeature {
        private String name;
        private long tokens;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserProfile {
        private Map<String, Long> mbtiDistribution;
        private Map<String, Long> genderDistribution;
        private Map<String, Long> statusDistribution;
    }
}
