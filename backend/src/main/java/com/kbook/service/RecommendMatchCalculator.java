package com.kbook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.dto.BookProjection;
import com.kbook.dto.MatchScoreDetailVO;
import com.kbook.entity.User;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 推荐匹配度计算器
 * <p>
 * 根据用户画像（年龄、性别、MBTI、职业、学历、收入、心情等）
 * 与书籍的相关度得分（relevanceScores）计算匹配度。
 * <p>
 * 核心算法：对每个画像维度，从书籍的 relevanceScores 中取对应维度的原始得分，
 * 通过 Z-Score 标准化后加权求和，再乘以覆盖率因子得到最终匹配分。
 * 支持邻近维度衰减（如相邻年龄组、相邻MBTI类型等）。
 */
@Slf4j
public class RecommendMatchCalculator {

    /**
     * 计算用户与书籍的匹配度得分
     * @param user 用户实体
     * @param book 书籍实体
     * @param coefficientService 系数服务
     * @param objectMapper JSON 解析器
     * @param statsService 维度统计服务
     * @return 匹配度得分（0~1+），0 表示无匹配
     */
    public static double calculateMatchScore(User user, BookProjection book,
                                              RecommendCoefficientService coefficientService,
                                              ObjectMapper objectMapper,
                                              DimensionStatsService statsService) {
        if (book.getRelevanceScores() == null || book.getRelevanceScores().isBlank()) {
            return 0.0;
        }

        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }

        double ageWeight = coefficientService.getCoefficient("MATCH", "age_weight", 1.5);
        double mbtiWeight = coefficientService.getCoefficient("MATCH", "mbti_weight", 1.3);
        double adjacentDecay = coefficientService.getCoefficient("MATCH", "adjacent_decay", 0.40);

