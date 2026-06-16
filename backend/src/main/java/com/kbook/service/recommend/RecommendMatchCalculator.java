package com.kbook.service.recommend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.config.annotation.LogModule;
import com.kbook.dto.book.BookProjection;
import com.kbook.dto.recommend.MatchScoreDetailVO;
import com.kbook.entity.User;
import com.kbook.service.tools.DimensionStatsService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 推荐匹配度计算器
 * <p>
 * 根据用户画像（年龄、性别、婚姻、子女、MBTI、职业、学历、创业意向、收入、阅读意图/心情）
 * 与书籍的 AI 生成相关度得分（relevanceScores）计算匹配度。
 * <p>
 * <b>核心算法流程：</b>
 * <ol>
 *   <li>解析书籍的 relevanceScores JSON，得到各维度的原始得分（0~1）</li>
 *   <li>对每个用户画像维度，从 scores 中取对应 key 的原始得分，计算偏差值（deviation）</li>
 *   <li>偏差值 = Z-Score（有统计服务时）或 rawScore - 0.5（无统计服务时）</li>
 *   <li>对部分维度（年龄、子女、职业、学历、收入、意图、心情）计算邻近维度衰减贡献</li>
 *   <li>对二值维度（性别、婚姻、有无子女）计算反向维度惩罚</li>
 *   <li>汇总所有维度的加权偏差 → 加权平均偏差 × 覆盖率因子 → Sigmoid 归一化 → 最终匹配分</li>
 * </ol>
 * <p>
 * <b>relevanceScores 数据结构示例：</b>
 * <pre>
 * {
 *   "20-29": 0.8,        // 年龄组得分
 *   "male": 0.6,         // 性别得分
 *   "married": 0.7,      // 婚姻得分
 *   "children_3_6": 0.5, // 子女年龄区间得分
 *   "INTJ": 0.9,         // MBTI 得分
 *   "tech": 0.7,         // 职业得分
 *   "bachelor": 0.6,     // 学历得分
 *   "entrepreneur_or_want": 0.4, // 创业意向得分
 *   "50k_150k": 0.5,     // 收入区间得分
 *   "growth": 0.8,       // 阅读意图得分
 *   "calm": 0.6          // 心情得分
 * }
 * </pre>
 *
 * @see DimensionStatsService Z-Score 统计服务
 * @see RecommendCoefficientService 权重系数服务
 */
@Slf4j
@LogModule("推荐计算")
public class RecommendMatchCalculator {

    /**
     * 匹配度计算权重枚举
     * <p>
     * 集中管理所有维度的权重配置，每个枚举项包含：
     * <ul>
     *   <li>key — 数据库 recommend_coefficient 表中的配置 key</li>
     *   <li>defaultValue — 默认权重值（数据库无配置时使用）</li>
     * </ul>
     * <p>
     * 权重设计原则：
     * <ul>
     *   <li>核心画像维度（年龄 1.5、MBTI 1.3）权重较高，是匹配度的主驱动</li>
     *   <li>中等画像维度（职业 1.0、性别 0.8、婚姻 0.8、子女 0.8、学历 0.8）</li>
     *   <li>瞬时状态维度（意图 1.2、心情 1.2）权重适中，辅助信号不主导匹配</li>
     *   <li>辅助画像维度（创业 0.6、收入 0.5）权重较低</li>
     *   <li>图书评分（0.5）作为客观质量信号</li>
     *   <li>衰减系数 0.4（邻近维度权重 = 主权重 × 0.4）</li>
     *   <li>反向惩罚系数 0.3（二值维度的反向扣分比例）</li>
     * </ul>
     */
    enum MatchWeight {
        // ---- 主维度权重 ----
        RATING("rating_weight", 0.8),                          // 图书评分：客观质量信号，适度权重让好书有加分
        AGE("age_weight", 1.5),                                // 年龄：核心画像维度，不同年龄段阅读偏好差异大，权重最高
        GENDER("gender_weight", 0.8),                          // 性别：中等权重，配合反向惩罚（OPPOSITE_PENALTY）使用
        MARRIED("married_weight", 0.8),                        // 婚姻状态：中等权重，配合反向惩罚使用
        CHILDREN("children_weight", 0.8),                      // 子女年龄区间：中等权重，配合邻近衰减（CHILDREN_ADJACENT_DECAY）使用
        MBTI("mbti_weight", 1.5),                              // MBTI 性格类型：较高权重，性格对阅读偏好影响显著
        OCCUPATION("occupation_weight", 1.2),                  // 职业：中等权重，配合邻近衰减使用
        EDUCATION("education_weight", 0.8),                    // 学历：中等权重，配合邻近衰减使用
        ENTREPRENEURSHIP("entrepreneurship_weight", 0.6),      // 创业意向：辅助权重，二值维度无邻近衰减
        INCOME("income_weight", 0.5),                          // 收入区间：辅助权重，配合邻近衰减使用
        INTENT("intent_weight", 1.8),                          // 阅读意图：中等权重，瞬时状态辅助信号（充电成长/共鸣陪伴/逃离放松/新鲜刺激/答案解惑）
        MOOD("mood_weight", 1.6),                              // 心情：中等权重，瞬时状态辅助信号（开心/平静/焦虑/低落/烦躁/疲惫）

        // ---- 衰减/惩罚系数 ----
        ADJACENT_DECAY("adjacent_decay", 0.4),                 // 邻近维度衰减系数：邻近维度的权重 = 主权重 × 此值
        CHILDREN_ADJACENT_DECAY("children_adjacent_decay", 0.4),// 子女区间专用衰减系数（与通用衰减分开配置）
        OPPOSITE_PENALTY("opposite_penalty", 0.3),             // 反向维度惩罚系数：性别/婚姻/子女等二值维度的反向扣分比例
        ;

        final String key;
        final double defaultValue;

        MatchWeight(String key, double defaultValue) {
            this.key = key;
            this.defaultValue = defaultValue;
        }

        double resolve(RecommendCoefficientService coefficientService) {
            return coefficientService.getCoefficient("MATCH", key, defaultValue);
        }
    }

    /**
     * 维度贡献记录 — 描述单个维度（或邻近维度）对匹配度的贡献
     *
     * @param dimension         维度标识（如 "age"、"gender"、"children"），同一维度可能有主贡献 + 邻近贡献
     * @param label             维度中文标签（如 "年龄: 20-29"、"邻近年龄: 30-39"），用于前端展示
     * @param rawScore          AI 生成的原始得分（0~1），直接从 relevanceScores JSON 中读取
     * @param deviation         偏差值：Z-Score（有统计服务时）或 rawScore - 0.5（无统计服务时）
     * @param weight            该贡献的权重（主维度权重 × 邻近衰减系数）
     * @param weightedDeviation 加权偏差 = deviation × weight，最终汇总用
     */
    record DimensionContribution(
            String dimension,
            String label,
            double rawScore,
            double deviation,
            double weight,
            double weightedDeviation
    ) {
    }

