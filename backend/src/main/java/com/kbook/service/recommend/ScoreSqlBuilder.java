package com.kbook.service.recommend;

import com.kbook.entity.User;
import com.kbook.service.tools.DimensionStatsService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import static com.kbook.service.recommend.RecommendMatchCalculator.MatchWeight;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;

/**
 * 构建图书匹配度评分的 SQL 表达式
 * <p>
 * 将 {@link RecommendMatchCalculator#collectContributions} 中的 Java 评分逻辑
 * 翻译为 MySQL 标量表达式，直接在 book_dimension_scores 表上计算加权偏差和 + 覆盖率因子 + sigmoid。
 * 结果与 Java 评分完全一致。
 */
@Component
public class ScoreSqlBuilder {

    /** 列名前缀 */
    private static final String DS = "ds";

    /**
     * 构建结果：SQL 表达式 + 元数据
     */
    public record BuildResult(
            String weightedSumSql,    // 各维度加权偏差和的 SQL 表达式
            double totalWeight,       // 所有活跃维度的权重之和
            int activeDimensionCount, // 活跃维度数（用于 coverageFactor）
            String intentKey          // 用户阅读意图（影响权重调制）
    ) {}

    /**
     * 根据用户画像动态构建 SQL 权重表达式
     *
     * @param user               用户（含画像信息）
     * @param dimensionStatsService  维度统计服务（用于 Z-Score）
     * @param coefficientService 权重系数服务
     * @return SQL 构建结果
     */
    public BuildResult build(User user, DimensionStatsService dimensionStatsService,
                             RecommendCoefficientService coefficientService) {
        StringBuilder sb = new StringBuilder();
        List<String> activeDimensions = new ArrayList<>();
        double totalWeight = 0;

        double adjacentDecay = coefficientService.getCoefficient("MATCH", "adjacent_decay", 0.4);

        String intentKey = extractIntent(user);

        // ==================== 图书评分 ====================
        double ratingWeight = coefficientService.getCoefficient("MATCH", "rating_weight", 0.8);
        double ratingMod = getIntentModulation(intentKey, MatchWeight.RATING);
        double ratingWeighted = ratingWeight * ratingMod;
        sb.append(String.format("(COALESCE(b.rating/5.0,0)-0.5)*%.6f + ", ratingWeighted));
        totalWeight += ratingWeighted;
        activeDimensions.add("rating");

        // ==================== 年龄 ====================
        if (user.getBirthday() != null) {
            double ageWeight = coefficientService.getCoefficient("MATCH", "age_weight", 1.5);
            double ageMod = getIntentModulation(intentKey, MatchWeight.AGE);
            double ageW = ageWeight * ageMod;

            int age = Period.between(user.getBirthday(), LocalDate.now()).getYears();
            String ageGroup = getAgeGroup(age);
            appendDeviationTerm(sb, dimensionStatsService, ageGroup, ageW);
            activeDimensions.add("age");
            totalWeight += ageW;

            // 相邻年龄组
            for (int dir : new int[]{-1, 1}) {
                String adj = getAdjacentAgeGroup(age, dir);
                if (adj != null && !adj.equals(ageGroup)) {
                    appendDeviationTerm(sb, dimensionStatsService, adj, ageW * adjacentDecay);
                    totalWeight += ageW * adjacentDecay;
                }
            }
        }

        // ==================== 性别 ====================
        if (user.getGender() != null) {
            double genderWeight = coefficientService.getCoefficient("MATCH", "gender_weight", 0.8);
            double genderMod = getIntentModulation(intentKey, MatchWeight.GENDER);
            double genderW = genderWeight * genderMod;
            double penalty = coefficientService.getCoefficient("MATCH", "opposite_penalty", 0.3);

            String genderKey = "MALE".equals(user.getGender()) ? "male" : "female";
            String oppositeKey = "MALE".equals(user.getGender()) ? "female" : "male";

            // 主性别 + 反向惩罚
            appendDeviationTermWithOpposite(sb, dimensionStatsService, genderKey, oppositeKey, genderW, penalty);
            activeDimensions.add("gender");
            totalWeight += genderW;
        }

        // ==================== 婚姻 ====================
        if (user.getMarried() != null) {
            double marriedWeight = coefficientService.getCoefficient("MATCH", "married_weight", 0.8);
            double marriedMod = getIntentModulation(intentKey, MatchWeight.MARRIED);
            double marriedW = marriedWeight * marriedMod;
            double penalty = coefficientService.getCoefficient("MATCH", "opposite_penalty", 0.3);

            String marriedKey = user.getMarried() ? "married" : "unmarried";
            String oppositeKey = user.getMarried() ? "unmarried" : "married";

            appendDeviationTermWithOpposite(sb, dimensionStatsService, marriedKey, oppositeKey, marriedW, penalty);
            activeDimensions.add("married");
            totalWeight += marriedW;
        }

        // ==================== 子女 ====================
        if (user.getChildrenAgeRanges() != null && !user.getChildrenAgeRanges().isBlank()) {
            double childrenWeight = coefficientService.getCoefficient("MATCH", "children_weight", 0.8);
            double childrenMod = getIntentModulation(intentKey, MatchWeight.CHILDREN);
            double childrenW = childrenWeight * childrenMod;
            double childDecay = coefficientService.getCoefficient("MATCH", "children_adjacent_decay", 0.4);

            String[] ranges = user.getChildrenAgeRanges().split(",");
            for (String range : ranges) {
                String childKey = range.trim().toLowerCase();
                if (childKey.isEmpty()) continue;
                appendDeviationTerm(sb, dimensionStatsService, childKey, childrenW);
                activeDimensions.add("children");
                totalWeight += childrenW;

                for (String adj : getAdjacentChildRanges(childKey)) {
                    appendDeviationTerm(sb, dimensionStatsService, adj, childrenW * childDecay);
                    totalWeight += childrenW * childDecay;
                }
            }
        } else if (user.getHasChildren() != null) {
            double childrenWeight = coefficientService.getCoefficient("MATCH", "children_weight", 0.8);
            double childrenMod = getIntentModulation(intentKey, MatchWeight.CHILDREN);
            double childrenW = childrenWeight * childrenMod;
            double penalty = coefficientService.getCoefficient("MATCH", "opposite_penalty", 0.3);

            String childKey = user.getHasChildren() ? "hasChildren" : "no_children";
            String oppositeKey = user.getHasChildren() ? "no_children" : "hasChildren";

            appendDeviationTermWithOpposite(sb, dimensionStatsService, childKey, oppositeKey, childrenW, penalty);
            activeDimensions.add("children");
            totalWeight += childrenW;
        }

        // ==================== MBTI ====================
        if (user.getMbti() != null) {
            double mbtiWeight = coefficientService.getCoefficient("MATCH", "mbti_weight", 1.5);
            // MBTI 无 intent modulation（Java 中也没调）
            String mbtiKey = user.getMbti().toUpperCase();
            appendDeviationTerm(sb, dimensionStatsService, mbtiKey, mbtiWeight);
            activeDimensions.add("mbti");
            totalWeight += mbtiWeight;
        }

        // ==================== 职业 ====================
        if (user.getOccupation() != null && !user.getOccupation().isBlank()) {
            double occWeight = coefficientService.getCoefficient("MATCH", "occupation_weight", 1.2);
            double occMod = getIntentModulation(intentKey, MatchWeight.OCCUPATION);
            double occW = occWeight * occMod;

            for (String occ : user.getOccupation().split(",")) {
                String occKey = occ.trim().toLowerCase();
                if (occKey.isEmpty()) continue;
                appendDeviationTerm(sb, dimensionStatsService, occKey, occW);
                activeDimensions.add("occupation");
                totalWeight += occW;

                for (String adj : getAdjacentOccupations(occKey)) {
                    appendDeviationTerm(sb, dimensionStatsService, adj, occW * adjacentDecay);
                    totalWeight += occW * adjacentDecay;
                }
            }
        }

        // ==================== 学历 ====================
        if (user.getAspirationEducation() != null) {
            double eduWeight = coefficientService.getCoefficient("MATCH", "education_weight", 0.8);
            double eduMod = getIntentModulation(intentKey, MatchWeight.EDUCATION);
            double eduW = eduWeight * eduMod;

            String eduKey = user.getAspirationEducation().toLowerCase();
            appendDeviationTerm(sb, dimensionStatsService, eduKey, eduW);
            activeDimensions.add("education");
            totalWeight += eduW;

            for (String adj : getAdjacentEducations(eduKey)) {
                appendDeviationTerm(sb, dimensionStatsService, adj, eduW * adjacentDecay);
                totalWeight += eduW * adjacentDecay;
            }
        }

        // ==================== 创业意向 ====================
        if (user.getEntrepreneurship() != null && !user.getEntrepreneurship().isBlank()) {
            double entWeight = coefficientService.getCoefficient("MATCH", "entrepreneurship_weight", 0.6);
            double entMod = getIntentModulation(intentKey, MatchWeight.ENTREPRENEURSHIP);
            double entW = entWeight * entMod;

            String entKey = user.getEntrepreneurship().toLowerCase();
            appendDeviationTerm(sb, dimensionStatsService, entKey, entW);
            activeDimensions.add("entrepreneurship");
            totalWeight += entW;
        }

        // ==================== 收入 ====================
        if (user.getAspirationIncome() != null && !user.getAspirationIncome().isBlank()
                && !"PREFER_NOT_TO_SAY".equalsIgnoreCase(user.getAspirationIncome())) {
            double incomeWeight = coefficientService.getCoefficient("MATCH", "income_weight", 0.5);
            double incomeMod = getIntentModulation(intentKey, MatchWeight.INCOME);
            double incomeW = incomeWeight * incomeMod;

            String incomeKey = user.getAspirationIncome().toLowerCase();
            appendDeviationTerm(sb, dimensionStatsService, incomeKey, incomeW);
            activeDimensions.add("income");
            totalWeight += incomeW;

            for (String adj : getAdjacentIncomes(incomeKey)) {
                appendDeviationTerm(sb, dimensionStatsService, adj, incomeW * adjacentDecay);
                totalWeight += incomeW * adjacentDecay;
            }
        }

        // ==================== 意图 + 心情 ====================
        if (user.getMood() != null && !user.getMood().isBlank()) {
            String moodRaw = user.getMood();
            String moodKey;
            int pipeIdx = moodRaw.indexOf('|');
            if (pipeIdx > 0) {
                moodKey = moodRaw.substring(pipeIdx + 1).toLowerCase().trim();
            } else {
                moodKey = moodRaw.toLowerCase().trim();
            }

            // 意图
            if (intentKey != null && !intentKey.isEmpty()) {
                double intentWeight = coefficientService.getCoefficient("MATCH", "intent_weight", 1.8);
                double intentMod = getIntentModulation(intentKey, MatchWeight.INTENT);
                double intentW = intentWeight * intentMod;

                appendDeviationTerm(sb, dimensionStatsService, intentKey, intentW);
                activeDimensions.add("intent");
                totalWeight += intentW;

                for (String adj : getRelatedIntents(intentKey)) {
                    appendDeviationTerm(sb, dimensionStatsService, adj, intentW * adjacentDecay);
                    totalWeight += intentW * adjacentDecay;
                }
            }

            // 心情
            if (!moodKey.isEmpty()) {
                double moodWeight = coefficientService.getCoefficient("MATCH", "mood_weight", 1.6);
                double moodMod = getIntentModulation(intentKey, MatchWeight.MOOD);
                double moodW = moodWeight * moodMod;

                appendDeviationTerm(sb, dimensionStatsService, moodKey, moodW);
                activeDimensions.add("mood");
                totalWeight += moodW;

                for (String adj : getRelatedMoods(moodKey)) {
                    appendDeviationTerm(sb, dimensionStatsService, adj, moodW * adjacentDecay);
                    totalWeight += moodW * adjacentDecay;
                }
            }
        }

        // 去掉最后的 " + "
        String expr = sb.toString();
        if (expr.endsWith(" + ")) {
            expr = expr.substring(0, expr.length() - 3);
        }
        if (expr.isEmpty()) {
            expr = "0";
        }

        int dimCount = (int) activeDimensions.stream().distinct().count();
        return new BuildResult(expr, totalWeight, dimCount, intentKey);
    }

