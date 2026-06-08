package com.kbook.service.recommend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.service.AbstractServiceImpl;
import com.kbook.config.annotation.LogModule;
import com.kbook.entity.RecommendCoefficient;
import com.kbook.entity.RecommendFeedbackEvent;
import com.kbook.repository.RecommendCoefficientRepository;
import com.kbook.repository.RecommendFeedbackEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 推荐系数服务 — 动态系数管理 + 基于反馈的自动调参
 * <p>
 * 架构：
 * 1. 内存缓存：启动时从数据库加载全部系数到 ConcurrentHashMap，推荐时直接读内存
 * 2. Redis 缓存：作为二级缓存，启动时和调参后更新
 * 3. 定时调参：每小时聚合反馈事件，梯度调整系数
 * 4. 管理员覆盖：管理员手动设置的系数带 locked=true 标记，自动调参不会修改
 * <p>
 * 调参策略：
 * - 统计最近24小时的反馈事件
 * - 按召回路径统计正反馈率，正反馈率高的路径增加权重，低的降低权重
 * - 每次调整幅度 = 学习率 × (正反馈率 - 平均正反馈率)
 * - 学习率 0.05（保守），系数有 min/max 钳位
 */
@Slf4j
@Service
@LogModule("推荐系数")
public class RecommendCoefficientService extends AbstractServiceImpl<RecommendCoefficient, Long> {

    @Autowired
    private RecommendCoefficientRepository coefficientRepository;
    @Autowired
    private RecommendFeedbackEventRepository feedbackEventRepository;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private static final String REDIS_PREFIX = "kbook:recommend:coeff:";
    private static final String REDIS_ALL_KEY = "kbook:recommend:coeff:all";

    /**
     * 学习率：每次调参的步长，0.05 表示保守调整
     */
    private static final double LEARNING_RATE = 0.05;

    /**
     * 内存缓存：启动时加载，调参时刷新
     */
    private final Map<String, Double> coefficientCache = new ConcurrentHashMap<>();

    // ==================== 默认系数定义 ====================
    // 格式：{category, key, defaultValue, minValue, maxValue, description}