    /**
     * 计算用户与书籍的匹配度得分（核心入口方法）
     * <p>
     * 算法步骤：
     * 1. 解析书籍的 relevanceScores JSON
     * 2. 收集所有画像维度的贡献（collectContributions）
     * 3. 汇总加权偏差和权重
     * 4. 计算加权平均偏差
     * 5. 乘以覆盖率因子（画像维度越完整，因子越高）
     * 6. Sigmoid 归一化到 0~1
     *
     * @param user               用户实体（含画像信息）
     * @param book               书籍投影（含 relevanceScores JSON）
     * @param coefficientService 权重系数服务（可从数据库读取可调权重）
     * @param objectMapper       JSON 解析器（可为 null，内部会创建默认实例）
     * @param statsService       维度统计服务（可为 null，无统计服务时使用简单偏差 rawScore - 0.5）
     * @return 匹配度得分（0~1），0 表示无匹配数据
     */
    public static double calculateMatchScore(User user, BookProjection book,
                                             RecommendCoefficientService coefficientService,
                                             ObjectMapper objectMapper,
                                             DimensionStatsService statsService) {
        // 没有 relevanceScores 数据就无法计算匹配度，直接返回 0
        if (book.getRelevanceScores() == null || book.getRelevanceScores().isBlank()) {
            return 0.0;
        }

        // objectMapper 允许外部传入（方便测试注入），为 null 时内部创建一个默认的
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }

