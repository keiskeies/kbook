package com.kbook.service.admin;

import com.kbook.service.admin.BookProfileService.Basis;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 画像 cohort 阈值的运行时自适应：每天按最新读者分布重算一次生效阈值。
 *
 * 它取代的是附录 A.3 的「上线前人工在生产库跑一次分布再定阈值」——那件事本来就会随数据量
 * 过期，做成定时重算就不需要有人记着去做。生效值只可能 ≥ 配置的 {@code min-cohort-size}，
 * 因为 {@link ThresholdCalibration#chooseCohortSize} 只往上挑档位；配置值本身是硬下界。
 *
 * 重算失败（查询超时、库不可用）保留上一次生效值；启动时生效值就是配置下界，
 * 所以这个作业没跑过也不会让页面失去闸门。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileThresholdCalibrationService {

    private final EntityManager em;

    @Value("${kbook.admin.book-profile.min-cohort-size:10}")
    private int configuredMinCohortSize;

    /** 关掉就完全退回配置值（生产上一键回到「人工钉阈值」的行为） */
    @Value("${kbook.admin.book-profile.threshold-auto:true}")
    private boolean auto;

    private final Map<Basis, Integer> effective = new EnumMap<>(Basis.class);

    @PostConstruct
    void init() {
        for (Basis basis : Basis.values()) {
            effective.put(basis, configuredMinCohortSize);
        }
        log.info("画像阈值自适应已启用={}，配置下界 min-cohort-size={}，生效初值={}", auto, configuredMinCohortSize, effective);
    }

    /** 单书画像 / 反查榜 / 吻合度三条路径共用这一个生效值 */
    int minCohortSize(Basis basis) {
        if (!auto) {
            return configuredMinCohortSize;
        }
        return effective.getOrDefault(basis, configuredMinCohortSize);
    }

    /** 生效值快照，给验证脚本和管理端排查用 */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("auto", auto);
        out.put("configuredMinCohortSize", configuredMinCohortSize);
        for (Basis basis : Basis.values()) {
            out.put(basis.name(), effective.getOrDefault(basis, configuredMinCohortSize));
        }
        out.put("tiers", ThresholdCalibration.COHORT_TIERS);
        out.put("minPublishableShare", ThresholdCalibration.MIN_PUBLISHABLE_SHARE);
        return out;
    }

    @Scheduled(cron = "${kbook.admin.book-profile.threshold-calibration-cron:0 40 3 * * *}")
    public void scheduledCalibration() {
        if (!auto) {
            log.debug("画像阈值自适应已关闭，沿用配置的 min-cohort-size={}", configuredMinCohortSize);
            return;
        }
        for (Basis basis : Basis.values()) {
            try {
                calibrate(basis);
            } catch (RuntimeException e) {
                // 保留上一次生效值：这一步失败不该让闸门消失，也不该把异常抛进调度线程
                log.warn("画像阈值自适应失败，沿用现值 basis={} effective={}", basis, minCohortSize(basis), e);
            }
        }
    }

    /** 手工触发一次（管理端接口用），与定时任务走同一条计算路径 */
    public Map<String, Integer> recalibrate() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Basis basis : Basis.values()) {
            calibrate(basis);
            out.put(basis.name(), minCohortSize(basis));
        }
        return out;
    }

    private void calibrate(Basis basis) {
        String sql = ProfileReaderSql.cohortDistribution(basis, ThresholdCalibration.COHORT_TIERS);
        Object raw = em.createNativeQuery(sql).getSingleResult();
        Object[] row = (Object[]) raw;
        long books = number(row[0]);
        Map<Integer, Long> atLeast = new LinkedHashMap<>();
        StringBuilder counts = new StringBuilder();
        for (int i = 0; i < ThresholdCalibration.COHORT_TIERS.size(); i++) {
            Integer tier = ThresholdCalibration.COHORT_TIERS.get(i);
            long reached = number(row[i + 1]);
            atLeast.put(tier, reached);
            counts.append(tier).append('=').append(reached).append(' ');
        }
        int chosen = ThresholdCalibration.chooseCohortSize(configuredMinCohortSize, books, atLeast);
        effective.put(basis, chosen);
        log.info("画像阈值自适应 basis={} 有读者的书={} 达标书[{}] 配置下界={} 生效 min-cohort-size={}",
                basis, books, counts.toString().trim(), configuredMinCohortSize, chosen);
    }

    /** 空表时 SUM(...) 返回 NULL，COUNT(*) 返回 0：两者都要能读成数字 */
    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
