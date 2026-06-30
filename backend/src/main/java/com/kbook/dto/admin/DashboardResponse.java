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
        private long weeklyNewUsers;
        private long weeklyActiveUsers;
        private long totalBooks;
        private long embeddedBooks;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeatureUsage {
        /** 各功能使用次数 */
        private List<FeatureCount> features;
        /** 近 7 天趋势 */
        private List<DailyTrend> trend;
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
        private long discussionCount;
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
        private long totalTokens;
        private long weeklyTokens;
        private List<TokenByFeature> byFeature;
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