    private static final Object[][] DEFAULT_COEFFICIENTS = {
            // --- 四路召回融合权重 ---
            {"FUSION", "weight_rule", 0.30, 0.10, 0.50, "规则召回权重"},
            {"FUSION", "weight_vector", 0.40, 0.20, 0.60, "向量召回权重"},
            {"FUSION", "weight_collab", 0.20, 0.05, 0.40, "协同召回权重"},
            {"FUSION", "weight_explore", 0.10, 0.02, 0.25, "探索召回权重"},

            // --- 质量因子分段参数 ---
            {"QUALITY", "very_low", 0.40, 0.10, 0.60, "rating=1.0 时的因子（强压制）"},
            {"QUALITY", "low", 0.70, 0.40, 0.85, "rating=2.0 时的因子（中等压制）"},
            {"QUALITY", "below_avg", 0.95, 0.75, 1.05, "rating=3.0 时的因子（近中性）"},
            {"QUALITY", "good", 1.15, 1.00, 1.35, "rating=4.0 时的因子（温和加成）"},
            {"QUALITY", "excellent", 1.30, 1.10, 1.50, "rating=5.0 时的因子（天花板加成）"},
            {"QUALITY", "unknown", 0.85, 0.60, 1.00, "无评分时的因子（略压制）"},

            // --- 新鲜度参数 ---
            {"FRESHNESS", "days_max", 7.0, 3.0, 14.0, "新鲜度最大加成天数"},
            {"FRESHNESS", "days_decay", 30.0, 14.0, 60.0, "新鲜度衰减天数"},
            {"FRESHNESS", "bonus_max", 1.12, 1.03, 1.25, "新鲜度最大加成系数"},
            {"FRESHNESS", "bonus_min", 1.03, 1.00, 1.10, "新鲜度衰减后最小加成"},

            // --- 画像匹配参数 ---
            {"MATCH", "rating_weight", 0.5, 0.2, 1.5, "图书评分匹配权重"},
            {"MATCH", "age_weight", 1.5, 1.0, 2.5, "年龄段匹配权重"},
            {"MATCH", "gender_weight", 0.8, 0.3, 1.5, "性别匹配权重"},
            {"MATCH", "married_weight", 0.8, 0.3, 1.5, "婚姻状态匹配权重"},
            {"MATCH", "children_weight", 0.8, 0.3, 1.5, "子女年龄匹配权重"},
            {"MATCH", "children_adjacent_decay", 0.40, 0.10, 0.70, "子女区间邻近衰减系数"},
            {"MATCH", "mbti_weight", 1.3, 0.8, 2.0, "MBTI 匹配权重"},
            {"MATCH", "occupation_weight", 1.0, 0.5, 1.5, "职业匹配权重"},
            {"MATCH", "education_weight", 0.8, 0.4, 1.2, "学历匹配权重"},
            {"MATCH", "entrepreneurship_weight", 0.6, 0.3, 1.0, "创业意向匹配权重"},
            {"MATCH", "income_weight", 0.5, 0.2, 0.8, "年收入匹配权重"},
            {"MATCH", "intent_weight", 1.2, 0.3, 1.5, "阅读意图匹配权重"},
            {"MATCH", "mood_weight", 1.2, 0.3, 1.5, "心情状态匹配权重"},
            {"MATCH", "adjacent_decay", 0.40, 0.10, 0.70, "通用邻近维度衰减系数"},
            {"MATCH", "opposite_penalty", 0.30, 0.10, 0.50, "反向维度惩罚系数"},

            // --- 覆盖度衰减参数 ---
            {"COVERAGE", "dim10", 1.00, 0.85, 1.00, "10维全填的置信度"},
            {"COVERAGE", "dim9", 0.98, 0.82, 1.00, "9维的置信度"},
            {"COVERAGE", "dim8", 0.96, 0.78, 1.00, "8维的置信度"},
            {"COVERAGE", "dim7", 0.93, 0.73, 0.98, "7维的置信度"},
            {"COVERAGE", "dim6", 0.89, 0.68, 0.95, "6维的置信度"},
            {"COVERAGE", "dim5", 0.84, 0.62, 0.93, "5维的置信度"},
            {"COVERAGE", "dim4", 0.78, 0.52, 0.90, "4维的置信度"},
            {"COVERAGE", "dim3", 0.70, 0.42, 0.85, "3维的置信度"},
            {"COVERAGE", "dim2", 0.58, 0.28, 0.78, "2维的置信度"},
            {"COVERAGE", "dim1", 0.42, 0.18, 0.60, "1维的置信度"},

            // --- 偏好加成参数 ---
            {"PREFERENCE", "tag_bonus", 0.12, 0.03, 0.25, "标签匹配加成"},
            {"PREFERENCE", "author_bonus", 0.15, 0.05, 0.30, "作者匹配加成"},
            {"PREFERENCE", "format_bonus", 0.05, 0.01, 0.15, "格式匹配加成"},

            // --- 其他参数 ---
            {"OTHER", "max_same_author", 2.0, 1.0, 4.0, "同作者最大推荐数"},
            {"OTHER", "mmr_lambda", 0.70, 0.40, 0.90, "MMR lambda（0=最大多样性，1=最大相关性）"},
            {"OTHER", "explore_random_count", 30.0, 10.0, 60.0, "探索召回随机采样数量"},
            {"OTHER", "rule_min_score", -0.50, -2.00, 1.00, "规则召回最低匹配分阈值(z-score)"},

            // --- 调参参数 ---
            {"TUNING", "learning_rate", 0.05, 0.01, 0.15, "自动调参学习率"},
            {"TUNING", "feedback_window_hours", 24.0, 6.0, 72.0, "反馈统计时间窗口（小时）"},
            {"TUNING", "min_feedback_count", 50.0, 10.0, 200.0, "最小反馈事件数（低于此数不调参）"},
            {"TUNING", "data_retention_days", 30.0, 7.0, 90.0, "反馈数据保留天数"},
    };

    // ==================== 初始化 ====================

