package com.kbook.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单书读者画像响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookProfileVO {

    private Long bookId;
    private String bookTitle;
    /** 读者口径：READ / TOUCH / DEEP */
    private String basis;
    /** 统计范围天数；全部为 0，自定义区间为实际跨度 */
    private int rangeDays;
    private String rangeStart;
    private String rangeEnd;

    /** 读者总数（已按 status=APPROVED 过滤、按 user_id 去重） */
    private long readers;
    /** 触发小样本抑制的阈值，随配置下发，前端文案不硬编码 */
    private int minCohortSize;
    private boolean sampleProtected;
    /** 至少填写了一个画像字段的读者数 */
    private long profiledUsers;
    /** 全站基线人数（APPROVED 用户数） */
    private long siteReaders;
    private String basisNote;

    private List<Dimension> dimensions;
    private List<String> highlights;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Dimension {
        private String key;
        private String label;
        /** 有效样本 / 读者总数 */
        private double coverage;
        private long unknownCount;
        private long dirtyCount;
        /** 多值维度：一人进多桶，各桶占比之和可 > 100% */
        private boolean multiValue;
        /** 该维度的桶是否可作为反查（榜单）下钻条件 */
        private boolean queryable;
        /** 只含合法取值桶；未填写与脏值通过 unknownCount / dirtyCount 表达 */
        private List<Bucket> buckets;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Bucket {
        /** DB 存储值，反查接口按它下钻；并桶「其他」为 null */
        private String value;
        private String label;
        private long count;
        private double ratio;
        private Double siteRatio;
        private Double lift;
        private boolean significant;
    }
}
