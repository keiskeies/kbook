package com.kbook.service.admin;

import com.kbook.service.admin.AudienceFitCalculator.Fit;
import com.kbook.service.admin.AudienceFitCalculator.Result;
import com.kbook.service.admin.AudienceFitCalculator.Verdict;
import com.kbook.service.admin.BookProfileCalculator.DimCounts;
import com.kbook.service.admin.BookProfileCalculator.Thresholds;
import com.kbook.service.admin.TargetAudienceRules.Bucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 单书版吻合度的口径边界（设计文档附录 Z）。
 *
 * 隐私闸门用的是与画像分布同一份 {@link Thresholds}：读者不足 min-cohort 一份都不出，
 * 单格不足 min-bucket 不给占比。这两条如果在这里放松，等于开了一个绕过 §4 的后门。
 */
class AudienceFitCalculatorTest {

    private static final Thresholds DEFAULT = new Thresholds(10, 5, 10, 20);

    private static DimCounts counts(long valid, Map<String, Long> buckets) {
        long total = buckets.values().stream().mapToLong(Long::longValue).sum();
        return new DimCounts(total, valid, 0, 0, buckets);
    }

    private static Map<BookProfileDimension, DimCounts> gender(long female, long male) {
        return Map.of(BookProfileDimension.GENDER, counts(female + male, Map.of("FEMALE", female, "MALE", male)));
    }

    @Test
    @DisplayName("声称桶高于全站 → 吻合，并给出 ratio/siteRatio/lift")
    void aligned() {
        Result result = AudienceFitCalculator.compute(
                List.of(new Bucket(BookProfileDimension.GENDER, "FEMALE")),
                gender(60, 40), gender(30, 70), 100, DEFAULT);

        assertThat(result.sampleProtected()).isFalse();
        Fit fit = result.fits().get(0);
        assertThat(fit.verdict()).isEqualTo(Verdict.ALIGNED);
        assertThat(fit.ratio()).isEqualTo(0.6);
        assertThat(fit.siteRatio()).isEqualTo(0.3);
        assertThat(fit.lift()).isEqualTo(2.0);
        assertThat(fit.dimLabel()).isEqualTo("性别");
        assertThat(fit.tokenLabel()).isEqualTo("女");
    }

