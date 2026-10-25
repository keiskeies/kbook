package com.kbook.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * P3 单书版受众吻合度：书侧声称的受众 vs 实际读者分布（口径见设计文档附录 Z）。
 *
 * 字段刻意只给数据不给文案：错配的一句话结论由前端拼（"标的是亲子，实际 60% 未婚"），
 * 这样文案改动不需要动接口，也不必在两端各维护一份中文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookAudienceFitVO {

    private Long bookId;
    private String bookTitle;
    private String basis;
    private int rangeDays;
    private String rangeStart;
    private String rangeEnd;

    private long readers;
    private int minCohortSize;
    private boolean sampleProtected;
    private String basisNote;

    /** 书侧标签总数（{@code target_reader_tags} 拆分后） */
    private int claimedTags;
    /** 其中能落到画像桶、实际参与吻合度计算的桶数 */
    private int claimedAudience;
    /** 剩余标签的分类计数，分开记而不是静默丢弃（附录 E.2 的硬要求） */
    private int interestTags;
    private int genericTags;
    private int noBucketTags;
    private int unrecognizedTags;

    private List<Fit> fits;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Fit {
        private String dimKey;
        private String dimLabel;
        /** DB 存储值 */
        private String token;
        private String tokenLabel;
        /** 人数与占比：不足 min-bucket-size 时一起为 null（§4 规则 5，附录 AG） */
        private Long count;
        private Double ratio;
        private Double siteRatio;
        private Double lift;
        private boolean significant;
        /** ALIGNED / MISMATCH / NOT_ENOUGH_SAMPLE / NO_DATA */
        private String verdict;
        /** 主导桶：只有人数达到 min-bucket-size 时三者一起发布，否则一起为 null（§4 规则 5） */
        private String topTokenLabel;
        private Long topCount;
        private Double topRatio;
    }
}