    /**
     * 启动时初始化：从数据库加载系数到内存缓存
     * 如果数据库为空，先写入默认系数
     */
    public void initializeCoefficients() {
        List<RecommendCoefficient> existing = findList();
        Map<String, RecommendCoefficient> existingMap = new HashMap<>();
        for (RecommendCoefficient rc : existing) {
            String key = rc.getCategory() + ":" + rc.getCoeffKey();
            existingMap.put(key, rc);
        }

        // 确保 DEFAULT_COEFFICIENTS 中的每个系数都在数据库中存在
        boolean needsSave = false;
        for (Object[] def : DEFAULT_COEFFICIENTS) {
            String category = (String) def[0];
            String coeffKey = (String) def[1];
            double defaultValue = (Double) def[2];
            double minValue = (Double) def[3];
            double maxValue = (Double) def[4];
            String description = (String) def[5];

            String mapKey = category + ":" + coeffKey;
            if (!existingMap.containsKey(mapKey)) {
                // 数据库中不存在，插入默认值
                RecommendCoefficient rc = RecommendCoefficient.builder()
                        .category(category)
                        .coeffKey(coeffKey)
                        .coeffValue(defaultValue)
                        .defaultValue(defaultValue)
                        .minValue(minValue)
                        .maxValue(maxValue)
                        .description(description)
                        .locked(false)
                        .build();
                saveOne(rc);
                needsSave = true;
                log.info("初始化推荐系数: {}.{} = {}", category, coeffKey, defaultValue);
            }
        }

        if (needsSave) {
            log.info("推荐系数初始化完成，新增了默认系数");
        }

        // 加载到内存
        reloadCache();
        log.info("推荐系数缓存加载完成，共 {} 个系数", coefficientCache.size());
    }

    /**
     * 从数据库重新加载系数到内存缓存
     */
    public void reloadCache() {
        coefficientCache.clear();
        List<RecommendCoefficient> all = findList();
        for (RecommendCoefficient rc : all) {
            String key = rc.getCategory() + ":" + rc.getCoeffKey();
            coefficientCache.put(key, rc.getCoeffValue());
        }
        // 更新 Redis 二级缓存（使用 StringRedisTemplate + 手动 JSON 序列化，避免依赖 Jackson default typing）
        try {
            String json = objectMapper.writeValueAsString(new HashMap<>(coefficientCache));
            redisTemplate.opsForValue().set(REDIS_ALL_KEY, json, 2, TimeUnit.HOURS);
        } catch (Exception e) {
            log.debug("更新系数Redis缓存失败: {}", e.getMessage());
        }
    }

    // ==================== 读取系数 ====================

    /**
     * 获取系数值，带默认回退
     */
    public double getCoefficient(String category, String key, double fallback) {
        String cacheKey = category + ":" + key;

        // 1. 内存缓存
        Double value = coefficientCache.get(cacheKey);
        if (value != null) return value;

        // 2. Redis
        try {
            String json = redisTemplate.opsForValue().get(REDIS_ALL_KEY);
            if (json != null) {
                Map<String, Double> redisMap = objectMapper.readValue(json,
                        new TypeReference<Map<String, Double>>() {});
                Double v = redisMap.get(cacheKey);
                if (v != null) {
                    coefficientCache.put(cacheKey, v);
                    return v;
                }
            }
        } catch (Exception e) {
            log.debug("读取系数Redis缓存失败: {}", e.getMessage());
        }

        // 3. 数据库
        try {
            Optional<RecommendCoefficient> opt = coefficientRepository.findByCategoryAndCoeffKey(category, key);
            if (opt.isPresent()) {
                double v = opt.get().getCoeffValue();
                coefficientCache.put(cacheKey, v);
                return v;
            }
        } catch (Exception e) {
            log.debug("读取系数数据库失败: {}", e.getMessage());
        }

        // 4. 从默认定义中查找
        for (Object[] def : DEFAULT_COEFFICIENTS) {
            if (def[0].equals(category) && def[1].equals(key)) {
                double defaultVal = (Double) def[2];
                coefficientCache.put(cacheKey, defaultVal);
                return defaultVal;
            }
        }

        return fallback;
    }



    // ==================== 反馈记录 ====================

    /**
     * 记录推荐反馈事件
     */
    @Transactional
    public void recordFeedback(Long userId, Long bookId, String feedbackType,
                               Double strength, String recallPaths,
                               Double recommendScore, Double qualityFactor,
                               String feedbackDetail) {
        try {
            RecommendFeedbackEvent event = RecommendFeedbackEvent.builder()
                    .userId(userId)
                    .bookId(bookId)
                    .feedbackType(feedbackType)
                    .strength(strength != null ? strength : 0.0)
                    .recallPaths(recallPaths)
                    .recommendScore(recommendScore)
                    .qualityFactor(qualityFactor)
                    .feedbackDetail(feedbackDetail)
                    .build();
            feedbackEventRepository.save(event);
        } catch (Exception e) {
            log.warn("记录推荐反馈失败: userId={}, bookId={}, type={} - {}",
                    userId, bookId, feedbackType, e.getMessage());
        }
    }