    @Test
    @DisplayName("声称女性但实际以男性为主 → 错配，并指出实际最多的桶")
    void mismatchNamesTheDominantBucket() {
        Result result = AudienceFitCalculator.compute(
                List.of(new Bucket(BookProfileDimension.GENDER, "FEMALE")),
                gender(20, 80), gender(50, 50), 100, DEFAULT);

        Fit fit = result.fits().get(0);
        assertThat(fit.verdict()).isEqualTo(Verdict.MISMATCH);
        assertThat(fit.lift()).isEqualTo(0.4);
        assertThat(fit.topTokenLabel()).isEqualTo("男");
        assertThat(fit.topRatio()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("单格不足 min-bucket-size → 人数/占比/lift/显著一起不发布（与并桶同一条隐私闸门）")
    void smallCellIsNotPublished() {
        Result result = AudienceFitCalculator.compute(
                List.of(new Bucket(BookProfileDimension.GENDER, "FEMALE")),
                gender(3, 97), gender(50, 50), 100, DEFAULT);

        Fit fit = result.fits().get(0);
        assertThat(fit.verdict()).isEqualTo(Verdict.NOT_ENOUGH_SAMPLE);
        // 单独留下"3 人"同样能反推小群人，所以 count 也必须为 null
        assertThat(fit.count()).isNull();
        assertThat(fit.ratio()).isNull();
        assertThat(fit.lift()).isNull();
        assertThat(fit.significant()).isFalse();

        // 阈值降到 2/1 后，同一份数据就该正常发布
        Fit loose = AudienceFitCalculator.compute(
                List.of(new Bucket(BookProfileDimension.GENDER, "FEMALE")),
                gender(3, 97), gender(50, 50), 100, new Thresholds(2, 1, 10, 20)).fits().get(0);
        assertThat(loose.count()).isEqualTo(3L);
        assertThat(loose.ratio()).isEqualTo(0.03);
        assertThat(loose.verdict()).isEqualTo(Verdict.MISMATCH);
    }

    @Test
    @DisplayName("整本书读者不足 min-cohort → 一份都不出（保护态）")
    void cohortGateComesFirst() {
        Result result = AudienceFitCalculator.compute(
                List.of(new Bucket(BookProfileDimension.GENDER, "FEMALE")),
                gender(6, 3), gender(50, 50), 9, DEFAULT);

        assertThat(result.sampleProtected()).isTrue();
        assertThat(result.fits()).isEmpty();
    }

    @Test
    @DisplayName("该维度没有有效样本 → NO_DATA，不猜测")
    void emptyDimensionIsNoData() {
        Result result = AudienceFitCalculator.compute(
                List.of(new Bucket(BookProfileDimension.GENDER, "FEMALE")),
                Map.of(), gender(50, 50), 100, DEFAULT);

        assertThat(result.fits().get(0).verdict()).isEqualTo(Verdict.NO_DATA);
    }

    /**
     * §4 规则 5 在新端点上的一处漏洞：主导桶原先无条件发布，「7 个人里 4 个是男」就是单格信息。
     * 现在主导桶也必须达到 min-bucket-size；不足时三项一起为 null，前端不渲染这一行。
     */
    @Test
    @DisplayName("主导桶不足 min-bucket-size 时不发布（三项一起为 null），达到时才发布")
    void smallTopBucketIsNotPublished() {
        java.util.Map<BookProfileDimension, DimCounts> book =
                Map.of(BookProfileDimension.GENDER, counts(7, Map.of("FEMALE", 3L, "MALE", 4L)));
        java.util.Map<BookProfileDimension, DimCounts> site =
                Map.of(BookProfileDimension.GENDER, counts(10, Map.of("FEMALE", 5L, "MALE", 5L)));
        Bucket claimed = new Bucket(BookProfileDimension.GENDER, "FEMALE");

        Fit strict = AudienceFitCalculator.compute(List.of(claimed), book, site, 100, DEFAULT).fits().get(0);
        assertThat(strict.topTokenLabel()).isNull();
        assertThat(strict.topCount()).isNull();
        assertThat(strict.topRatio()).isNull();

        Fit loose = AudienceFitCalculator.compute(List.of(claimed), book, site, 100,
                new Thresholds(1, 1, 10, 20)).fits().get(0);
        assertThat(loose.topTokenLabel()).isEqualTo("男");
        assertThat(loose.topCount()).isEqualTo(4);
        assertThat(loose.topRatio()).isEqualTo(0.5714);
    }

    @Test
    @DisplayName("同一个声称桶重复出现只算一次（书侧标签常重复）")
    void duplicateClaimsAreDeduped() {
        Result result = AudienceFitCalculator.compute(
                List.of(new Bucket(BookProfileDimension.GENDER, "FEMALE"),
                        new Bucket(BookProfileDimension.GENDER, "FEMALE")),
                gender(60, 40), gender(30, 70), 100, DEFAULT);

        assertThat(result.fits()).hasSize(1);
    }

    /**
     * 运行时抓到的边界：n=1、count=0、全站该桶恰好也为 0 时，Wilson 的下界会是 ~2e-4 而不是 0，
     * 于是「一个都没有」被判成显著偏高。零人、或全站没有基线，都不给显著标记。
     */
    @Test
    @DisplayName("全站该桶不足 min-bucket 时不给 siteRatio/lift（基线本身是小格）")
    void smallSiteCellHasNoBaseline() {
        java.util.Map<BookProfileDimension, DimCounts> site =
                Map.of(BookProfileDimension.GENDER, counts(22, Map.of("FEMALE", 2L, "MALE", 20L)));

        Fit fit = AudienceFitCalculator.compute(
                List.of(new Bucket(BookProfileDimension.GENDER, "FEMALE")),
                gender(10, 10), site, 100, DEFAULT).fits().get(0);

        assertThat(fit.count()).isEqualTo(10L);
        assertThat(fit.ratio()).isEqualTo(0.5);
        assertThat(fit.siteRatio()).isNull();
        assertThat(fit.lift()).isNull();
        assertThat(fit.verdict()).isEqualTo(Verdict.NO_DATA);
    }

    @Test
    @DisplayName("零人 / 全站该桶为 0 时不判显著（Wilson 下界的数值效应）")
    void zeroCountIsNeverSignificant() {
        Thresholds tiny = new Thresholds(1, 1, 10, 20);
        DimCounts bookOnlyMale = counts(1, Map.of("MALE", 1L));

        Fit zeroClaimed = AudienceFitCalculator.compute(
                List.of(new Bucket(BookProfileDimension.GENDER, "FEMALE")),
                Map.of(BookProfileDimension.GENDER, bookOnlyMale),
                Map.of(BookProfileDimension.GENDER, bookOnlyMale), 100, tiny).fits().get(0);

        assertThat(zeroClaimed.count()).isNull();
        assertThat(zeroClaimed.ratio()).isNull();
        // 全站该桶 0 人，本身也不是可发布的小格 → siteRatio 同样为 null
        assertThat(zeroClaimed.siteRatio()).isNull();
        assertThat(zeroClaimed.lift()).isNull();
        assertThat(zeroClaimed.significant()).isFalse();
        assertThat(zeroClaimed.verdict()).isEqualTo(Verdict.NOT_ENOUGH_SAMPLE);
    }
}