        try {
            // 将 JSON 字符串解析为树状结构，后续按维度 key 取值
            JsonNode scores = objectMapper.readTree(book.getRelevanceScores());
            // 收集所有画像维度对匹配度的贡献（主维度 + 邻近衰减 + 反向惩罚）
            List<DimensionContribution> contributions = collectContributions(user, book, scores, coefficientService, statsService);

            // 汇总加权偏差之和与权重之和，用于计算加权平均
            double totalDeviation = 0;
            double totalWeight = 0;
            for (DimensionContribution c : contributions) {
                totalDeviation += c.weightedDeviation();
                totalWeight += c.weight();
            }

            // 统计真正匹配到的维度数量（去重），用于覆盖率因子
            int matchedDimensions = (int) contributions.stream()
                    .map(DimensionContribution::dimension)
                    .distinct()
                    .count();

            // 如果没有任一维度有贡献（如用户完全没有画像），返回 0
            if (totalWeight == 0) return 0.0;

            // 加权平均偏差：综合各维度的偏离方向和强度
            double avgDeviation = totalDeviation / totalWeight;
            // 覆盖率因子：画像维度越完整，结果越可信，因子越高
            double coverageFactor = getCoverageFactor(matchedDimensions);
            // Sigmoid 归一化到 0~1 范围
            return normalizeScore(avgDeviation * coverageFactor);
        } catch (Exception e) {
            log.debug("解析相关度得分失败: bookId={} - {}", book.getId(), e.getMessage());
            return 0.0;
        }
    }

    /**
     * 计算用户与书籍的匹配度详情（含各维度得分明细）
     * <p>
     * 与 calculateMatchScore 共享同一套维度收集逻辑（collectContributions），
     * 但额外将每个维度的原始得分、权重、加权得分输出为前端可展示的明细列表。
     *
     * @param user               用户实体
     * @param book               书籍投影
     * @param coefficientService 权重系数服务
     * @param objectMapper       JSON 解析器
     * @param statsService       维度统计服务
     * @return 匹配度详情 VO（含总分、匹配维度数、覆盖率因子、各维度得分列表）
     */
    public static MatchScoreDetailVO calculateMatchScoreDetail(User user, BookProjection book,
                                                               RecommendCoefficientService coefficientService,
                                                               ObjectMapper objectMapper,
                                                               DimensionStatsService statsService) {
        // 先用标准算法算出总分
        double overallScore = calculateMatchScore(user, book, coefficientService, objectMapper, statsService);

        // 无 relevanceScores 时无法展开维度明细，返回一个仅包含总分的空壳
        if (book.getRelevanceScores() == null || book.getRelevanceScores().isBlank()) {
            return MatchScoreDetailVO.builder()
                    .bookId(book.getId())
                    .overallScore(overallScore)
                    .matchedDimensions(0)
                    .coverageFactor(0.35)
                    .dimensions(List.of())
                    .build();
        }

        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }

        try {
            JsonNode scores = objectMapper.readTree(book.getRelevanceScores());
            // 复用同一套收集逻辑，保持总分与明细的维度定义完全一致
            List<DimensionContribution> contributions = collectContributions(user, book, scores, coefficientService, statsService);

            // 去重统计匹配维度数
            int matchedDimensions = (int) contributions.stream()
                    .map(DimensionContribution::dimension)
                    .distinct()
                    .count();

            // 将每个贡献记录转为前端需要的 DimensionScore 格式
            List<MatchScoreDetailVO.DimensionScore> dimensions = contributions.stream()
                    .map(c -> MatchScoreDetailVO.DimensionScore.builder()
                            .dimension(c.dimension())
                            .label(c.label())
                            .score(round4(c.rawScore()))
                            .weight(c.weight())
                            .weightedScore(round4(c.rawScore() * c.weight()))
                            .build())
                    .toList();

            double coverageFactor = getCoverageFactor(matchedDimensions);

            return MatchScoreDetailVO.builder()
                    .bookId(book.getId())
                    .overallScore(Math.round(overallScore * 100.0) / 100.0)
                    .matchedDimensions(matchedDimensions)
                    .coverageFactor(coverageFactor)
                    .dimensions(dimensions)
                    .build();
        } catch (Exception e) {
            log.debug("解析相关度得分失败: bookId={} - {}", book.getId(), e.getMessage());
            // 解析失败时返回仅包含总分的结构
            return MatchScoreDetailVO.builder()
                    .bookId(book.getId())
                    .overallScore(overallScore)
                    .matchedDimensions(0)
                    .coverageFactor(0.35)
                    .dimensions(List.of())
                    .build();
        }
    }

    /**
     * 收集所有画像维度的贡献（核心调度方法）
     * <p>
     * 按维度依次调用各 add*Contributions 方法，将结果汇总到一个列表中。
     * 每个维度方法内部会添加主维度贡献，以及邻近维度衰减贡献（如适用）。
     */
    private static List<DimensionContribution> collectContributions(User user, BookProjection book, JsonNode scores,
                                                                    RecommendCoefficientService coefficientService,
                                                                    DimensionStatsService statsService) {
        List<DimensionContribution> list = new ArrayList<>();
        // 获取通用邻近衰减系数，多个维度共用
        double adjacentDecay = MatchWeight.ADJACENT_DECAY.resolve(coefficientService);

        // 按维度的"重要性"顺序调用：越重要的越靠前，便于调试阅读
        addRatingContribution(book, coefficientService, list);            // 图书评分（客观质量）
        addAgeContributions(user, scores, statsService, coefficientService, adjacentDecay, list);         // 年龄（核心）
        addGenderContributions(user, scores, statsService, coefficientService, list);                     // 性别
        addMarriedContributions(user, scores, statsService, coefficientService, list);                    // 婚姻
        addChildrenContributions(user, scores, statsService, coefficientService, list);                   // 子女
        addMbtiContributions(user, scores, statsService, coefficientService, list);                       // MBTI
        addOccupationContributions(user, scores, statsService, coefficientService, adjacentDecay, list);  // 职业
        addEducationContributions(user, scores, statsService, coefficientService, adjacentDecay, list);   // 学历
        addEntrepreneurshipContributions(user, scores, statsService, coefficientService, list);           // 创业意向
        addIncomeContributions(user, scores, statsService, coefficientService, adjacentDecay, list);      // 收入
        addMoodContributions(user, scores, statsService, coefficientService, adjacentDecay, list);        // 意图+心情

        return list;
    }

    /**
     * 收集图书评分维度的贡献
     * <p>
     * 评分是图书的客观质量指标，不依赖 relevanceScores，直接从图书属性读取。
     * 评分归一化：rating / 5.0 → 0~1 范围
     * 偏差值：normalizedRating - 0.5（与无统计服务时的偏差计算一致）
     */
    private static void addRatingContribution(BookProjection book, RecommendCoefficientService coefficientService,
                                              List<DimensionContribution> list) {
        Double rating = book.getRating();
        if (rating == null || rating < 0) return;

        double weight = MatchWeight.RATING.resolve(coefficientService);
        // 评分归一化到 0~1，满分 5 分对应 1.0
        double normalizedRating = Math.min(rating / 5.0, 1.0);
        // 以 0.5 为中点，高于 0.5 表示质量偏好，低于表示偏差
        double deviation = normalizedRating - 0.5;

        list.add(new DimensionContribution("rating", String.format("评分: %.1f", rating), normalizedRating, deviation, weight, deviation * weight));
    }

    /**
     * 收集年龄维度的贡献
     * <p>
     * 算法：
     * 1. 根据用户生日计算年龄，映射到年龄组（如 "20-29"）
     * 2. 从 scores 中取该年龄组的偏差值，乘以年龄权重
     * 3. 计算前后相邻年龄组（如 "10-19"、"30-39"）的衰减贡献
     * <p>
     * 邻近衰减原理：年龄是连续变量，相邻年龄组的偏好有相似性。
     * 比如 25 岁用户没有 "20-29" 数据时，"10-19" 和 "30-39" 也能提供参考，
     * 但置信度减半（adjacentDecay ≈ 0.4）。
     * <p>
     * 年龄组定义见 AiPromptConstants.java 第 146 行（"0-9","10-19",...,"60+"），
     * 映射见本类 getAgeGroup()。
     */
    private static void addAgeContributions(User user, JsonNode scores, DimensionStatsService statsService,
                                            RecommendCoefficientService coefficientService, double adjacentDecay,
                                            List<DimensionContribution> list) {
        if (user.getBirthday() == null) return;

        double weight = MatchWeight.AGE.resolve(coefficientService);
        // 根据生日计算当前年龄
        int age = java.time.Period.between(user.getBirthday(), java.time.LocalDate.now()).getYears();
        // 映射到年龄组（如 25 岁 → "20-29"）
        String ageGroup = getAgeGroup(age);

        // 主年龄组贡献
        double dev = getDeviation(statsService, ageGroup, scores);
        double rawScore = scores.has(ageGroup) ? scores.get(ageGroup).asDouble() : 0.0;
        list.add(new DimensionContribution("age", "年龄: " + ageGroup, rawScore, dev, weight, dev * weight));

        // 前后相邻年龄组的衰减贡献（direction=-1 前一组，+1 后一组）
        for (int dir : new int[]{-1, 1}) {
            String adj = getAdjacentAgeGroup(age, dir);
            if (adj != null && !adj.equals(ageGroup)) {
                double adjDev = getDeviation(statsService, adj, scores);
                double adjWeight = weight * adjacentDecay;
                list.add(new DimensionContribution("age", "邻近年龄: " + adj, rawScore, adjDev, adjWeight, adjDev * adjWeight));
            }
        }
    }

    /**
     * 收集性别维度的贡献
     * <p>
     * 反向惩罚逻辑：如果一本书对女性得分高，对男性用户应适当降低匹配度。
     * 惩罚力度由 OPPOSITE_PENALTY 控制（默认 0.5），
     * 即：oppDev > 0 时，从加权偏差中扣减 oppDev × penalty。
     * <p>
     * 性别定义见 User.java 第 68-70 行（MALE / FEMALE / OTHER），
     * AI prompt key 见 AiPromptConstants.java 第 147 行（"male","female"）。
     * 注意：OTHER 类型不会进入此方法（user.getGender() 为 "OTHER" 时，genderKey 会赋值为 "female"，
     * 这是一个潜在的 bug/待改进点）。
     */
    private static void addGenderContributions(User user, JsonNode scores, DimensionStatsService statsService,
                                               RecommendCoefficientService coefficientService,
                                               List<DimensionContribution> list) {
        if (user.getGender() == null) return;

        double weight = MatchWeight.GENDER.resolve(coefficientService);
        double penalty = MatchWeight.OPPOSITE_PENALTY.resolve(coefficientService);
        // User.java:68-70 定义的性别值：MALE / FEMALE / OTHER，但这里只映射 male/female
        String genderKey = "MALE".equals(user.getGender()) ? "male" : "female";

        // 主性别的偏差（正偏差 = 书籍更符合该性别偏好）
        double dev = getDeviation(statsService, genderKey, scores);
        double rawScore = scores.has(genderKey) ? scores.get(genderKey).asDouble() : 0.0;
        double weightedDev = dev * weight;

        // 反向性别的偏差：如果反向性别也有正偏差，说明这本书偏"通用的"，要适度扣分
        // 比如用户是男性，如果书籍也适合女性（oppDev > 0），扣减 penalty 比例
        String oppositeKey = "MALE".equals(user.getGender()) ? "female" : "male";
        double oppDev = getDeviation(statsService, oppositeKey, scores);
        if (oppDev > 0) weightedDev -= oppDev * penalty;

        list.add(new DimensionContribution("gender", "性别: " + ("MALE".equals(user.getGender()) ? "男" : "女"), rawScore, dev, weight, weightedDev));
    }

    /**
     * 收集婚姻维度的贡献
     * <p>
     * 算法与性别维度类似：主维度偏差 + 反向维度惩罚。
     * 婚姻定义见 User.java 第 72-74 行（Boolean: true=已婚, false=未婚），
     * AI prompt key 见 AiPromptConstants.java 第 147 行（"married","unmarried"）。
     */
    private static void addMarriedContributions(User user, JsonNode scores, DimensionStatsService statsService,
                                                RecommendCoefficientService coefficientService,
                                                List<DimensionContribution> list) {
        if (user.getMarried() == null) return;

        double weight = MatchWeight.MARRIED.resolve(coefficientService);
        double penalty = MatchWeight.OPPOSITE_PENALTY.resolve(coefficientService);
        // User.java:72-74: Boolean married，true → married，false → unmarried
        String marryKey = user.getMarried() ? "married" : "unmarried";

        double dev = getDeviation(statsService, marryKey, scores);
        double rawScore = scores.has(marryKey) ? scores.get(marryKey).asDouble() : 0.0;
        double weightedDev = dev * weight;

        // 反向惩罚：已婚用户看到未婚偏好高的书，适当降低匹配度
        String oppositeKey = user.getMarried() ? "unmarried" : "married";
        double oppDev = getDeviation(statsService, oppositeKey, scores);
        if (oppDev > 0) weightedDev -= oppDev * penalty;

        list.add(new DimensionContribution("married", user.getMarried() ? "已婚" : "未婚", rawScore, dev, weight, weightedDev));
    }

    /**
     * 收集子女维度的贡献
     * <p>
     * 新格式：childrenAgeRanges 字段（User.java 第 80-82 行），值为逗号分隔的
     * "children_0_2,children_3_6" 等，AI prompt key 见 AiPromptConstants.java 第 148 行。
     * 旧格式：hasChildren 字段（User.java 第 76-78 行），值为 true/false。
     * <p>
     * 新格式下对每个子女区间计算主贡献 + 邻近衰减。
     * 旧格式下只做二值判断 + 反向惩罚。
     */
    private static void addChildrenContributions(User user, JsonNode scores, DimensionStatsService statsService,
                                                 RecommendCoefficientService coefficientService,
                                                 List<DimensionContribution> list) {
        if (user.getChildrenAgeRanges() != null && !user.getChildrenAgeRanges().isBlank()) {
            // 新格式：多个子女年龄区间，每个区间独立计算贡献
            String[] ranges = user.getChildrenAgeRanges().split(",");
            double weight = MatchWeight.CHILDREN.resolve(coefficientService);
            double childDecay = MatchWeight.CHILDREN_ADJACENT_DECAY.resolve(coefficientService);

            for (String range : ranges) {
                String childKey = range.trim().toLowerCase();
                if (childKey.isEmpty()) continue;
                if (!scores.has(childKey)) continue;

                // 当前子女区间的主贡献
                double dev = getDeviation(statsService, childKey, scores);
                double rawScore = scores.get(childKey).asDouble();
                list.add(new DimensionContribution("children", "子女: " + getChildRangeLabel(childKey), rawScore, dev, weight, dev * weight));

                // 相邻年龄区间的衰减贡献（如 3-6 岁的相邻是 0-2 岁和 7-12 岁）
                for (String adj : getAdjacentChildRanges(childKey)) {
                    double adjDev = getDeviation(statsService, adj, scores);
                    double adjWeight = weight * childDecay;
                    list.add(new DimensionContribution("children", "邻近子女: " + getChildRangeLabel(adj), rawScore, adjDev, adjWeight, adjDev * adjWeight));
                }
            }
        } else if (user.getHasChildren() != null) {
            // 旧格式：只有 hasChildren 布尔值，做二值判断 + 反向惩罚
            double weight = MatchWeight.CHILDREN.resolve(coefficientService);
            double penalty = MatchWeight.OPPOSITE_PENALTY.resolve(coefficientService);
            String childKey = user.getHasChildren() ? "hasChildren" : "no_children";
            double rawScore = scores.has(childKey) ? scores.get(childKey).asDouble() : 0.0;
            double dev = scores.has(childKey) ? getDeviation(statsService, childKey, scores) : 0.0;
            double weightedDev = dev * weight;

            // 反向惩罚：有孩子的用户遇到"无孩"偏好高的书，适当扣分
            String oppositeKey = user.getHasChildren() ? "no_children" : "hasChildren";
            double oppDev = getDeviation(statsService, oppositeKey, scores);
            if (oppDev > 0) weightedDev -= oppDev * penalty;

            list.add(new DimensionContribution("hasChildren", user.getHasChildren() ? "有孩子" : "无孩子", rawScore, dev, weight, weightedDev));
        }
    }

    /**
     * 收集 MBTI 维度的贡献
     * <p>
     * 直接取用户 MBTI 类型在 scores 中的偏差值，乘以 MBTI 权重。
     * 注意：当前未实现邻近 MBTI 类型衰减（getAdjacentMbti 已实现但未调用），
     * 因为 MBTI 的 16 种类型之间差异较大，邻近类型的参考价值不如年龄/职业等连续维度。
     * <p>
     * MBTI 所有 16 种类型见 AiPromptConstants.java 第 149 行。
     */
    private static void addMbtiContributions(User user, JsonNode scores, DimensionStatsService statsService,
                                             RecommendCoefficientService coefficientService,
                                             List<DimensionContribution> list) {
        if (user.getMbti() == null) return;

        double weight = MatchWeight.MBTI.resolve(coefficientService);
        // MBTI 统一转大写，与 AI 生成的 JSON key 保持一致
        String mbtiKey = user.getMbti().toUpperCase();

        double dev = getDeviation(statsService, mbtiKey, scores);
        double rawScore = scores.has(mbtiKey) ? scores.get(mbtiKey).asDouble() : 0.0;
        list.add(new DimensionContribution("mbti", "MBTI: " + mbtiKey, rawScore, dev, weight, dev * weight));
    }

    /**
     * 收集职业维度的贡献
     * <p>
     * 用户可填写多个职业（逗号分隔），对每个职业计算主维度贡献和邻近职业衰减贡献。
     * 邻近关系由 getAdjacentOccupations 定义，如 tech ↔ education, finance ↔ management。
     * <p>
     * 职业定义见 User.java 第 88 行（STUDENT / TECH / FINANCE / ... / OTHER），
     * AI prompt key 见 AiPromptConstants.java 第 150 行（小写形式）。
     */
    private static void addOccupationContributions(User user, JsonNode scores, DimensionStatsService statsService,
                                                   RecommendCoefficientService coefficientService, double adjacentDecay,
                                                   List<DimensionContribution> list) {
        if (user.getOccupation() == null || user.getOccupation().isBlank()) return;

        double weight = MatchWeight.OCCUPATION.resolve(coefficientService);

        // 每个职业独立计算（一个用户可以有多个职业标签）
        for (String userOcc : user.getOccupation().split(",")) {
            String occKey = userOcc.trim().toLowerCase();
            if (occKey.isEmpty()) continue;

            // 主职业贡献
            double dev = getDeviation(statsService, occKey, scores);
            double rawScore = scores.has(occKey) ? scores.get(occKey).asDouble() : 0.0;
            list.add(new DimensionContribution("occupation", "职业: " + getOccupationLabel(occKey), rawScore, dev, weight, dev * weight));

            // 邻近职业的衰减贡献
            for (String adj : getAdjacentOccupations(occKey)) {
                double adjDev = getDeviation(statsService, adj, scores);
                double adjWeight = weight * adjacentDecay;
                list.add(new DimensionContribution("occupation", "邻近职业: " + getOccupationLabel(adj), rawScore, adjDev, adjWeight, adjDev * adjWeight));
            }
        }
    }

    /**
     * 收集学历维度的贡献
     * <p>
     * 取用户学历在 scores 中的偏差值，并计算邻近学历衰减贡献。
     * 学历邻近链：high_school ↔ college ↔ bachelor ↔ master ↔ doctorate
     * <p>
     * 学历定义见 User.java 第 92 行（HIGH_SCHOOL / COLLEGE / BACHELOR / MASTER / DOCTORATE / OTHER），
     * AI prompt key 见 AiPromptConstants.java 第 151 行。
     * 注意：AI prompt 中"其他"使用 "other_edu" 而非 "other"，而本类 getAdjacentEducations
     * 中匹配的是 "other"——如果 AI 生成的是 "other_edu" 则邻近衰减会 miss。
     */
    private static void addEducationContributions(User user, JsonNode scores, DimensionStatsService statsService,
                                                  RecommendCoefficientService coefficientService, double adjacentDecay,
                                                  List<DimensionContribution> list) {
        if (user.getAspirationEducation() == null) return;

        String eduKey = user.getAspirationEducation().toLowerCase();
        double weight = MatchWeight.EDUCATION.resolve(coefficientService);

        // 主学历贡献
        double dev = getDeviation(statsService, eduKey, scores);
        double rawScore = scores.has(eduKey) ? scores.get(eduKey).asDouble() : 0.0;
        list.add(new DimensionContribution("education", "学历: " + getEducationLabel(user.getAspirationEducation()), rawScore, dev, weight, dev * weight));

        // 相邻学历的衰减贡献
        for (String adj : getAdjacentEducations(eduKey)) {
            double adjDev = getDeviation(statsService, adj, scores);
            double adjWeight = weight * adjacentDecay;
            list.add(new DimensionContribution("education", "邻近学历: " + getEducationLabel(adj), rawScore, adjDev, adjWeight, adjDev * adjWeight));
        }
    }

    /**
     * 收集创业意向维度的贡献
     * <p>
     * 直接取用户创业意向在 scores 中的偏差值，无邻近衰减。
     * 创业意向只有两个值（创业/不创业），没有中间状态，不适合邻近衰减。
     * <p>
     * 创业意向定义见 User.java 第 96-98 行（ENTREPRENEUR_OR_WANT / NOT_INTERESTED），
     * AI prompt key 见 AiPromptConstants.java 第 152 行。
     * 注意：User.java 中值为 "NOT_INTERESTED"，AI prompt 中为 "notInterested"（驼峰），
     * 二者不匹配——如果 AI 生成的是 "notInterested" 则 getDeviation 会 miss。
     */
    private static void addEntrepreneurshipContributions(User user, JsonNode scores, DimensionStatsService statsService,
                                                         RecommendCoefficientService coefficientService,
                                                         List<DimensionContribution> list) {
        if (user.getEntrepreneurship() == null || user.getEntrepreneurship().isBlank()) return;

        String entreKey = user.getEntrepreneurship().toLowerCase();
        double weight = MatchWeight.ENTREPRENEURSHIP.resolve(coefficientService);

        double dev = getDeviation(statsService, entreKey, scores);
        double rawScore = scores.has(entreKey) ? scores.get(entreKey).asDouble() : 0.0;
        list.add(new DimensionContribution("entrepreneurship", getEntrepreneurshipLabel(user.getEntrepreneurship()), rawScore, dev, weight, dev * weight));
    }

    /**
     * 收集收入维度的贡献
     * <p>
     * 取用户收入区间在 scores 中的偏差值，并计算邻近收入区间衰减贡献。
     * 收入邻近链：under_50k ↔ 50k_150k ↔ 150k_300k ↔ 300k_500k ↔ 500k_1m ↔ over_1m
     * 用户选择 "PREFER_NOT_TO_SAY" 时跳过该维度。
     * <p>
     * 收入定义见 User.java 第 100 行（UNDER_50K / 50K_150K / ... / PREFER_NOT_TO_SAY），
     * AI prompt key 见 AiPromptConstants.java 第 153 行。
     */
    private static void addIncomeContributions(User user, JsonNode scores, DimensionStatsService statsService,
                                               RecommendCoefficientService coefficientService, double adjacentDecay,
                                               List<DimensionContribution> list) {
        if (user.getAspirationIncome() == null || user.getAspirationIncome().isBlank()
                || "PREFER_NOT_TO_SAY".equalsIgnoreCase(user.getAspirationIncome())) return;

        String incomeKey = user.getAspirationIncome().toLowerCase();
        double weight = MatchWeight.INCOME.resolve(coefficientService);

        // 主收入区间贡献
        double dev = getDeviation(statsService, incomeKey, scores);
        double rawScore = scores.has(incomeKey) ? scores.get(incomeKey).asDouble() : 0.0;
        list.add(new DimensionContribution("income", getAnnualIncomeLabel(user.getAspirationIncome()), rawScore, dev, weight, dev * weight));

        // 相邻收入区间的衰减贡献
        for (String adj : getAdjacentIncomes(incomeKey)) {
            double adjDev = getDeviation(statsService, adj, scores);
            double adjWeight = weight * adjacentDecay;
            list.add(new DimensionContribution("income", "邻近收入: " + getAnnualIncomeLabel(adj), rawScore, adjDev, adjWeight, adjDev * adjWeight));
        }
    }

    /**
     * 收集阅读意图+心情维度的贡献
     * <p>
     * 用户 mood 字段支持两种格式：
     * <ul>
     *   <li>新格式："INTENT|MOOD"（如 "growth|calm"），同时包含阅读意图和心情</li>
     *   <li>旧格式：纯 MOOD（如 "calm"），仅包含心情</li>
     * </ul>
     * 意图和心情共用同一个字段存储，用 '|' 分隔。
     * 意图邻近链：growth ↔ insight ↔ comfort ↔ escape ↔ excite
     * 心情邻近链：happy ↔ calm; anxious ↔ sad ↔ tired ↔ frustrated
     * <p>
     * mood 字段定义见 User.java 第 104 行，
     * AI prompt key 见 AiPromptConstants.java 第 154-155 行。
     * 注意：前端 profile 页还定义了 MOTIVATED 和 CURIOUS 两种心情，
     * 但后端 AiPromptConstants 未包含这两个值，属于前端多余定义。
     */
    private static void addMoodContributions(User user, JsonNode scores, DimensionStatsService statsService,
                                             RecommendCoefficientService coefficientService, double adjacentDecay,
                                             List<DimensionContribution> list) {
        if (user.getMood() == null || user.getMood().isBlank()) return;

        String moodRaw = user.getMood();
        String intentKey = null;
        String moodKey;

        // 解析 "意图|心情" 格式，兼容纯心情的旧格式
        int pipeIdx = moodRaw.indexOf('|');
        if (pipeIdx > 0) {
            intentKey = moodRaw.substring(0, pipeIdx).toLowerCase().trim();
            moodKey = moodRaw.substring(pipeIdx + 1).toLowerCase().trim();
        } else {
            moodKey = moodRaw.toLowerCase().trim();
        }

        // 意图维度贡献 + 相关意图衰减
        if (intentKey != null && !intentKey.isEmpty()) {
            double weight = MatchWeight.INTENT.resolve(coefficientService);
            double dev = getDeviation(statsService, intentKey, scores);
            double rawScore = scores.has(intentKey) ? scores.get(intentKey).asDouble() : 0.0;
            list.add(new DimensionContribution("intent", "意图: " + getIntentLabel(intentKey), rawScore, dev, weight, dev * weight));

            for (String adj : getRelatedIntents(intentKey)) {
                double adjDev = getDeviation(statsService, adj, scores);
                double adjWeight = weight * adjacentDecay;
                list.add(new DimensionContribution("intent", "相关意图: " + getIntentLabel(adj), rawScore, adjDev, adjWeight, adjDev * adjWeight));
            }
        }

        // 心情维度贡献 + 相关心情衰减
        if (!moodKey.isEmpty()) {
            double weight = MatchWeight.MOOD.resolve(coefficientService);
            double dev = getDeviation(statsService, moodKey, scores);
            double rawScore = scores.has(moodKey) ? scores.get(moodKey).asDouble() : 0.0;
            list.add(new DimensionContribution("mood", "心情: " + getMoodLabel(moodKey), rawScore, dev, weight, dev * weight));

            for (String adj : getRelatedMoods(moodKey)) {
                double adjDev = getDeviation(statsService, adj, scores);
                double adjWeight = weight * adjacentDecay;
                list.add(new DimensionContribution("mood", "相关心情: " + getMoodLabel(adj), rawScore, adjDev, adjWeight, adjDev * adjWeight));
            }
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 获取覆盖率因子
     * <p>
     * 用户画像填写的维度越多，覆盖率因子越高，匹配度越可信。
     * 维度数不足时降低匹配度，避免仅凭 1-2 个维度就给出高分。
     * <p>
     * 因子设计：
     * <ul>
     *   <li>11 维度（全开）= 1.0（完全信任）</li>
     *   <li>6 维度 = 0.9（大部分画像完整）</li>
     *   <li>3 维度 = 0.78（仅部分画像）</li>
     *   <li>1 维度 = 0.6（参考价值有限）</li>
     *   <li>0 维度 = 0.5（兜底，几乎无画像）</li>
     * </ul>
     */
    static double getCoverageFactor(int matchedDimensions) {
        return switch (matchedDimensions) {
            case 11 -> 1.0;
            case 10 -> 0.99;
            case 9 -> 0.97;
            case 8 -> 0.95;
            case 7 -> 0.93;
            case 6 -> 0.90;
            case 5 -> 0.87;
            case 4 -> 0.83;
            case 3 -> 0.78;
            case 2 -> 0.70;
            case 1 -> 0.60;
            default -> 0.50;
        };
    }

    /**
     * Sigmoid 归一化
     * <p>
     * 将原始分数映射到 0~1 范围。
     * 使用改进的 Sigmoid 函数：f(x) = 1 / (1 + e^(-4 * (x - 0.5)))
     * <ul>
     *   <li>中点：0.5（输入 0.5 → 输出 0.5）</li>
     *   <li>斜率：4（在 0.5 附近梯度适中，两端快速饱和）</li>
     *   <li>输入 ≤ 0 → 输出 0（硬截断）</li>
     *   <li>四舍五入到 4 位小数</li>
     * </ul>
     * 选择 Sigmoid 而非 Min-Max 的原因：
     * Sigmoid 在中间区域有区分度，两端自然饱和，符合"匹配度"的感知特性；
     * 即 0.7 和 0.8 的差异对用户有意义，而 0.97 和 0.99 的差异可以忽略。
     */
    static double normalizeScore(double rawScore) {
        if (rawScore <= 0) return 0.0;
        double normalized = 1.0 / (1.0 + Math.exp(-4.0 * (rawScore - 0.5)));
        return Math.round(normalized * 10000.0) / 10000.0;
    }

    /**
     * 保留 4 位小数
     */
    private static double round4(double val) {
        return Math.round(val * 10000.0) / 10000.0;
    }

    /**
     * 获取指定维度的偏差值
     * <p>
     * 有统计服务 → Z-Score 标准化：(rawScore - mean) / stddev
     * 无统计服务 → 简单偏差：rawScore - 0.5（以 0.5 为中点）
     * <p>
     * 两种模式的差异：
     * <ul>
     *   <li>Z-Score 考虑了全体书籍在该维度的分布，可以识别"相对高/低"</li>
     *   <li>简单偏差假设 0.5 是中性值，高于 0.5 即偏好</li>
     * </ul>
     */
    private static double getDeviation(DimensionStatsService statsService, String key, JsonNode scores) {
        if (!scores.has(key)) return 0.0;
        double rawScore = scores.get(key).asDouble();
        if (statsService != null) {
            // Z-Score：偏差 = (原始分 - 均值) / 标准差
            return statsService.getZScore(key, rawScore);
        }
        // 无统计服务时的兜底偏差计算
        return rawScore - 0.5;
    }

    // ==================== 维度映射 ====================

    /**
     * 年龄 → 年龄组映射
     * 将连续年龄映射到离散年龄组：
     * 0-9, 10-19, 20-29, ..., 60+
     * 每 10 岁一组，AI 按这些 key 生成 relevanceScores（见 AiPromptConstants.java 第 146 行）
     */
    public static String getAgeGroup(int age) {
        if (age < 10) return "0-9";
        if (age < 20) return "10-19";
        if (age < 30) return "20-29";
        if (age < 40) return "30-39";
        if (age < 50) return "40-49";
        if (age < 60) return "50-59";
        return "60+";
    }

    /**
     * 获取相邻年龄组
     *
     * @param age       用户当前年龄
     * @param direction -1=前一组（更年轻），1=后一组（更年长）
     * @return 相邻年龄组的 key，超出范围返回 null
     */
    static String getAdjacentAgeGroup(int age, int direction) {
        // 年龄组边界数组，与 AiPromptConstants.java 第 146 行的年龄组定义保持一致
        int[] boundaries = {0, 10, 20, 30, 40, 50, 60, Integer.MAX_VALUE};
        int currentIdx = -1;
        // 找到当前年龄所在的组索引
        for (int i = 0; i < boundaries.length - 1; i++) {
            if (age >= boundaries[i] && age < boundaries[i + 1]) {
                currentIdx = i;
                break;
            }
        }
        if (currentIdx < 0) return null;
        // 计算相邻组索引
        int adjacentIdx = currentIdx + direction;
        if (adjacentIdx < 0 || adjacentIdx >= boundaries.length - 1) return null;
        // 用相邻组的下界年龄重新映射
        return getAgeGroup(boundaries[adjacentIdx]);
    }

    /**
     * 获取相邻 MBTI 类型
     * 对 MBTI 的四维（I/E, N/S, T/F, J/P）依次取反，生成 4 个相邻类型。
     * 例如 INTJ → ENTJ, ISTJ, INFJ, INTP
     * 当前未被 addMbtiContributions 使用，为后续优化预留。
     * <p>
     * MBTI 所有 16 种类型见 AiPromptConstants.java 第 149 行，
     * 每个维度取反规则：
     * - I ↔ E（内外向）
     * - N ↔ S（直觉/实感）
     * - T ↔ F（思考/情感）
     * - J ↔ P（判断/感知）
     */
    static List<String> getAdjacentMbti(String mbti) {
        if (mbti == null || mbti.length() != 4) return List.of();
        List<String> adjacent = new java.util.ArrayList<>();
        char[] chars = mbti.toCharArray();
        // 每个维度的两个取值，按 MBTI 四维度定义
        char[][] flips = {
                {chars[0], chars[0] == 'I' ? 'E' : 'I'},  // 第一维：I/E
                {chars[1], chars[1] == 'N' ? 'S' : 'N'},  // 第二维：N/S
                {chars[2], chars[2] == 'T' ? 'F' : 'T'},  // 第三维：T/F
                {chars[3], chars[3] == 'J' ? 'P' : 'J'}   // 第四维：J/P
        };
        for (int i = 0; i < 4; i++) {
            char[] copy = chars.clone();
            copy[i] = flips[i][1];
            adjacent.add(new String(copy));
        }
        return adjacent;
    }

    /**
     * 获取相邻职业
     * 定义职业之间的邻近关系，用于衰减贡献。
     * 关系网：student ↔ education ↔ tech ↔ freelance ↔ arts; finance ↔ management
     * <p>
     * 设计依据：
     * - student → education：学生和教育者关注的内容有重叠（如学习方法、学科知识）
     * - tech → education, freelance：技术人员既会撰写教程（教育），也可能接单（自由职业）
     * - finance → management：金融和管理都涉及企业决策和商业逻辑
     * - arts → freelance, education：文艺从业者常以自由职业形式工作，也从事教学
     * - retired → 无：退休用户的偏好独立性强，无明确邻近职业
     * <p>
     * 职业定义见 User.java 第 88 行，AI prompt key 见 AiPromptConstants.java 第 150 行。
     */
    static List<String> getAdjacentOccupations(String occupation) {
        return switch (occupation.toLowerCase()) {
            case "student" -> List.of("education");          // 学生 → 教育：学习内容与教学材料有交叉
            case "tech" -> List.of("education", "freelance");    // 技术 → 教育/自由职业：技术人员常出教程和接外包
            case "finance" -> List.of("management");         // 金融 → 管理：商业决策类图书受众重叠
            case "education" -> List.of("student", "tech");      // 教育 → 学生/技术：读者群与作者群相互覆盖
            case "medical" -> List.of("education");          // 医疗 → 教育：医学教育类图书的天然关联
            case "arts" -> List.of("freelance", "education");    // 文艺 → 自由职业/教育：艺术家常教学或自由创作
            case "management" -> List.of("finance");         // 管理 → 金融：管理者的财务知识需求
            case "freelance" -> List.of("arts", "tech");         // 自由职业 → 文艺/技术：自由职业的两大主要方向
            case "retired" -> List.of();                         // 退休：独立偏好，无交叉
            case "other" -> List.of();                           // 其他：无映射关系
            default -> List.of();
        };
    }

    /**
     * 获取相邻学历
     * 学历由低到高：high_school ↔ college ↔ bachelor ↔ master ↔ doctorate
     * 每个学历只与相邻一级（更低和/或更高）关联，跨级不加关联（如 high_school 只关联 college，不关联 bachelor）。
     * <p>
     * 学历定义见 User.java 第 92 行，AI prompt key 见 AiPromptConstants.java 第 151 行。
     * 注意：AI prompt 中"其他"用 "other_edu"，而此处匹配 "other"——如果 AI 生成 "other_edu" 则衰减 miss。
     */
    static List<String> getAdjacentEducations(String education) {
        return switch (education.toLowerCase()) {
            case "high_school" -> List.of("college");               // 高中 → 大专：相邻一级
            case "college" -> List.of("high_school", "bachelor");       // 大专 → 高中/本科：双向相邻
            case "bachelor" -> List.of("college", "master");            // 本科 → 大专/硕士：双向相邻
            case "master" -> List.of("bachelor", "doctorate");          // 硕士 → 本科/博士：双向相邻
            case "doctorate" -> List.of("master");                  // 博士 → 硕士：相邻一级
            case "other" -> List.of();                                  // 其他：无邻近学历
            default -> List.of();
        };
    }

    /**
     * 获取相邻收入区间
     * 收入由低到高：under_50k ↔ 50k_150k ↔ 150k_300k ↔ 300k_500k ↔ 500k_1m ↔ over_1m
     * 每个收入区间只与相邻一级关联。
     * <p>
     * 收入定义见 User.java 第 100 行，AI prompt key 见 AiPromptConstants.java 第 153 行。
     */
    static List<String> getAdjacentIncomes(String income) {
        return switch (income.toLowerCase()) {
            case "under_50k" -> List.of("50k_150k");                           // 5万以下 → 5~15万
            case "50k_150k" -> List.of("under_50k", "150k_300k");                  // 5~15万 → 5万以下/15~30万
            case "150k_300k" -> List.of("50k_150k", "300k_500k");                  // 15~30万 → 5~15万/30~50万
            case "300k_500k" -> List.of("150k_300k", "500k_1m");                   // 30~50万 → 15~30万/50~100万
            case "500k_1m" -> List.of("300k_500k", "over_1m");                     // 50~100万 → 30~50万/100万+
            case "over_1m" -> List.of("500k_1m");                              // 100万+ → 50~100万
            default -> List.of();
        };
    }

    /**
     * 获取相关心情
     * 心情关系网：
     * - happy ↔ calm（正面情绪相互关联）
     * - anxious ↔ sad ↔ tired ↔ frustrated（负面情绪相互关联）
     * 正负情绪之间不关联（如 happy 不会关联 sad）
     * <p>
     * 心情定义见 User.java 第 104 行的 mood 字段后半部分，
     * AI prompt key 见 AiPromptConstants.java 第 154 行（happy, calm, anxious, sad, frustrated, tired）。
     * 注意：前端 profile/index.tsx 还定义了 MOTIVATED 和 CURIOUS 两种心情，
     * 但后端不支持，getRelatedMoods 会走 default 返回空列表。
     */
    static List<String> getRelatedMoods(String mood) {
        return switch (mood.toLowerCase()) {
            case "happy" -> List.of("calm");                        // 开心 → 平静（正面情绪关联）
            case "calm" -> List.of("happy");                        // 平静 → 开心（正面情绪关联）
            case "anxious" -> List.of("sad", "tired", "frustrated");    // 焦虑 → 低落/疲惫/烦躁（负面链起点）
            case "sad" -> List.of("anxious", "tired", "frustrated");    // 低落 → 焦虑/疲惫/烦躁（负面链中间）
            case "tired" -> List.of("sad", "anxious");                  // 疲惫 → 低落/焦虑（负面链中间）
            case "frustrated" -> List.of("anxious", "sad");             // 烦躁 → 焦虑/低落（负面链中间）
            default -> List.of();
        };
    }

    /**
     * 获取相关阅读意图
     * 意图关系链：growth ↔ insight ↔ comfort ↔ escape ↔ excite
     * 相邻意图之间有相似性，不相邻的意图不加关联（如 growth 不关联 escape）
     * <p>
     * 意图定义见 User.java 第 104 行的 mood 字段前半部分，
     * AI prompt key 见 AiPromptConstants.java 第 155 行（growth, comfort, escape, excite, insight）。
     * <p>
     * 意图语义：
     * - growth（充电成长）：自我提升、学习新知识
     * - insight（答案解惑）：寻找具体问题的答案或人生感悟
     * - comfort（共鸣陪伴）：寻求情感共鸣、温暖治愈
     * - escape（逃离放松）：暂时逃离现实压力、放松身心
     * - excite（新鲜刺激）：追求新奇体验、兴奋感
     */
    public static List<String> getRelatedIntents(String intent) {
        return switch (intent.toLowerCase()) {
            case "growth" -> List.of("insight");             // 充电成长 ↔ 答案解惑：学习与解惑天然关联
            case "insight" -> List.of("growth", "comfort");      // 答案解惑 ↔ 充电成长/共鸣陪伴：解惑后可成长或寻求共鸣
            case "comfort" -> List.of("escape", "insight");      // 共鸣陪伴 ↔ 逃离放松/答案解惑：陪伴与放松、解惑相连
            case "escape" -> List.of("comfort", "excite");       // 逃离放松 ↔ 共鸣陪伴/新鲜刺激：放松后可寻求陪伴或刺激
            case "excite" -> List.of("escape");              // 新鲜刺激 ↔ 逃离放松：刺激感与逃离感相近
            default -> List.of();
        };
    }

    // ==================== 中文标签 ====================

    /**
     * 阅读意图中文标签映射
     * AiPromptConstants.java 第 155 行定义的 5 种意图，
     * 对应前端 MoodQuickSwitch.tsx 中的定义（GROWTH, COMFORT, ESCAPE, EXCITE, INSIGHT）。
     */
    public static String getIntentLabel(String intent) {
        if (intent == null) return "";
        return switch (intent.toLowerCase()) {
            case "growth" -> "充电成长";     // 自我提升、学习新知识
            case "comfort" -> "共鸣陪伴";    // 情感共鸣、温暖治愈
            case "escape" -> "逃离放松";     // 暂时逃离压力、放松身心
            case "excite" -> "新鲜刺激";     // 新奇体验、兴奋感
            case "insight" -> "答案解惑";    // 寻找具体答案或人生感悟
            default -> intent;
        };
    }

    /**
     * 职业中文标签映射
     * User.java 第 88 行定义的 10 种职业，
     * AiPromptConstants.java 第 150 行定义了对应的小写 AI prompt key。
     */
    public static String getOccupationLabel(String occupation) {
        if (occupation == null) return "";
        return switch (occupation.toUpperCase()) {
            case "STUDENT" -> "学生";         // 学生
            case "TECH" -> "技术/IT";         // 技术/IT
            case "FINANCE" -> "金融/商业";     // 金融/商业
            case "EDUCATION" -> "教育/科研";   // 教育/科研
            case "MEDICAL" -> "医疗/健康";     // 医疗/健康
            case "ARTS" -> "文艺/传媒";        // 文艺/传媒
            case "MANAGEMENT" -> "管理/行政";  // 管理/行政
            case "FREELANCE" -> "自由职业";    // 自由职业
            case "RETIRED" -> "退休";         // 退休
            case "OTHER" -> "其他";           // 其他
            default -> occupation;
        };
    }

    /**
     * 学历中文标签映射
     * User.java 第 92 行定义的 aspirationEducation 字段值，
     * AiPromptConstants.java 第 151 行定义了对应的小写 AI prompt key。
     */
    public static String getEducationLabel(String education) {
        if (education == null) return "";
        return switch (education.toUpperCase()) {
            case "HIGH_SCHOOL" -> "高中及以下";
            case "COLLEGE" -> "大专";
            case "BACHELOR" -> "本科";
            case "MASTER" -> "硕士";
            case "DOCTORATE" -> "博士";
            case "OTHER" -> "其他";
            default -> education;
        };
    }

    /**
     * 心情中文标签映射
     * AiPromptConstants.java 第 154 行定义的 6 种心情，
     * 对应前端 MoodQuickSwitch.tsx 中的定义（HAPPY, CALM, ANXIOUS, SAD, FRUSTRATED, TIRED）。
     * <p>
     * 注意：前端 profile/index.tsx 还定义了 MOTIVATED 和 CURIOUS，后端不支持，会走 default。
     */
    public static String getMoodLabel(String mood) {
        if (mood == null) return "";
        return switch (mood.toUpperCase()) {
            case "HAPPY" -> "开心";
            case "CALM" -> "平静";
            case "ANXIOUS" -> "焦虑";
            case "SAD" -> "低落";
            case "FRUSTRATED" -> "烦躁";
            case "TIRED" -> "疲惫";
            default -> mood;
        };
    }

    /**
     * 创业意向中文标签映射
     * User.java 第 96-98 行定义的 entrepreneurship 字段值，
     * AiPromptConstants.java 第 152 行定义了对应 AI prompt key。
     * 注意：数据库中存储的是 "NOT_INTERESTED"，AI prompt 中用的是 "notInterested"（驼峰），
     * 二者不匹配，可能导致创业意向维度的偏差计算 miss。
     */
    public static String getEntrepreneurshipLabel(String entrepreneurship) {
        if (entrepreneurship == null) return "";
        return switch (entrepreneurship.toUpperCase()) {
            case "ENTREPRENEUR_OR_WANT" -> "正在创业/想创业";
            case "NOT_INTERESTED" -> "暂不考虑";
            default -> entrepreneurship;
        };
    }

    /**
     * 子女年龄区间中文标签映射
     * User.java 第 80-82 行定义的 childrenAgeRanges 字段值，
     * AiPromptConstants.java 第 148 行定义了对应 AI prompt key。
     */
    public static String getChildRangeLabel(String childKey) {
        if (childKey == null) return "";
        return switch (childKey.toLowerCase()) {
            case "children_0_2" -> "0-2岁";       // 婴幼儿
            case "children_3_6" -> "3-6岁";       // 学龄前
            case "children_7_12" -> "7-12岁";     // 小学阶段
            case "children_13_17" -> "13-17岁";   // 中学阶段
            case "children_18_plus" -> "18岁以上"; // 成年子女
            case "no_children" -> "无孩子";
            default -> childKey;
        };
    }

    /**
     * 获取相邻子女年龄区间
     * 年龄连续性：0-2岁 ↔ 3-6岁 ↔ 7-12岁 ↔ 13-17岁 ↔ 18岁以上
     * 首尾区间只有一个相邻（0-2岁仅相邻 3-6岁，18岁以上仅相邻 13-17岁）
     * <p>
     * 子女年龄区间定义见 User.java 第 80-82 行的 childrenAgeRanges 字段，
     * AI prompt key 见 AiPromptConstants.java 第 148 行。
     * <p>
     * 儿童发展阶段的相邻依据：
     * - 0-2岁（婴幼儿）→ 3-6岁（学龄前）：自然成长过渡
     * - 3-6岁（学龄前）→ 7-12岁（小学）：进入正式教育阶段
     * - 7-12岁（小学）→ 13-17岁（中学）：青春期过渡
     * - 13-17岁（中学）→ 18岁以上（成年）：成年过渡
     */
    public static List<String> getAdjacentChildRanges(String childKey) {
        List<String> result = new ArrayList<>();
        switch (childKey.toLowerCase()) {
            case "children_0_2" -> {
                result.add("children_3_6");
            }                                     // 0-2岁 → 3-6岁
            case "children_3_6" -> {
                result.add("children_0_2");
                result.add("children_7_12");
            }        // 3-6岁 → 0-2岁/7-12岁
            case "children_7_12" -> {
                result.add("children_3_6");
                result.add("children_13_17");
            }      // 7-12岁 → 3-6岁/13-17岁
            case "children_13_17" -> {
                result.add("children_7_12");
                result.add("children_18_plus");
            }  // 13-17岁 → 7-12岁/18岁以上
            case "children_18_plus" -> {
                result.add("children_13_17");
            }                               // 18岁以上 → 13-17岁
        }
        return result;
    }

    /**
     * 年收入中文标签映射
     * User.java 第 100 行定义的 aspirationIncome 字段值，
     * AiPromptConstants.java 第 153 行定义了对应 AI prompt key。
     * PREFER_NOT_TO_SAY 返回空字符串（该维度在 addIncomeContributions 中被跳过）。
     */
    public static String getAnnualIncomeLabel(String annualIncome) {
        if (annualIncome == null) return "";
        return switch (annualIncome.toUpperCase()) {
            case "UNDER_50K" -> "年收入5万以内";           // 5万以下
            case "50K_150K" -> "年收入5~15万";            // 5~15万
            case "150K_300K" -> "年收入15~30万";          // 15~30万
            case "300K_500K" -> "年收入30~50万";          // 30~50万
            case "500K_1M" -> "年收入50~100万";           // 50~100万
            case "OVER_1M" -> "年收入100万+";             // 100万以上
            case "PREFER_NOT_TO_SAY" -> "";              // 不愿透露（跳过此维度）
            default -> annualIncome;
        };
    }
}
