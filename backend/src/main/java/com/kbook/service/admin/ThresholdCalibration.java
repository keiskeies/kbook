package com.kbook.service.admin;

import java.util.List;
import java.util.Map;

/**
 * 阈值自适应的纯规则。
 *
 * 背景：附录 A.3 原本要求「上线前人工在生产库跑一次读者分布，再定 min-cohort-size」。
 * 那个做法有两个问题——它只在上线那一刻做一次，而数据量会长大；且它拿当天的稀疏样本
 * 去钉一个永久阈值。这里把同一条 20% 规则挪进运行时，每天按最新分布重算。
 *
 * 只允许**往上收紧**，绝不下破配置下界。理由：降档等于在数据变薄时放松隐私闸门，
 * 而读者越少越容易从分布反推到人，与 §4「宁可不出」的立场相反。
 * 因此循环里只看不小于当前下界的档位——「低于下界」在这个实现里构造不出来。
 */
final class ThresholdCalibration {

    /** 候选档位，与 deploy/sql/profile-threshold-audit.sql 的阈值扫描用同一组数 */
    static final List<Integer> COHORT_TIERS = List.of(3, 5, 10, 20);

    /** A.3 的判定线：某个档位仍有 ≥20% 的书可出分布，才允许把阈值收到那一档 */
    static final double MIN_PUBLISHABLE_SHARE = 0.20;

    private ThresholdCalibration() {
    }

    /**
     * @param configuredFloor   配置里的 min-cohort-size，即运行时的硬下界
     * @param booksWithReaders  该口径下「有读者」的书总数（分布的分母）
     * @param booksAtLeastTier  各档位上达标书的计数，缺键按 0 算
     */
    static int chooseCohortSize(int configuredFloor, long booksWithReaders, Map<Integer, Long> booksAtLeastTier) {
        if (booksWithReaders <= 0 || booksAtLeastTier == null) {
            return configuredFloor;
        }
        int chosen = configuredFloor;
        for (Integer tier : COHORT_TIERS) {
            if (tier < chosen) {
                continue;
            }
            long reached = booksAtLeastTier.getOrDefault(tier, 0L);
            if (reached > 0 && (double) reached / booksWithReaders >= MIN_PUBLISHABLE_SHARE) {
                chosen = Math.max(chosen, tier);
            }
        }
        return chosen;
    }
}
