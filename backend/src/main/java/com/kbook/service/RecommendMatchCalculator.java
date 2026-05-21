package com.kbook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.entity.Book;
import com.kbook.entity.User;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class RecommendMatchCalculator {

    public static double calculateMatchScore(User user, Book book,
                                              RecommendCoefficientService coefficientService,
                                              ObjectMapper objectMapper) {
        return calculateMatchScore(user, book, coefficientService, objectMapper, null);
    }

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

            if (user.getBirthday() != null) {
                int age = java.time.Period.between(user.getBirthday(), java.time.LocalDate.now()).getYears();
                String ageGroup = getAgeGroup(age);
                double dev = getDeviation(statsService, ageGroup, scores);
                totalDeviation += dev * ageWeight;
                totalWeight += ageWeight;

                String prevGroup = getAdjacentAgeGroup(age, -1);
                String nextGroup = getAdjacentAgeGroup(age, 1);
                if (prevGroup != null && !prevGroup.equals(ageGroup)) {
                    double adjDev = getDeviation(statsService, prevGroup, scores);
                    totalDeviation += adjDev * ageWeight * adjacentDecay;
                    totalWeight += ageWeight * adjacentDecay;
                }
                if (nextGroup != null && !nextGroup.equals(ageGroup)) {
                    double adjDev = getDeviation(statsService, nextGroup, scores);
                    totalDeviation += adjDev * ageWeight * adjacentDecay;
                    totalWeight += ageWeight * adjacentDecay;
                }
                matchedDimensions++;
            }

            if (user.getGender() != null) {
                String genderKey = "MALE".equals(user.getGender()) ? "male" : "female";
                double dev = getDeviation(statsService, genderKey, scores);
                totalDeviation += dev * 1.0;
                totalWeight += 1.0;

                String oppositeKey = "MALE".equals(user.getGender()) ? "female" : "male";
                double oppDev = getDeviation(statsService, oppositeKey, scores);
                if (oppDev > 0) {
                    totalDeviation -= oppDev * 0.5;
                }
                matchedDimensions++;
            }

            if (user.getMarried() != null) {
                String marryKey = user.getMarried() ? "married" : "unmarried";
                double dev = getDeviation(statsService, marryKey, scores);
                totalDeviation += dev * 1.0;
                totalWeight += 1.0;

                String oppositeKey = user.getMarried() ? "unmarried" : "married";
                double oppDev = getDeviation(statsService, oppositeKey, scores);
                if (oppDev > 0) {
                    totalDeviation -= oppDev * 0.5;
                }
                matchedDimensions++;
            }

            if (user.getHasChildren() != null) {
                String childKey = user.getHasChildren() ? "hasChildren" : "noChildren";
                double dev = getDeviation(statsService, childKey, scores);
                totalDeviation += dev * 1.0;
                totalWeight += 1.0;

                String oppositeKey = user.getHasChildren() ? "noChildren" : "hasChildren";
                double oppDev = getDeviation(statsService, oppositeKey, scores);
                if (oppDev > 0) {
                    totalDeviation -= oppDev * 0.5;
                }
                matchedDimensions++;
            }

            if (user.getMbti() != null) {
                String mbtiKey = user.getMbti().toUpperCase();
                double dev = getDeviation(statsService, mbtiKey, scores);
                totalDeviation += dev * mbtiWeight;
                totalWeight += mbtiWeight;

                List<String> adjacentMbti = getAdjacentMbti(mbtiKey);
                for (String adj : adjacentMbti) {
                    double adjDev = getDeviation(statsService, adj, scores);
                    totalDeviation += adjDev * mbtiWeight * adjacentDecay;
                    totalWeight += mbtiWeight * adjacentDecay;
                }
                matchedDimensions++;
            }

            if (user.getOccupation() != null && !user.getOccupation().isBlank()) {
                String[] userOccList = user.getOccupation().split(",");
                double occWeight = coefficientService.getCoefficient("MATCH", "occupation_weight", 1.0);
                double occDecay = coefficientService.getCoefficient("MATCH", "occupation_decay", 0.40);
                for (String userOcc : userOccList) {
                    String occKey = userOcc.trim().toLowerCase();
                    if (occKey.isEmpty()) continue;
                    double dev = getDeviation(statsService, occKey, scores);
                    totalDeviation += dev * occWeight;
                    totalWeight += occWeight;

                    List<String> adjacentOcc = getAdjacentOccupations(occKey);
                    for (String adj : adjacentOcc) {
                        double adjDev = getDeviation(statsService, adj, scores);
                        totalDeviation += adjDev * occWeight * occDecay;
                        totalWeight += occWeight * occDecay;
                    }
                }
                matchedDimensions++;
            }

            if (user.getEducation() != null) {
                String eduKey = user.getEducation().toLowerCase();
                double eduWeight = coefficientService.getCoefficient("MATCH", "education_weight", 0.8);
                double eduDecay = coefficientService.getCoefficient("MATCH", "education_decay", 0.40);
                double dev = getDeviation(statsService, eduKey, scores);
                totalDeviation += dev * eduWeight;
                totalWeight += eduWeight;

                List<String> adjacentEdu = getAdjacentEducations(eduKey);
                for (String adj : adjacentEdu) {
                    double adjDev = getDeviation(statsService, adj, scores);
                    totalDeviation += adjDev * eduWeight * eduDecay;
                    totalWeight += eduWeight * eduDecay;
                }
                matchedDimensions++;
            }

            if (user.getEntrepreneurship() != null && !user.getEntrepreneurship().isBlank()) {
                String entreKey = user.getEntrepreneurship().toLowerCase();
                double entreWeight = coefficientService.getCoefficient("MATCH", "entrepreneurship_weight", 0.6);
                double dev = getDeviation(statsService, entreKey, scores);
                totalDeviation += dev * entreWeight;
                totalWeight += entreWeight;
                matchedDimensions++;
            }

            if (user.getAnnualIncome() != null && !user.getAnnualIncome().isBlank()
                    && !"PREFER_NOT_TO_SAY".equalsIgnoreCase(user.getAnnualIncome())) {
                String incomeKey = user.getAnnualIncome().toLowerCase();
                double incomeWeight = coefficientService.getCoefficient("MATCH", "income_weight", 0.5);
                double incomeDecay = coefficientService.getCoefficient("MATCH", "income_decay", 0.40);
                double dev = getDeviation(statsService, incomeKey, scores);
                totalDeviation += dev * incomeWeight;
                totalWeight += incomeWeight;

                List<String> adjacentIncome = getAdjacentIncomes(incomeKey);
                for (String adj : adjacentIncome) {
                    double adjDev = getDeviation(statsService, adj, scores);
                    totalDeviation += adjDev * incomeWeight * incomeDecay;
                    totalWeight += incomeWeight * incomeDecay;
                }
                matchedDimensions++;
            }

            if (user.getMood() != null) {
                String moodKey = user.getMood().toLowerCase();
                double moodWeight = coefficientService.getCoefficient("MATCH", "mood_weight", 0.7);
                double moodDecay = coefficientService.getCoefficient("MATCH", "mood_decay", 0.40);
                double dev = getDeviation(statsService, moodKey, scores);
                totalDeviation += dev * moodWeight;
                totalWeight += moodWeight;

                List<String> relatedMoods = getRelatedMoods(moodKey);
                for (String adj : relatedMoods) {
                    double adjDev = getDeviation(statsService, adj, scores);
                    totalDeviation += adjDev * moodWeight * moodDecay;
                    totalWeight += moodWeight * moodDecay;
                }
                matchedDimensions++;
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

            return avgDeviation * coverageFactor;
        } catch (Exception e) {
            log.debug("解析相关度得分失败: bookId={} - {}", book.getId(), e.getMessage());
            return 0.0;
        }
    }

    private static double getDeviation(DimensionStatsService statsService, String key, JsonNode scores) {
        if (!scores.has(key)) return 0.0;
        double rawScore = scores.get(key).asDouble();
        if (statsService != null) {
            return statsService.getZScore(key, rawScore);
        }
        return rawScore - 0.5;
    }

    static String getAgeGroup(int age) {
        if (age < 10) return "0-9";
        if (age < 20) return "10-19";
        if (age < 30) return "20-29";
        if (age < 40) return "30-39";
        if (age < 50) return "40-49";
        if (age < 60) return "50-59";
        return "60+";
    }

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

    static List<String> getRelatedMoods(String mood) {
        return switch (mood.toLowerCase()) {
            case "happy" -> List.of("motivated", "calm");
            case "calm" -> List.of("happy", "curious");
            case "anxious" -> List.of("sad", "tired");
            case "sad" -> List.of("anxious", "tired");
            case "motivated" -> List.of("happy", "curious");
            case "tired" -> List.of("sad", "anxious");
            case "curious" -> List.of("calm", "motivated");
            default -> List.of();
        };
    }

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

    public static String getEntrepreneurshipLabel(String entrepreneurship) {
        if (entrepreneurship == null) return "";
        return switch (entrepreneurship.toUpperCase()) {
            case "ENTREPRENEUR_OR_WANT" -> "正在创业/想创业";
            case "NOT_INTERESTED" -> "暂不考虑";
            default -> entrepreneurship;
        };
    }

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