    // ==================== 自动调参（定时任务） ====================

    /**
     * 自动调参：每小时执行一次
     * 基于反馈数据梯度调整四路召回权重和质量因子
     */
    @Scheduled(fixedRate = 3600_000) // 每小时
    public void autoTuneCoefficients() {
        try {
            double windowHours = getCoefficient("TUNING", "feedback_window_hours", 24.0);
            double minFeedback = getCoefficient("TUNING", "min_feedback_count", 50.0);
            double learningRate = getCoefficient("TUNING", "learning_rate", 0.05);

            LocalDateTime to = LocalDateTime.now();
            LocalDateTime from = to.minusHours((long) windowHours);

            // 1. 检查反馈量是否足够
            long feedbackCount = feedbackEventRepository.countByCreatedAtBetween(from, to);
            if (feedbackCount < (long) minFeedback) {
                log.debug("反馈事件不足，跳过调参: count={}, min={}", feedbackCount, (long) minFeedback);
                return;
            }

            log.info("开始自动调参: feedbackCount={}, window={}h, learningRate={}",
                    feedbackCount, (long) windowHours, learningRate);

            // 2. 按召回路径统计正反馈率
            List<Object[]> pathStats = feedbackEventRepository.countByRecallPathInRange(from, to);
            Map<String, Double> pathPositiveRates = new HashMap<>();
            double totalPositiveRate = 0;
            int pathCount = 0;

            for (Object[] row : pathStats) {
                String paths = (String) row[0];
                long count = ((Number) row[1]).longValue();
                long positiveCount = ((Number) row[2]).longValue();
                double rate = count > 0 ? (double) positiveCount / count : 0.5;
                pathPositiveRates.put(paths, rate);
                totalPositiveRate += rate;
                pathCount++;
            }

            double avgPositiveRate = pathCount > 0 ? totalPositiveRate / pathCount : 0.5;

            // 3. 按反馈类型统计，调整质量因子
            List<Object[]> typeStats = feedbackEventRepository.countByFeedbackTypeInRange(from, to);
            double avgRating = 3.0; // 默认
            long rateCount = 0;
            double rateSum = 0;

            for (Object[] row : typeStats) {
                String type = (String) row[0];
                long count = ((Number) row[1]).longValue();
                double avgStrength = ((Number) row[2]).doubleValue();
                if ("RATE".equals(type) && count > 0) {
                    rateSum += avgStrength;
                    rateCount = count;
                }
            }
            if (rateCount > 0) {
                // 从反馈强度反推平均评分
                avgRating = rateSum > 0 ? rateSum / 0.1 : 3.0;
                avgRating = Math.max(1.0, Math.min(5.0, avgRating));
            }

            // 4. 调整四路召回权重
            tuneRecallWeights(pathPositiveRates, avgPositiveRate, learningRate);

            // 5. 调整质量因子（如果平均评分偏离3.0，微调分段参数）
            tuneQualityFactors(avgRating, learningRate);

            // 6. 刷新缓存
            reloadCache();

            // 7. 清理过期反馈数据
            double retentionDays = getCoefficient("TUNING", "data_retention_days", 30.0);
            LocalDateTime cutoff = LocalDateTime.now().minusDays((long) retentionDays);
            long deleted = feedbackEventRepository.deleteByCreatedAtBefore(cutoff);
            if (deleted > 0) {
                log.info("清理过期反馈数据: deleted={}, retention={}days", deleted, (long) retentionDays);
            }

            log.info("自动调参完成: pathPositiveRates={}, avgRating={}", pathPositiveRates, avgRating);
        } catch (Exception e) {
            log.error("自动调参失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 调整四路召回权重
     * 原理：正反馈率高于平均的路径增加权重，低于平均的降低权重
     */
    private void tuneRecallWeights(Map<String, Double> pathPositiveRates,
                                   double avgPositiveRate, double learningRate) {
        // 映射：recallPaths 中的关键词 → 系数 key
        Map<String, String> pathToCoeffKey = Map.of(
                "RULE", "weight_rule",
                "VECTOR", "weight_vector",
                "COLLAB", "weight_collab",
                "EXPLORE", "weight_explore"
        );

        for (Map.Entry<String, String> entry : pathToCoeffKey.entrySet()) {
            String pathName = entry.getKey();
            String coeffKey = entry.getValue();

            // 找出包含此路径的所有反馈
            double pathRate = 0;
            boolean found = false;
            for (Map.Entry<String, Double> pr : pathPositiveRates.entrySet()) {
                if (pr.getKey() != null && pr.getKey().contains(pathName)) {
                    pathRate = pr.getValue();
                    found = true;
                    break;
                }
            }

            if (!found) continue;

            // 梯度调整：调整量 = 学习率 × (路径正反馈率 - 平均正反馈率)
            double delta = learningRate * (pathRate - avgPositiveRate);

            adjustCoefficient("FUSION", coeffKey, delta);
        }

        // 归一化：确保四路权重之和为 1.0
        normalizeFusionWeights();
    }

    /**
     * 归一化四路召回权重，使其总和为 1.0
     */
    /**
     * 归一化四路召回权重，使其总和为 1.0
     * 权重调整后必须归一化，否则会导致概率分布异常
     */
    private void normalizeFusionWeights() {
        String[] keys = {"weight_rule", "weight_vector", "weight_collab", "weight_explore"};
        double sum = 0;
        for (String key : keys) {
            sum += getCoefficient("FUSION", key, 0);
        }
        if (sum <= 0) return;

        for (String key : keys) {
            double current = getCoefficient("FUSION", key, 0);
            double normalized = current / sum;
            coefficientCache.put("FUSION:" + key, normalized);

            // 同步到数据库
            coefficientRepository.findByCategoryAndCoeffKey("FUSION", key).ifPresent(rc -> {
                if (!rc.getLocked()) {
                    rc.setCoeffValue(Math.max(rc.getMinValue(), Math.min(rc.getMaxValue(), normalized)));
                    updateOne(rc);
                }
            });
        }
    }

    /**
     * 调整质量因子
     * 如果用户平均评分偏高（>3.5），说明低质量因子压制效果好，可以保持或增强
     * 如果用户平均评分偏低（<2.5），说明推荐了太多低质量书，需要增强压制
     */
    /**
     * 调整质量因子
     * 根据用户平均评分动态调整低分书籍的压制力度：
     * - 平均评分低（<2.5）：增强低分压制，减少低质量书籍推荐
     * - 平均评分高（>3.5）：适当放松压制，允许更多书籍被推荐
     *
     * @param avgRating    用户平均评分
     * @param learningRate 学习率
     */
    private void tuneQualityFactors(double avgRating, double learningRate) {
        if (avgRating < 2.5) {
            // 平均评分低 → 增强低分压制（降低 very_low 和 low）
            adjustCoefficient("QUALITY", "very_low", -learningRate * 0.5);
            adjustCoefficient("QUALITY", "low", -learningRate * 0.3);
            adjustCoefficient("QUALITY", "unknown", -learningRate * 0.2);
        } else if (avgRating > 3.5) {
            // 平均评分高 → 可以适当放松压制（提高 very_low 和 low）
            adjustCoefficient("QUALITY", "very_low", learningRate * 0.3);
            adjustCoefficient("QUALITY", "low", learningRate * 0.2);
        }
    }

    /**
     * 微调单个系数（考虑 locked 和 min/max 钳位）
     */
    /**
     * 微调单个系数（考虑锁定状态和范围钳位）
     * 自动调参时使用，管理员手动设置的系数（locked=true）不会被修改
     *
     * @param category 系数类别（如 FUSION、QUALITY）
     * @param key      系数键名
     * @param delta    调整增量（正数增加，负数减少）
     */
    private void adjustCoefficient(String category, String key, double delta) {
        coefficientRepository.findByCategoryAndCoeffKey(category, key).ifPresent(rc -> {
            if (rc.getLocked()) {
                log.debug("系数已锁定，跳过调参: {}.{}", category, key);
                return;
            }
            double newValue = rc.getCoeffValue() + delta;
            newValue = Math.max(rc.getMinValue(), Math.min(rc.getMaxValue(), newValue));
            rc.setCoeffValue(newValue);
            updateOne(rc);
            log.info("自动调参: {}.{} {} → {} (delta={})",
                    category, key, rc.getCoeffValue() - delta, newValue, delta);
        });
    }
}
