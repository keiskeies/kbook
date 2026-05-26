package com.kbook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.entity.Book;
import com.kbook.entity.User;
import lombok.extern.slf4j.Slf4j;

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
    public static double calculateMatchScore(User user, Book book,
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
                logDetail.append(" | ");
            }

            if (user.getGender() != null) {
                String genderKey = "MALE".equals(user.getGender()) ? "male" : "female";
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
                logDetail.append(" | ");
            }

            if (user.getMarried() != null) {
                String marryKey = user.getMarried() ? "married" : "unmarried";
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
                logDetail.append(" | ");
            }

            if (user.getHasChildren() != null) {
                String childKey = user.getHasChildren() ? "hasChildren" : "noChildren";
                double dev = getDeviation(statsService, childKey, scores);
                totalDeviation += dev * 1.0;
                totalWeight += 1.0;
                logDetail.append(String.format("子女[%s]: dev=%.4f*1.0=%.4f", childKey, dev, dev * 1.0));

                String oppositeKey = user.getHasChildren() ? "noChildren" : "hasChildren";
                double oppDev = getDeviation(statsService, oppositeKey, scores);
                if (oppDev > 0) {
                    totalDeviation -= oppDev * 0.5;
                    logDetail.append(String.format(" - 相反[%s]: dev=%.4f*0.5=%.4f", oppositeKey, oppDev, oppDev * 0.5));
                }
                matchedDimensions++;
                logDetail.append(" | ");
            }

            if (user.getMbti() != null) {
                String mbtiKey = user.getMbti().toUpperCase();
                double dev = getDeviation(statsService, mbtiKey, scores);
                totalDeviation += dev * mbtiWeight;
                totalWeight += mbtiWeight;
                logDetail.append(String.format("MBTI[%s]: dev=%.4f*%.2f=%.4f", mbtiKey, dev, mbtiWeight, dev * mbtiWeight));

                List<String> adjacentMbti = getAdjacentMbti(mbtiKey);
                for (String adj : adjacentMbti) {
                    double adjDev = getDeviation(statsService, adj, scores);
                    totalDeviation += adjDev * mbtiWeight * adjacentDecay;
                    totalWeight += mbtiWeight * adjacentDecay;
                    logDetail.append(String.format(" + 邻近MBTI[%s]: dev=%.4f*%.2f*%.2f=%.4f", adj, adjDev, mbtiWeight, adjacentDecay, adjDev * mbtiWeight * adjacentDecay));
                }
                matchedDimensions++;
                logDetail.append(" | ");
            }

            if (user.getOccupation() != null && !user.getOccupation().isBlank()) {
                String[] userOccList = user.getOccupation().split(",");
                double occWeight = coefficientService.getCoefficient("MATCH", "occupation_weight", 1.0);
                double occDecay = coefficientService.getCoefficient("MATCH", "occupation_decay", 0.40);
                logDetail.append("职业");
                for (String userOcc : userOccList) {
                    String occKey = userOcc.trim().toLowerCase();
                    if (occKey.isEmpty()) continue;
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
                matchedDimensions++;
                logDetail.append(" | ");
            }

            if (user.getEducation() != null) {
                String eduKey = user.getEducation().toLowerCase();
                double eduWeight = coefficientService.getCoefficient("MATCH", "education_weight", 0.8);
                double eduDecay = coefficientService.getCoefficient("MATCH", "education_decay", 0.40);
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
                logDetail.append(" | ");
            }

            if (user.getEntrepreneurship() != null && !user.getEntrepreneurship().isBlank()) {
                String entreKey = user.getEntrepreneurship().toLowerCase();
                double entreWeight = coefficientService.getCoefficient("MATCH", "entrepreneurship_weight", 0.6);
                double dev = getDeviation(statsService, entreKey, scores);
                totalDeviation += dev * entreWeight;
                totalWeight += entreWeight;
                logDetail.append(String.format("创业[%s]: dev=%.4f*%.2f=%.4f", entreKey, dev, entreWeight, dev * entreWeight));
                matchedDimensions++;
                logDetail.append(" | ");
            }

            if (user.getAnnualIncome() != null && !user.getAnnualIncome().isBlank()
                    && !"PREFER_NOT_TO_SAY".equalsIgnoreCase(user.getAnnualIncome())) {
                String incomeKey = user.getAnnualIncome().toLowerCase();
                double incomeWeight = coefficientService.getCoefficient("MATCH", "income_weight", 0.5);
                double incomeDecay = coefficientService.getCoefficient("MATCH", "income_decay", 0.40);
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
                logDetail.append(" | ");
            }

            if (user.getMood() != null) {
                String moodKey = user.getMood().toLowerCase();
                double moodWeight = coefficientService.getCoefficient("MATCH", "mood_weight", 0.7);
                double moodDecay = coefficientService.getCoefficient("MATCH", "mood_decay", 0.40);
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
                logDetail.append(" | ");
            }

            if (totalWeight == 0) return 0.0;

            double avgDeviation = totalDeviation / totalWeight;

            double coverageFactor = switch (matchedDimensions) {
                case 10 -> 1.0;
                case 9 -> 0.98;
                case 8 -> 0.96;
                case 7 -> 0.93;
                case 6 -> 0.89;
                case 5 -> 0.84;
                case 4 -> 0.78;
                case 3 -> 0.70;
                case 2 -> 0.58;
                case 1 -> 0.42;
                default -> 0.35;
            };

            double finalScore = avgDeviation * coverageFactor;
            logDetail.append(String.format("汇总: totalDev=%.4f, totalWeight=%.4f, avgDev=%.4f, coverage[%d维]=%.2f, final=%.4f",
                    totalDeviation, totalWeight, avgDeviation, matchedDimensions, coverageFactor, finalScore));
            log.debug("匹配度计算: {}", logDetail);

            return finalScore;
        } catch (Exception e) {
            log.debug("解析相关度得分失败: bookId={} - {}", book.getId(), e.getMessage());
            return 0.0;
        }
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
            case "happy" -> List.of("motivated", "calm");
            case "calm", "motivated" -> List.of("happy", "curious");
            case "anxious" -> List.of("sad", "tired");
            case "sad" -> List.of("anxious", "tired");
            case "tired" -> List.of("sad", "anxious");
            case "curious" -> List.of("calm", "motivated");
            default -> List.of();
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
            case "MOTIVATED" -> "充满动力";
            case "TIRED" -> "疲惫";
            case "CURIOUS" -> "好奇";
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