    // ==================== SQL 项构建 ====================

    /**
     * 追加单个维度的偏差项：(COALESCE(ds.col, 0) - mean) / stddev * weight
     * 使用 Z-Score（当 stats 有数据时）或 rawScore - 0.5（兜底）
     */
    private void appendDeviationTerm(StringBuilder sb, DimensionStatsService stats,
                                     String dimKey, double weight) {
        String col = BookDimensionScoreService.DIMENSION_COLUMNS.get(dimKey);
        if (col == null) return;

        double mean = stats.getMean(dimKey);
        double stddev = stats.getStddev(dimKey);

        // (COALESCE(col, 0.3) - mean) / stddev * weight
        // 默认 0.3 而非 0：AI 可能漏掉某些维度 key，缺失 ≠ 0 分，给一个偏低但不致命的默认值
        sb.append(String.format("(COALESCE(%s.%s,0.3)-%.4f)/%.4f*%.6f + ",
                DS, col, mean, stddev, weight));
    }

    /**
     * 追加带反向惩罚的偏差项：
     * (COALESCE(mainCol, 0.3) - mean) / stddev * weight
     * - GREATEST((COALESCE(oppCol, 0.3) - oppMean) / oppStddev, 0) * penalty
     */
    private void appendDeviationTermWithOpposite(StringBuilder sb, DimensionStatsService stats,
                                                  String mainKey, String oppKey,
                                                  double weight, double penalty) {
        String mainCol = BookDimensionScoreService.DIMENSION_COLUMNS.get(mainKey);
        String oppCol = BookDimensionScoreService.DIMENSION_COLUMNS.get(oppKey);
        if (mainCol == null) return;

        double mainMean = stats.getMean(mainKey);
        double mainStddev = stats.getStddev(mainKey);

        if (oppCol == null) {
            sb.append(String.format("(COALESCE(%s.%s,0.3)-%.4f)/%.4f*%.6f + ",
                    DS, mainCol, mainMean, mainStddev, weight));
            return;
        }

        double oppMean = stats.getMean(oppKey);
        double oppStddev = stats.getStddev(oppKey);

        // main - MAX(opposite, 0) * penalty
        sb.append(String.format("((COALESCE(%s.%s,0.3)-%.4f)/%.4f - GREATEST((COALESCE(%s.%s,0.3)-%.4f)/%.4f,0)*%.4f)*%.6f + ",
                DS, mainCol, mainMean, mainStddev,
                DS, oppCol, oppMean, oppStddev, penalty,
                weight));
    }

