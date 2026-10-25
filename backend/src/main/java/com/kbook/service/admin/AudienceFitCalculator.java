package com.kbook.service.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P3 单书版：书侧声称的受众（{@link TargetAudienceRules} 归到画像桶的那些）与实际读者分布的吻合度。
 *
 * 口径（2026-10-25 用户拍板，见设计文档附录 Z）：
 * 对每个声称桶，比「本书该桶占比」与「全站该桶占比」——lift &gt;= 1 记吻合、&lt; 1 记错配；
 * 单格人数不足 min-bucket 或整本书读者不足 min-cohort 时不出版本，与画像分布共用同一套隐私闸门
 * （{@link BookProfileCalculator.Thresholds}），不另立第二套阈值。
 *
 * 纯函数：本书与全站的 {@code DimCounts} 由调用方按既有口径取好，本类不碰 EntityManager，
 * 这样口径边界可以逐条单测，而不必先有一张生产量级的表。
 */
public final class AudienceFitCalculator {

    private AudienceFitCalculator() {
    }

    public enum Verdict {
        /** 声称的桶在本书里占到全站水平的 1 倍以上 */
        ALIGNED,
        /** 声称的桶在本书里低于全站水平 */
        MISMATCH,
        /** 该桶人数不足 min-bucket-size，不单独给占比（隐私闸门） */
        NOT_ENOUGH_SAMPLE,
        /** 该维度没有有效样本，或全站该桶为 0，无法比较 */
        NO_DATA
    }

    /**
     * 主导桶（{@code topTokenLabel/topCount/topRatio}）只有在该桶人数达到 min-bucket-size 时才发布：
     * 否则"7 个人里 4 个是 X"就是一条绕过 §4 规则 5 的单格信息。三个字段要么一起有、要么一起为 null。
     *
     * @param lift ratio / siteRatio；两者缺一时为 null
     */
    public record Fit(String dimKey, String dimLabel, String token, String tokenLabel,
                      Long count, Double ratio, Double siteRatio, Double lift,
                      boolean significant, Verdict verdict,
                      String topTokenLabel, Long topCount, Double topRatio) {
    }

    public record Result(boolean sampleProtected, List<Fit> fits) {
    }

    public static Result compute(List<TargetAudienceRules.Bucket> claimed,
                                 Map<BookProfileDimension, BookProfileCalculator.DimCounts> book,
                                 Map<BookProfileDimension, BookProfileCalculator.DimCounts> site,
                                 long readers,
                                 BookProfileCalculator.Thresholds thresholds) {
        if (thresholds.isSampleProtected(readers)) {
            return new Result(true, List.of());
        }
        Map<String, Fit> unique = new LinkedHashMap<>();
        for (TargetAudienceRules.Bucket bucket : claimed) {
            String key = bucket.dim().name() + "|" + bucket.token();
            if (unique.containsKey(key)) {
                continue;
            }
            unique.put(key, fitOne(bucket.dim(), bucket.token(), book.get(bucket.dim()),
                    site.get(bucket.dim()), thresholds));
        }
        return new Result(false, List.copyOf(unique.values()));
    }

    private static Fit fitOne(BookProfileDimension dim, String token,
                              BookProfileCalculator.DimCounts book,
                              BookProfileCalculator.DimCounts site,
                              BookProfileCalculator.Thresholds thresholds) {
        String label = dim.getAllowed().getOrDefault(token, token);
        if (book == null || book.validSample() == 0) {
            return new Fit(dim.name(), dim.getLabel(), token, label,
                    null, null, null, null, false, Verdict.NO_DATA, null, null, null);
        }
        long count = book.buckets().getOrDefault(token, 0L);
        double ratio = (double) count / book.validSample();
        Double siteRatio = null;
        if (site != null && site.validSample() > 0) {
            long siteCount = site.buckets().getOrDefault(token, 0L);
            // 与 buildDimension 同一条：全站小格的比例不发布，否则 lift 的基线本身是小格
            if (thresholds.isCountPublishable(siteCount)) {
                siteRatio = (double) siteCount / site.validSample();
            }
        }
        Double lift = (siteRatio == null || siteRatio <= 0) ? null : ratio / siteRatio;
        // 零人永远不算显著：count==0 时 Wilson 的下界在小样本下会因数值效应略大于 0
        // （p=0、n=1 时约 2e-4），遇上全站该桶恰为 0 就会把"一个都没有"判成显著偏高。
        // 全站该桶为 0 时没有可比基线（lift 也是 null），同样不给显著标记。
        boolean publishable = thresholds.isCountPublishable(count);
        // 单格不足时，人数/占比/lift/显著一起不发布：单独留一个"4 人"或"显著"同样能反推出小群人
        boolean significant = publishable && count > 0 && siteRatio != null && siteRatio > 0
                && BookProfileCalculator.isSignificant(count, book.validSample(), siteRatio);

        String topToken = null;
        long topCount = -1;
        for (Map.Entry<String, Long> entry : book.buckets().entrySet()) {
            if (entry.getValue() > topCount) {
                topCount = entry.getValue();
                topToken = entry.getKey();
            }
        }
        // 主导桶也必须过 min-bucket-size 闸门：不足时整组字段为 null，前端就不渲染"实际最多"
        boolean topPublishable = topToken != null && thresholds.isCountPublishable(topCount);
        String topLabel = topPublishable ? dim.getAllowed().getOrDefault(topToken, topToken) : null;
        Long publishedTopCount = topPublishable ? topCount : null;
        Double topRatio = topPublishable ? round4((double) topCount / book.validSample()) : null;

        Verdict verdict;
        if (!publishable) {
            verdict = Verdict.NOT_ENOUGH_SAMPLE;
        } else if (lift == null) {
            verdict = Verdict.NO_DATA;
        } else {
            verdict = lift >= 1 ? Verdict.ALIGNED : Verdict.MISMATCH;
        }
        return new Fit(dim.name(), dim.getLabel(), token, label,
                publishable ? count : null,
                publishable ? round4(ratio) : null,
                siteRatio == null ? null : round4(siteRatio),
                publishable && lift != null ? round2(lift) : null,
                significant, verdict, topLabel, publishedTopCount, topRatio);
    }

    private static double round4(double value) {
        return Math.round(value * 10_000D) / 10_000D;
    }

    private static double round2(double value) {
        return Math.round(value * 100D) / 100D;
    }
}
