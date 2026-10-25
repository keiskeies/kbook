package com.kbook.service.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cohort 阈值自适应的纯规则测试（取代 A.3 的人工生产校准）。
 *
 * 最重要的一条是「永不下破配置下界」：这条错了不会报错，只会让隐私闸门在数据变薄时悄悄放松，
 * 而放松的方向正是 §4 要防的反推。所以每个用例都同时断言"不低于下界"。
 */
class ThresholdCalibrationTest {

    private static Map<Integer, Long> counts(long t3, long t5, long t10, long t20) {
        Map<Integer, Long> map = new LinkedHashMap<>();
        map.put(3, t3);
        map.put(5, t5);
        map.put(10, t10);
        map.put(20, t20);
        return map;
    }

    @Test
    @DisplayName("没有读者的书 = 没有意见，沿用配置下界")
    void noDataKeepsFloor() {
        assertThat(ThresholdCalibration.chooseCohortSize(10, 0, counts(0, 0, 0, 0))).isEqualTo(10);
        assertThat(ThresholdCalibration.chooseCohortSize(10, -1, counts(99, 99, 99, 99))).isEqualTo(10);
        assertThat(ThresholdCalibration.chooseCohortSize(10, 100, null)).isEqualTo(10);
    }

    @Test
    @DisplayName("没有任何档位达到 20%：保持下界，不降档")
    void noTierReachesShare() {
        // dev 库就是这个形状：100 本书里只有 1 本达到 3 人
        assertThat(ThresholdCalibration.chooseCohortSize(10, 100, counts(1, 1, 0, 0))).isEqualTo(10);
    }

    @Test
    @DisplayName("达标就往上收到最高的那一档")
    void tightensToHighestQualifyingTier() {
        Map<Integer, Long> rich = counts(900, 700, 400, 250);
        assertThat(ThresholdCalibration.chooseCohortSize(10, 1000, rich)).isEqualTo(20);
        assertThat(ThresholdCalibration.chooseCohortSize(3, 1000, rich)).isEqualTo(20);
        // 只有 10 达标（400/1000），20 只有 100/1000 → 收到 10 而不是 20
        assertThat(ThresholdCalibration.chooseCohortSize(3, 1000, counts(900, 700, 400, 100))).isEqualTo(10);
    }

    @Test
    @DisplayName("20% 是「达到即算」：19/100 不达标，20/100 达标")
    void shareBoundaryIsInclusive() {
        assertThat(ThresholdCalibration.chooseCohortSize(10, 100, counts(100, 100, 100, 19))).isEqualTo(10);
        assertThat(ThresholdCalibration.chooseCohortSize(10, 100, counts(100, 100, 100, 20))).isEqualTo(20);
    }

    @Test
    @DisplayName("下界高于全部档位时，分布再好看也不会把阈值放宽")
    void neverBelowConfiguredFloor() {
        Map<Integer, Long> everything = counts(100, 100, 100, 100);
        assertThat(ThresholdCalibration.chooseCohortSize(20, 100, everything)).isEqualTo(20);
        assertThat(ThresholdCalibration.chooseCohortSize(50, 100, everything)).isEqualTo(50);
        assertThat(ThresholdCalibration.chooseCohortSize(1, 100, everything))
                .isGreaterThanOrEqualTo(1).isEqualTo(20);
    }

    @Test
    @DisplayName("档位缺键按 0 算，不当成「达标」")
    void missingTierKeyCountsAsZero() {
        Map<Integer, Long> partial = new LinkedHashMap<>();
        partial.put(10, 100L);
        assertThat(ThresholdCalibration.chooseCohortSize(10, 100, partial)).isEqualTo(10);
        assertThat(ThresholdCalibration.chooseCohortSize(3, 100, partial)).isEqualTo(10);
    }

    @Test
    @DisplayName("档位表升序、含下界默认值 10 与最严档 20，且判定线就是 A.3 的 20%")
    void tierLadderMatchesDocumentation() {
        assertThat(ThresholdCalibration.COHORT_TIERS).isSorted().containsExactly(3, 5, 10, 20);
        assertThat(ThresholdCalibration.MIN_PUBLISHABLE_SHARE).isEqualTo(0.20);
    }
}