    // ==================== 辅助方法（从 RecommendMatchCalculator 引用）====================

    private static String extractIntent(User user) {
        if (user == null || user.getMood() == null || user.getMood().isBlank()) return null;
        int pipeIdx = user.getMood().indexOf('|');
        if (pipeIdx > 0) {
            return user.getMood().substring(0, pipeIdx).toLowerCase().trim();
        }
        return null;
    }

    static double getIntentModulation(String intentKey, MatchWeight dimension) {
        return RecommendMatchCalculator.getIntentModulation(intentKey, dimension);
    }

    static String getAgeGroup(int age) {
        return RecommendMatchCalculator.getAgeGroup(age);
    }

    static String getAdjacentAgeGroup(int age, int direction) {
        return RecommendMatchCalculator.getAdjacentAgeGroup(age, direction);
    }

    static List<String> getAdjacentOccupations(String occ) {
        return RecommendMatchCalculator.getAdjacentOccupations(occ);
    }

    static List<String> getAdjacentEducations(String edu) {
        return RecommendMatchCalculator.getAdjacentEducations(edu);
    }

    static List<String> getAdjacentIncomes(String income) {
        return RecommendMatchCalculator.getAdjacentIncomes(income);
    }

    static List<String> getAdjacentChildRanges(String childKey) {
        return RecommendMatchCalculator.getAdjacentChildRanges(childKey);
    }

    static List<String> getRelatedIntents(String intent) {
        return RecommendMatchCalculator.getRelatedIntents(intent);
    }

    static List<String> getRelatedMoods(String mood) {
        return RecommendMatchCalculator.getRelatedMoods(mood);
    }
}