        try {
            JsonNode scores = objectMapper.readTree(book.getRelevanceScores());
            double totalDeviation = 0;
            double totalWeight = 0;
            int matchedDimensions = 0;

            StringBuilder logDetail = new StringBuilder();
            logDetail.append(String.format("book=%s(%d) | ", book.getTitle(), book.getId()));

            if (user.getBirthday() != null) {
                int age = java.time.Period.between(user.getBirthday(), java.time.LocalDate.now()).getYears();
                String ageGroup = getAgeGroup(age);
                if (scores.has(ageGroup)) {
                    double dev = getDeviation(statsService, ageGroup, scores);
                    totalDeviation += dev * ageWeight;
                    totalWeight += ageWeight;
                    logDetail.append(String.format("年龄[%s]: dev=%.4f*%.2f=%.4f", ageGroup, dev, ageWeight, dev * ageWeight));

                    String prevGroup = getAdjacentAgeGroup(age, -1);
                    String nextGroup = getAdjacentAgeGroup(age, 1);
                    if (prevGroup != null && !prevGroup.equals(ageGroup)) {
                        double adjDev = getDeviation(statsService, prevGroup, scores);
                        totalDeviation += adjDev * ageWeight * adjacentDecay;
                        totalWeight += ageWeight * adjacentDecay;
                        logDetail.append(String.format(" + 邻近年龄[%s]: dev=%.4f*%.2f*%.2f=%.4f", prevGroup, adjDev, ageWeight, adjacentDecay, adjDev * ageWeight * adjacentDecay));
                    }
                    if (nextGroup != null && !nextGroup.equals(ageGroup)) {
                        double adjDev = getDeviation(statsService, nextGroup, scores);
                        totalDeviation += adjDev * ageWeight * adjacentDecay;
                        totalWeight += ageWeight * adjacentDecay;
                        logDetail.append(String.format(" + 邻近年龄[%s]: dev=%.4f*%.2f*%.2f=%.4f", nextGroup, adjDev, ageWeight, adjacentDecay, adjDev * ageWeight * adjacentDecay));
                    }
                    matchedDimensions++;
                }
                logDetail.append(" | ");
            }

            if (user.getGender() != null) {
                String genderKey = "MALE".equals(user.getGender()) ? "male" : "female";
                if (scores.has(genderKey)) {
                    double dev = getDeviation(statsService, genderKey, scores);
                    totalDeviation += dev * 1.0;
                    totalWeight += 1.0;
                    logDetail.append(String.format("性别[%s]: dev=%.4f*1.0=%.4f", genderKey, dev, dev * 1.0));

                    String oppositeKey = "MALE".equals(user.getGender()) ? "female" : "male";
                    double oppDev = getDeviation(statsService, oppositeKey, scores);
                    if (oppDev > 0) {
                        totalDeviation -= oppDev * 0.5;
                        logDetail.append(String.format(" - 相反[%s]: dev=%.4f*0.5=%.4f", oppositeKey, oppDev, oppDev * 0.5));
                    }
                    matchedDimensions++;
                }
                logDetail.append(" | ");
            }

            if (user.getMarried() != null) {
                String marryKey = user.getMarried() ? "married" : "unmarried";
                if (scores.has(marryKey)) {
                    double dev = getDeviation(statsService, marryKey, scores);
                    totalDeviation += dev * 1.0;
                    totalWeight += 1.0;
                    logDetail.append(String.format("婚姻[%s]: dev=%.4f*1.0=%.4f", marryKey, dev, dev * 1.0));

                    String oppositeKey = user.getMarried() ? "unmarried" : "married";
                    double oppDev = getDeviation(statsService, oppositeKey, scores);
                    if (oppDev > 0) {
                        totalDeviation -= oppDev * 0.5;
                        logDetail.append(String.format(" - 相反[%s]: dev=%.4f*0.5=%.4f", oppositeKey, oppDev, oppDev * 0.5));
                    }
                    matchedDimensions++;
                }
                logDetail.append(" | ");
            }

            // 孩子年龄区间：优先使用新字段 childrenAgeRanges，兜底旧字段 hasChildren
            if (user.getChildrenAgeRanges() != null && !user.getChildrenAgeRanges().isBlank()) {
                String[] ranges = user.getChildrenAgeRanges().split(",");
                double childWeight = 1.0;
                double childDecay = 0.4;
                boolean hasChildData = false;
                logDetail.append("子女");
                for (String range : ranges) {
                    String childKey = range.trim().toLowerCase();
                    if (childKey.isEmpty()) continue;
                    if (!scores.has(childKey)) continue;
                    hasChildData = true;
                    double dev = getDeviation(statsService, childKey, scores);
                    totalDeviation += dev * childWeight;
                    totalWeight += childWeight;
                    logDetail.append(String.format("[%s]: dev=%.4f*%.2f=%.4f", childKey, dev, childWeight, dev * childWeight));
                    // 邻近区间衰减
                    List<String> adjacentRanges = getAdjacentChildRanges(childKey);
                    for (String adj : adjacentRanges) {
                        double adjDev = getDeviation(statsService, adj, scores);
                        totalDeviation += adjDev * childWeight * childDecay;
                        totalWeight += childWeight * childDecay;
                        logDetail.append(String.format(" + 邻近子女[%s]: dev=%.4f*%.2f*%.2f=%.4f", adj, adjDev, childWeight, childDecay, adjDev * childWeight * childDecay));
                    }
                }
                if (hasChildData) matchedDimensions++;
                logDetail.append(" | ");
            } else if (user.getHasChildren() != null) {
                String childKey = user.getHasChildren() ? "hasChildren" : "noChildren";
                if (scores.has(childKey)) {
                    double dev = getDeviation(statsService, childKey, scores);
                    totalDeviation += dev * 1.0;
                    totalWeight += 1.0;
                    logDetail.append(String.format("子女(旧)[%s]: dev=%.4f*1.0=%.4f", childKey, dev, dev * 1.0));
                    String oppositeKey = user.getHasChildren() ? "noChildren" : "hasChildren";
                    double oppDev = getDeviation(statsService, oppositeKey, scores);
                    if (oppDev > 0) {
                        totalDeviation -= oppDev * 0.5;
                        logDetail.append(String.format(" - 相反[%s]: dev=%.4f*0.5=%.4f", oppositeKey, oppDev, oppDev * 0.5));
                    }
                    matchedDimensions++;
                }
                logDetail.append(" | ");
            }

            if (user.getMbti() != null) {
                String mbtiKey = user.getMbti().toUpperCase();
                if (scores.has(mbtiKey)) {
                    double dev = getDeviation(statsService, mbtiKey, scores);
                    totalDeviation += dev * mbtiWeight;
                    totalWeight += mbtiWeight;
                    logDetail.append(String.format("MBTI[%s]: dev=%.4f*%.2f=%.4f", mbtiKey, dev, mbtiWeight, dev * mbtiWeight));
//
//                    List<String> adjacentMbti = getAdjacentMbti(mbtiKey);
//                    for (String adj : adjacentMbti) {
//                        double adjDev = getDeviation(statsService, adj, scores);
//                        totalDeviation += adjDev * mbtiWeight * adjacentDecay;
//                        totalWeight += mbtiWeight * adjacentDecay;
//                        logDetail.append(String.format(" + 邻近MBTI[%s]: dev=%.4f*%.2f*%.2f=%.4f", adj, adjDev, mbtiWeight, adjacentDecay, adjDev * mbtiWeight * adjacentDecay));
//                    }
                    matchedDimensions++;
                }
                logDetail.append(" | ");
            }

            if (user.getOccupation() != null && !user.getOccupation().isBlank()) {
                String[] userOccList = user.getOccupation().split(",");
                double occWeight = coefficientService.getCoefficient("MATCH", "occupation_weight", 1.0);
                double occDecay = coefficientService.getCoefficient("MATCH", "occupation_decay", 0.40);
                boolean hasOccData = false;
                logDetail.append("职业");
                for (String userOcc : userOccList) {
                    String occKey = userOcc.trim().toLowerCase();
                    if (occKey.isEmpty()) continue;
                    if (!scores.has(occKey)) continue;
                    hasOccData = true;
                    double dev = getDeviation(statsService, occKey, scores);
                    totalDeviation += dev * occWeight;
                    totalWeight += occWeight;
                    logDetail.append(String.format("[%s]: dev=%.4f*%.2f=%.4f", occKey, dev, occWeight, dev * occWeight));

                    List<String> adjacentOcc = getAdjacentOccupations(occKey);
                    for (String adj : adjacentOcc) {
                        double adjDev = getDeviation(statsService, adj, scores);
                        totalDeviation += adjDev * occWeight * occDecay;
                        totalWeight += occWeight * occDecay;
                        logDetail.append(String.format(" + 邻近职业[%s]: dev=%.4f*%.2f*%.2f=%.4f", adj, adjDev, occWeight, occDecay, adjDev * occWeight * occDecay));
                    }
                }
                if (hasOccData) matchedDimensions++;
                logDetail.append(" | ");
            }

            if (user.getAspirationEducation() != null) {
                String eduKey = user.getAspirationEducation().toLowerCase();
                double eduWeight = coefficientService.getCoefficient("MATCH", "education_weight", 0.8);
                double eduDecay = coefficientService.getCoefficient("MATCH", "education_decay", 0.40);
                if (scores.has(eduKey)) {
                    double dev = getDeviation(statsService, eduKey, scores);
                    totalDeviation += dev * eduWeight;
                    totalWeight += eduWeight;
                    logDetail.append(String.format("学历[%s]: dev=%.4f*%.2f=%.4f", eduKey, dev, eduWeight, dev * eduWeight));

                    List<String> adjacentEdu = getAdjacentEducations(eduKey);
                    for (String adj : adjacentEdu) {
                        double adjDev = getDeviation(statsService, adj, scores);
                        totalDeviation += adjDev * eduWeight * eduDecay;
                        totalWeight += eduWeight * eduDecay;
                        logDetail.append(String.format(" + 邻近学历[%s]: dev=%.4f*%.2f*%.2f=%.4f", adj, adjDev, eduWeight, eduDecay, adjDev * eduWeight * eduDecay));
                    }
                    matchedDimensions++;
                }
                logDetail.append(" | ");
            }

            if (user.getEntrepreneurship() != null && !user.getEntrepreneurship().isBlank()) {
                String entreKey = user.getEntrepreneurship().toLowerCase();
                double entreWeight = coefficientService.getCoefficient("MATCH", "entrepreneurship_weight", 0.6);
                if (scores.has(entreKey)) {
                    double dev = getDeviation(statsService, entreKey, scores);
                    totalDeviation += dev * entreWeight;
                    totalWeight += entreWeight;
                    logDetail.append(String.format("创业[%s]: dev=%.4f*%.2f=%.4f", entreKey, dev, entreWeight, dev * entreWeight));
                    matchedDimensions++;
                }
                logDetail.append(" | ");
            }

            if (user.getAspirationIncome() != null && !user.getAspirationIncome().isBlank()
                    && !"PREFER_NOT_TO_SAY".equalsIgnoreCase(user.getAspirationIncome())) {
                String incomeKey = user.getAspirationIncome().toLowerCase();
                double incomeWeight = coefficientService.getCoefficient("MATCH", "income_weight", 0.5);
                double incomeDecay = coefficientService.getCoefficient("MATCH", "income_decay", 0.40);
                if (scores.has(incomeKey)) {
                    double dev = getDeviation(statsService, incomeKey, scores);
                    totalDeviation += dev * incomeWeight;
                    totalWeight += incomeWeight;
                    logDetail.append(String.format("收入[%s]: dev=%.4f*%.2f=%.4f", incomeKey, dev, incomeWeight, dev * incomeWeight));

                    List<String> adjacentIncome = getAdjacentIncomes(incomeKey);
                    for (String adj : adjacentIncome) {
                        double adjDev = getDeviation(statsService, adj, scores);
                        totalDeviation += adjDev * incomeWeight * incomeDecay;
                        totalWeight += incomeWeight * incomeDecay;
                        logDetail.append(String.format(" + 邻近收入[%s]: dev=%.4f*%.2f*%.2f=%.4f", adj, adjDev, incomeWeight, incomeDecay, adjDev * incomeWeight * incomeDecay));
                    }
                    matchedDimensions++;
                }
                logDetail.append(" | ");
            }

            // 阅读意图+心情（支持新格式 "INTENT|MOOD" 和旧格式纯 MOOD）
            if (user.getMood() != null && !user.getMood().isBlank()) {
                String moodRaw = user.getMood();
                String intentKey = null;
                String moodKey;
                int pipeIdx = moodRaw.indexOf('|');
                if (pipeIdx > 0) {
                    intentKey = moodRaw.substring(0, pipeIdx).toLowerCase().trim();
                    moodKey = moodRaw.substring(pipeIdx + 1).toLowerCase().trim();
                } else {
                    moodKey = moodRaw.toLowerCase().trim();
                }

                // 意图维度（权重 0.8）
                if (intentKey != null && !intentKey.isEmpty()) {
                    double intentWeight = coefficientService.getCoefficient("MATCH", "intent_weight", 0.8);
                    double intentDecay = coefficientService.getCoefficient("MATCH", "intent_decay", 0.40);
                    if (scores.has(intentKey)) {
                        double dev = getDeviation(statsService, intentKey, scores);
                        totalDeviation += dev * intentWeight;
                        totalWeight += intentWeight;
                        logDetail.append(String.format("意图[%s]: dev=%.4f*%.2f=%.4f", intentKey, dev, intentWeight, dev * intentWeight));
                        List<String> relatedIntents = getRelatedIntents(intentKey);
                        for (String adj : relatedIntents) {
                            double adjDev = getDeviation(statsService, adj, scores);
                            totalDeviation += adjDev * intentWeight * intentDecay;
                            totalWeight += intentWeight * intentDecay;
                            logDetail.append(String.format(" + 相关意图[%s]: dev=%.4f*%.2f*%.2f=%.4f", adj, adjDev, intentWeight, intentDecay, adjDev * intentWeight * intentDecay));
                        }
                        matchedDimensions++;
                    }
                }

                // 情绪维度（权重 0.8）
                if (moodKey != null && !moodKey.isEmpty()) {
                    double moodWeight = coefficientService.getCoefficient("MATCH", "mood_weight", 0.8);
                    double moodDecay = coefficientService.getCoefficient("MATCH", "mood_decay", 0.40);
                    if (scores.has(moodKey)) {
                        double dev = getDeviation(statsService, moodKey, scores);
                        totalDeviation += dev * moodWeight;
                        totalWeight += moodWeight;
                        logDetail.append(String.format("心情[%s]: dev=%.4f*%.2f=%.4f", moodKey, dev, moodWeight, dev * moodWeight));
                        List<String> relatedMoods = getRelatedMoods(moodKey);
                        for (String adj : relatedMoods) {
                            double adjDev = getDeviation(statsService, adj, scores);
                            totalDeviation += adjDev * moodWeight * moodDecay;
                            totalWeight += moodWeight * moodDecay;
                            logDetail.append(String.format(" + 相关心情[%s]: dev=%.4f*%.2f*%.2f=%.4f", adj, adjDev, moodWeight, moodDecay, adjDev * moodWeight * moodDecay));
                        }
                        matchedDimensions++;
                    }
                }
                logDetail.append(" | ");
            }

            if (totalWeight == 0) return 0.0;

            double avgDeviation = totalDeviation / totalWeight;

            double coverageFactor = switch (matchedDimensions) {
                case 10 -> 1.0;
                case 9 -> 0.98;
                case 8 -> 0.96;
                case 7 -> 0.94;
                case 6 -> 0.91;
                case 5 -> 0.88;
                case 4 -> 0.84;
                case 3 -> 0.85;
                case 2 -> 0.75;
                case 1 -> 0.65;
                default -> 0.50;
            };

            double finalScore = normalizeScore(avgDeviation * coverageFactor);
            logDetail.append(String.format("汇总: totalDev=%.4f, totalWeight=%.4f, avgDev=%.4f, coverage[%d维]=%.2f, final=%.4f",
                    totalDeviation, totalWeight, avgDeviation, matchedDimensions, coverageFactor, finalScore));
//            log.debug("匹配度计算: {}", logDetail);

            return finalScore;
        } catch (Exception e) {
            log.debug("解析相关度得分失败: bookId={} - {}", book.getId(), e.getMessage());
            return 0.0;
        }
    }

    public static MatchScoreDetailVO calculateMatchScoreDetail(User user, BookProjection book,
                                                                RecommendCoefficientService coefficientService,
                                                                ObjectMapper objectMapper,
                                                                DimensionStatsService statsService) {
        double overallScore = calculateMatchScore(user, book, coefficientService, objectMapper, statsService);

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
            int matchedDimensions = 0;
            List<MatchScoreDetailVO.DimensionScore> dimensions = new ArrayList<>();

            if (user.getBirthday() != null) {
                int age = java.time.Period.between(user.getBirthday(), java.time.LocalDate.now()).getYears();
                String ageGroup = getAgeGroup(age);
                double dev = getDeviation(statsService, ageGroup, scores);
                double w = coefficientService.getCoefficient("MATCH", "age_weight", 1.5);
                double normalizedDev = normalizeScore(dev);
                matchedDimensions++;
                dimensions.add(MatchScoreDetailVO.DimensionScore.builder()
                        .dimension("age").label("年龄: " + ageGroup)
                        .score(normalizedDev)
                        .weight(w).weightedScore(Math.round(normalizedDev * w * 10000.0) / 10000.0)
                        .build());
            }

            if (user.getGender() != null) {
                String genderKey = "MALE".equals(user.getGender()) ? "male" : "female";
                double dev = getDeviation(statsService, genderKey, scores);
                double normalizedDev = normalizeScore(dev);
                matchedDimensions++;
                dimensions.add(MatchScoreDetailVO.DimensionScore.builder()
                        .dimension("gender").label("性别: " + ("MALE".equals(user.getGender()) ? "男" : "女"))
                        .score(normalizedDev)
                        .weight(1.0).weightedScore(normalizedDev)
                        .build());
            }

            if (user.getMarried() != null) {
                String marryKey = user.getMarried() ? "married" : "unmarried";
                double dev = getDeviation(statsService, marryKey, scores);
                double normalizedDev = normalizeScore(dev);
                matchedDimensions++;
                dimensions.add(MatchScoreDetailVO.DimensionScore.builder()
                        .dimension("married").label(user.getMarried() ? "已婚" : "未婚")
                        .score(normalizedDev)
                        .weight(1.0).weightedScore(normalizedDev)
                        .build());
            }

            // 孩子年龄区间：优先新字段，兜底旧字段
            if (user.getChildrenAgeRanges() != null && !user.getChildrenAgeRanges().isBlank()) {
                String[] ranges = user.getChildrenAgeRanges().split(",");
                double childWeight = 1.0;
                for (String range : ranges) {
                    String childKey = range.trim().toLowerCase();
                    if (childKey.isEmpty()) continue;
                    double dev = getDeviation(statsService, childKey, scores);
                    double normalizedDev = normalizeScore(dev);
                    dimensions.add(MatchScoreDetailVO.DimensionScore.builder()
                            .dimension("children").label("子女: " + getChildRangeLabel(childKey))
                            .score(normalizedDev)
                            .weight(childWeight).weightedScore(Math.round(normalizedDev * childWeight * 10000.0) / 10000.0)
                            .build());
                }
                matchedDimensions++;
            } else if (user.getHasChildren() != null) {
                String childKey = user.getHasChildren() ? "hasChildren" : "noChildren";
                double dev = getDeviation(statsService, childKey, scores);
                double normalizedDev = normalizeScore(dev);
                matchedDimensions++;
                dimensions.add(MatchScoreDetailVO.DimensionScore.builder()
                        .dimension("hasChildren").label(user.getHasChildren() ? "有孩子" : "无孩子")
                        .score(normalizedDev)
                        .weight(1.0).weightedScore(normalizedDev)
                        .build());
            }

            if (user.getMbti() != null) {
                String mbtiKey = user.getMbti().toUpperCase();
                double dev = getDeviation(statsService, mbtiKey, scores);
                double w = coefficientService.getCoefficient("MATCH", "mbti_weight", 1.3);
                double normalizedDev = normalizeScore(dev);
                matchedDimensions++;
                dimensions.add(MatchScoreDetailVO.DimensionScore.builder()
                        .dimension("mbti").label("MBTI: " + mbtiKey)
                        .score(normalizedDev)
                        .weight(w).weightedScore(Math.round(normalizedDev * w * 10000.0) / 10000.0)
                        .build());
            }

            if (user.getOccupation() != null && !user.getOccupation().isBlank()) {
                String[] userOccList = user.getOccupation().split(",");
                double w = coefficientService.getCoefficient("MATCH", "occupation_weight", 1.0);
                for (String userOcc : userOccList) {
                    String occKey = userOcc.trim().toLowerCase();
                    if (occKey.isEmpty()) continue;
                    double dev = getDeviation(statsService, occKey, scores);
                    double normalizedDev = normalizeScore(dev);
                    matchedDimensions++;
                    dimensions.add(MatchScoreDetailVO.DimensionScore.builder()
                            .dimension("occupation").label("职业: " + getOccupationLabel(occKey))
                            .score(normalizedDev)
                            .weight(w).weightedScore(Math.round(normalizedDev * w * 10000.0) / 10000.0)
                            .build());
                }
            }

            if (user.getAspirationEducation() != null) {
                String eduKey = user.getAspirationEducation().toLowerCase();
                double w = coefficientService.getCoefficient("MATCH", "education_weight", 0.8);
                double dev = getDeviation(statsService, eduKey, scores);
                double normalizedDev = normalizeScore(dev);
                matchedDimensions++;
                dimensions.add(MatchScoreDetailVO.DimensionScore.builder()
                        .dimension("education").label("学历: " + getEducationLabel(user.getAspirationEducation()))
                        .score(normalizedDev)
                        .weight(w).weightedScore(Math.round(normalizedDev * w * 10000.0) / 10000.0)
                        .build());
            }

            if (user.getEntrepreneurship() != null && !user.getEntrepreneurship().isBlank()) {
                String entreKey = user.getEntrepreneurship().toLowerCase();
                double w = coefficientService.getCoefficient("MATCH", "entrepreneurship_weight", 0.6);
                double dev = getDeviation(statsService, entreKey, scores);
                double normalizedDev = normalizeScore(dev);
                matchedDimensions++;
                dimensions.add(MatchScoreDetailVO.DimensionScore.builder()
                        .dimension("entrepreneurship").label(getEntrepreneurshipLabel(user.getEntrepreneurship()))
                        .score(normalizedDev)
                        .weight(w).weightedScore(Math.round(normalizedDev * w * 10000.0) / 10000.0)
                        .build());
            }

            if (user.getAspirationIncome() != null && !user.getAspirationIncome().isBlank()
                    && !"PREFER_NOT_TO_SAY".equalsIgnoreCase(user.getAspirationIncome())) {
                String incomeKey = user.getAspirationIncome().toLowerCase();
                double w = coefficientService.getCoefficient("MATCH", "income_weight", 0.5);
                double dev = getDeviation(statsService, incomeKey, scores);
                double normalizedDev = normalizeScore(dev);
                matchedDimensions++;
                dimensions.add(MatchScoreDetailVO.DimensionScore.builder()
                        .dimension("income").label(getAnnualIncomeLabel(user.getAspirationIncome()))
                        .score(normalizedDev)
                        .weight(w).weightedScore(Math.round(normalizedDev * w * 10000.0) / 10000.0)
                        .build());
            }

            // 阅读意图+心情（支持新格式 "INTENT|MOOD" 和旧格式纯 MOOD）
            if (user.getMood() != null && !user.getMood().isBlank()) {
                String moodRaw = user.getMood();
                String intentKey = null;
                String moodKey;
                int pipeIdx = moodRaw.indexOf('|');
                if (pipeIdx > 0) {
                    intentKey = moodRaw.substring(0, pipeIdx).toLowerCase().trim();
                    moodKey = moodRaw.substring(pipeIdx + 1).toLowerCase().trim();
                } else {
                    moodKey = moodRaw.toLowerCase().trim();
                }

                if (intentKey != null && !intentKey.isEmpty()) {
                    double w = coefficientService.getCoefficient("MATCH", "intent_weight", 0.5);
                    double dev = getDeviation(statsService, intentKey, scores);
                    double normalizedDev = normalizeScore(dev);
                    matchedDimensions++;
                    dimensions.add(MatchScoreDetailVO.DimensionScore.builder()
                            .dimension("intent").label("意图: " + getIntentLabel(intentKey))
                            .score(normalizedDev)
                            .weight(w).weightedScore(Math.round(normalizedDev * w * 10000.0) / 10000.0)
                            .build());
                }

                if (moodKey != null && !moodKey.isEmpty()) {
                    double w = coefficientService.getCoefficient("MATCH", "mood_weight", 0.3);
                    double dev = getDeviation(statsService, moodKey, scores);
                    double normalizedDev = normalizeScore(dev);
                    matchedDimensions++;
                    dimensions.add(MatchScoreDetailVO.DimensionScore.builder()
                            .dimension("mood").label("心情: " + getMoodLabel(moodKey))
                            .score(normalizedDev)
                            .weight(w).weightedScore(Math.round(normalizedDev * w * 10000.0) / 10000.0)
                            .build());
                }
            }

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
            return MatchScoreDetailVO.builder()
                    .bookId(book.getId())
                    .overallScore(overallScore)
                    .matchedDimensions(0)
                    .coverageFactor(0.35)
                    .dimensions(List.of())
                    .build();
        }
    }

    static double getCoverageFactor(int matchedDimensions) {
        return switch (matchedDimensions) {
            case 10 -> 1.0;
            case 9 -> 0.98;
            case 8 -> 0.96;
            case 7 -> 0.94;
            case 6 -> 0.91;
            case 5 -> 0.88;
            case 4 -> 0.84;
            case 3 -> 0.85;
            case 2 -> 0.75;
            case 1 -> 0.65;
            default -> 0.50;
        };
    }

    static double normalizeScore(double rawScore) {
        if (rawScore <= 0) return 0.0;
        double normalized = 1.0 / (1.0 + Math.exp(-4.0 * (rawScore - 0.5)));
        return Math.round(normalized * 10000.0) / 10000.0;
    }

    /**
     * 获取指定维度的偏差值（Z-Score 或简单偏差）
     * @param statsService 维度统计服务（可为null）
     * @param key 维度键名
     * @param scores 书籍的 relevanceScores JSON
     * @return 偏差值
     */
    private static double getDeviation(DimensionStatsService statsService, String key, JsonNode scores) {
        if (!scores.has(key)) return 0.0;
        double rawScore = scores.get(key).asDouble();
        if (statsService != null) {
            return statsService.getZScore(key, rawScore);
        }
        return rawScore - 0.5;
    }

    /** 将年龄转换为年龄组标签 */
    static String getAgeGroup(int age) {
        if (age < 10) return "0-9";
        if (age < 20) return "10-19";
        if (age < 30) return "20-29";
        if (age < 40) return "30-39";
        if (age < 50) return "40-49";
        if (age < 60) return "50-59";
        return "60+";
    }

    /** 获取相邻年龄组标签（direction: -1=前一组, 1=后一组） */
    static String getAdjacentAgeGroup(int age, int direction) {
        int[] boundaries = {0, 10, 20, 30, 40, 50, 60, Integer.MAX_VALUE};
        int currentIdx = -1;
        for (int i = 0; i < boundaries.length - 1; i++) {
            if (age >= boundaries[i] && age < boundaries[i + 1]) {
                currentIdx = i;
                break;
            }
        }
        if (currentIdx < 0) return null;
        int adjacentIdx = currentIdx + direction;
        if (adjacentIdx < 0 || adjacentIdx >= boundaries.length - 1) return null;
        return getAgeGroup(boundaries[adjacentIdx]);
    }

    /** 获取 MBTI 的邻近类型（翻转每个字母得到4个邻近类型） */
    static List<String> getAdjacentMbti(String mbti) {
        if (mbti == null || mbti.length() != 4) return List.of();
        List<String> adjacent = new java.util.ArrayList<>();
        char[] chars = mbti.toCharArray();
        char[][] flips = {
                {chars[0], chars[0] == 'I' ? 'E' : 'I'},
                {chars[1], chars[1] == 'N' ? 'S' : 'N'},
                {chars[2], chars[2] == 'T' ? 'F' : 'T'},
                {chars[3], chars[3] == 'J' ? 'P' : 'J'}
        };
        for (int i = 0; i < 4; i++) {
            char[] copy = chars.clone();
            copy[i] = flips[i][1];
            adjacent.add(new String(copy));
        }
        return adjacent;
    }

    /** 获取职业的邻近职业映射 */
    static List<String> getAdjacentOccupations(String occupation) {
        return switch (occupation.toLowerCase()) {
            case "student" -> List.of("education");
            case "tech" -> List.of("education", "freelance");
            case "finance" -> List.of("management");
            case "education" -> List.of("student", "tech");
            case "medical" -> List.of("education");
            case "arts" -> List.of("freelance", "education");
            case "management" -> List.of("finance");
            case "freelance" -> List.of("arts", "tech");
            case "retired" -> List.of();
            case "other" -> List.of();
            default -> List.of();
        };
    }

    /** 获取学历的邻近学历映射 */
    static List<String> getAdjacentEducations(String education) {
        return switch (education.toLowerCase()) {
            case "high_school" -> List.of("college");
            case "college" -> List.of("high_school", "bachelor");
            case "bachelor" -> List.of("college", "master");
            case "master" -> List.of("bachelor", "doctorate");
            case "doctorate" -> List.of("master");
            case "other" -> List.of();
            default -> List.of();
        };
    }

    /** 获取收入区间的邻近收入映射 */
    static List<String> getAdjacentIncomes(String income) {
        return switch (income.toLowerCase()) {
            case "under_50k" -> List.of("50k_150k");
            case "50k_150k" -> List.of("under_50k", "150k_300k");
            case "150k_300k" -> List.of("50k_150k", "300k_500k");
            case "300k_500k" -> List.of("150k_300k", "500k_1m");
            case "500k_1m" -> List.of("300k_500k", "over_1m");
            case "over_1m" -> List.of("500k_1m");
            default -> List.of();
        };
    }

    /** 获取心情的相关心情映射 */
    static List<String> getRelatedMoods(String mood) {
        return switch (mood.toLowerCase()) {
            case "happy" -> List.of("calm");
            case "calm" -> List.of("happy");
            case "anxious" -> List.of("sad", "tired", "frustrated");
            case "sad" -> List.of("anxious", "tired", "frustrated");
            case "tired" -> List.of("sad", "anxious");
            case "frustrated" -> List.of("anxious", "sad");
            default -> List.of();
        };
    }

    /** 获取阅读意图的邻近意图（用于衰减） */
    public static List<String> getRelatedIntents(String intent) {
        return switch (intent.toLowerCase()) {
            case "growth" -> List.of("insight");
            case "insight" -> List.of("growth", "comfort");
            case "comfort" -> List.of("escape", "insight");
            case "escape" -> List.of("comfort", "excite");
            case "excite" -> List.of("escape");
            default -> List.of();
        };
    }

    /** 获取阅读意图的中文标签 */
    public static String getIntentLabel(String intent) {
        if (intent == null) return "";
        return switch (intent.toLowerCase()) {
            case "growth" -> "充电成长";
            case "comfort" -> "共鸣陪伴";
            case "escape" -> "逃离放松";
            case "excite" -> "新鲜刺激";
            case "insight" -> "答案解惑";
            default -> intent;
        };
    }

    /** 获取职业的中文标签 */
    public static String getOccupationLabel(String occupation) {
        if (occupation == null) return "";
        return switch (occupation.toUpperCase()) {
            case "STUDENT" -> "学生";
            case "TECH" -> "技术/IT";
            case "FINANCE" -> "金融/商业";
            case "EDUCATION" -> "教育/科研";
            case "MEDICAL" -> "医疗/健康";
            case "ARTS" -> "文艺/传媒";
            case "MANAGEMENT" -> "管理/行政";
            case "FREELANCE" -> "自由职业";
            case "RETIRED" -> "退休";
            case "OTHER" -> "其他";
            default -> occupation;
        };
    }

    /** 获取学历的中文标签 */
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

    /** 获取心情的中文标签 */
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

    /** 获取创业意向的中文标签 */
    public static String getEntrepreneurshipLabel(String entrepreneurship) {
        if (entrepreneurship == null) return "";
        return switch (entrepreneurship.toUpperCase()) {
            case "ENTREPRENEUR_OR_WANT" -> "正在创业/想创业";
            case "NOT_INTERESTED" -> "暂不考虑";
            default -> entrepreneurship;
        };
    }

    /** 获取孩子年龄区间的中文标签 */
    public static String getChildRangeLabel(String childKey) {
        if (childKey == null) return "";
        return switch (childKey.toLowerCase()) {
            case "0_2" -> "0-2岁";
            case "3_6" -> "3-6岁";
            case "7_12" -> "7-12岁";
            case "13_17" -> "13-17岁";
            case "18_plus" -> "18岁以上";
            case "no_children" -> "无孩子";
            default -> childKey;
        };
    }

    /** 获取孩子年龄区间的邻近区间（用于衰减） */
    public static List<String> getAdjacentChildRanges(String childKey) {
        List<String> result = new ArrayList<>();
        switch (childKey.toLowerCase()) {
            case "0_2" -> { result.add("3_6"); }
            case "3_6" -> { result.add("0_2"); result.add("7_12"); }
            case "7_12" -> { result.add("3_6"); result.add("13_17"); }
            case "13_17" -> { result.add("7_12"); result.add("18_plus"); }
            case "18_plus" -> { result.add("13_17"); }
        }
        return result;
    }

    /** 获取年收入区间的中文标签 */
    public static String getAnnualIncomeLabel(String annualIncome) {
        if (annualIncome == null) return "";
        return switch (annualIncome.toUpperCase()) {
            case "UNDER_50K" -> "年收入5万以内";
            case "50K_150K" -> "年收入5~15万";
            case "150K_300K" -> "年收入15~30万";
            case "300K_500K" -> "年收入30~50万";
            case "500K_1M" -> "年收入50~100万";
            case "OVER_1M" -> "年收入100万+";
            case "PREFER_NOT_TO_SAY" -> "";
            default -> annualIncome;
        };
    }
}
