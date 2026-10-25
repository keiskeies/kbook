package com.kbook.service.recommend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.dto.book.BookProjection;
import com.kbook.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.kbook.service.recommend.RecommendMatchCalculator.MatchWeight;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 推荐匹配度计算器单测（阶段 2 第 1 批 — 纯函数，零 Spring/零 mock）。
 * <p>
 * 协作对象处理：
 * <ul>
 *   <li>{@link RecommendCoefficientService} 为字段注入无构造依赖，匿名子类覆写直接返回默认值</li>
 *   <li>{@code calculateMatchScore} 允许 statsService = null（走 rawScore - 0.5 兜底偏差），无需桩</li>
 * </ul>
 */
class RecommendMatchCalculatorTest {

    private static RecommendCoefficientService defaultCoefficients() {
        return new RecommendCoefficientService() {
            @Override
            public double getCoefficient(String category, String key, double fallback) {
                return fallback;
            }
        };
    }

    private static User userWithFullProfile() {
        User user = new User();
        user.setBirthday(LocalDate.now().minusYears(25));   // → "20-29"
        user.setGender("MALE");
        user.setMarried(true);
        user.setMbti("INTJ");
        user.setOccupation("tech");
        user.setMood("growth|calm");                        // intent=growth, mood=calm
        return user;
    }

    private static BookProjection bookWithScores(String json, Double rating) {
        // BookProjection 为 @AllArgsConstructor（22 参），除 id(1)/rating(9)/relevanceScores(13) 外均 null
        return new BookProjection(1L, null, null, null, null, null, null, null, rating,
                null, null, null, json, null, null, null, null, null, null, null, null, null);
    }

    // ==================== 归一化与覆盖因子 ====================

    @Nested
    @DisplayName("normalizeScore — Sigmoid 归一化")
    class NormalizeScore {

        @Test
        void 非正输入硬截断为0() {
            assertThat(RecommendMatchCalculator.normalizeScore(0.0)).isZero();
            assertThat(RecommendMatchCalculator.normalizeScore(-0.5)).isZero();
        }

        @Test
        void 中点05输出05() {
            assertThat(RecommendMatchCalculator.normalizeScore(0.5)).isEqualTo(0.5);
        }

        @Test
        void 满分1输出sigmoid值并保留4位小数() {
            // 1/(1+e^(-4*(1-0.5))) = 1/(1+e^-2) ≈ 0.8808
            assertThat(RecommendMatchCalculator.normalizeScore(1.0)).isEqualTo(0.8808);
        }
    }

    @Nested
    @DisplayName("getCoverageFactor — 画像覆盖率因子")
    class CoverageFactor {

        @Test
        void 全维度覆盖为1() {
            assertThat(RecommendMatchCalculator.getCoverageFactor(11)).isEqualTo(1.0);
        }

        @Test
        void 维度越少因子越低() {
            assertThat(RecommendMatchCalculator.getCoverageFactor(6)).isEqualTo(0.90);
            assertThat(RecommendMatchCalculator.getCoverageFactor(1)).isEqualTo(0.60);
        }

        @Test
        void 零维度与超界走兜底05() {
            assertThat(RecommendMatchCalculator.getCoverageFactor(0)).isEqualTo(0.50);
            assertThat(RecommendMatchCalculator.getCoverageFactor(12)).isEqualTo(0.50);
        }
    }

    // ==================== 维度映射 ====================

    @Nested
    @DisplayName("年龄分组与相邻组")
    class AgeGroups {

        @Test
        void 每10岁一组() {
            assertThat(RecommendMatchCalculator.getAgeGroup(5)).isEqualTo("0-9");
            assertThat(RecommendMatchCalculator.getAgeGroup(9)).isEqualTo("0-9");
            assertThat(RecommendMatchCalculator.getAgeGroup(10)).isEqualTo("10-19");
            assertThat(RecommendMatchCalculator.getAgeGroup(29)).isEqualTo("20-29");
            assertThat(RecommendMatchCalculator.getAgeGroup(60)).isEqualTo("60+");
            assertThat(RecommendMatchCalculator.getAgeGroup(99)).isEqualTo("60+");
        }

        @Test
        void 相邻组前推后推() {
            assertThat(RecommendMatchCalculator.getAdjacentAgeGroup(25, -1)).isEqualTo("10-19");
            assertThat(RecommendMatchCalculator.getAdjacentAgeGroup(25, 1)).isEqualTo("30-39");
        }

        @Test
        void 边界外无相邻组返回null() {
            assertThat(RecommendMatchCalculator.getAdjacentAgeGroup(5, -1)).isNull();
            assertThat(RecommendMatchCalculator.getAdjacentAgeGroup(70, 1)).isNull();
            assertThat(RecommendMatchCalculator.getAdjacentAgeGroup(70, -1)).isEqualTo("50-59");
        }
    }

    @Nested
    @DisplayName("意图权重调制")
    class IntentModulation {

        @Test
        void 无意图不调制() {
            assertThat(RecommendMatchCalculator.getIntentModulation(null, MatchWeight.AGE)).isEqualTo(1.0);
            assertThat(RecommendMatchCalculator.getIntentModulation("", MatchWeight.MOOD)).isEqualTo(1.0);
        }

        @Test
        void growth压制人生状态放大发展维度() {
            assertThat(RecommendMatchCalculator.getIntentModulation("growth", MatchWeight.CHILDREN)).isEqualTo(0.25);
            assertThat(RecommendMatchCalculator.getIntentModulation("growth", MatchWeight.MARRIED)).isEqualTo(0.30);
            assertThat(RecommendMatchCalculator.getIntentModulation("growth", MatchWeight.OCCUPATION)).isEqualTo(1.20);
            assertThat(RecommendMatchCalculator.getIntentModulation("growth", MatchWeight.ENTREPRENEURSHIP)).isEqualTo(1.30);
        }

        @Test
        void comfort放大人生状态与心情() {
            assertThat(RecommendMatchCalculator.getIntentModulation("comfort", MatchWeight.CHILDREN)).isEqualTo(1.20);
            assertThat(RecommendMatchCalculator.getIntentModulation("comfort", MatchWeight.MOOD)).isEqualTo(1.30);
        }

        @Test
        void escape放大心情_未知与insight保持中性() {
            assertThat(RecommendMatchCalculator.getIntentModulation("escape", MatchWeight.MOOD)).isEqualTo(1.30);
            assertThat(RecommendMatchCalculator.getIntentModulation("insight", MatchWeight.AGE)).isEqualTo(1.0);
            assertThat(RecommendMatchCalculator.getIntentModulation("unknown", MatchWeight.AGE)).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("关联心情/意图链")
    class RelatedChains {

        @Test
        void 正面情绪互相关联() {
            assertThat(RecommendMatchCalculator.getRelatedMoods("happy")).containsExactly("calm");
            assertThat(RecommendMatchCalculator.getRelatedMoods("calm")).containsExactly("happy");
        }

        @Test
        void 负面情绪链() {
            assertThat(RecommendMatchCalculator.getRelatedMoods("anxious"))
                    .containsExactly("sad", "tired", "frustrated");
        }

        @Test
        void 前端有后端无的心情走空列表() {
            // 前端 profile 页有 MOTIVATED/CURIOUS，后端不支持，应返回空而非报错
            assertThat(RecommendMatchCalculator.getRelatedMoods("motivated")).isEmpty();
            assertThat(RecommendMatchCalculator.getRelatedMoods("curious")).isEmpty();
        }

        @Test
        void 意图相邻链不相邻不关联() {
            assertThat(RecommendMatchCalculator.getRelatedIntents("growth")).containsExactly("insight");
            assertThat(RecommendMatchCalculator.getRelatedIntents("insight"))
                    .containsExactly("growth", "comfort");
            assertThat(RecommendMatchCalculator.getRelatedIntents("unknown")).isEmpty();
        }
    }

    // ==================== 匹配分计算 ====================

    @Nested
    @DisplayName("calculateMatchScore — 匹配分")
    class MatchScore {

        @Test
        void 无相关度数据返回0() {
            User user = userWithFullProfile();
            assertThat(RecommendMatchCalculator.calculateMatchScore(
                    user, bookWithScores(null, 4.5), defaultCoefficients(), new ObjectMapper(), null))
                    .isZero();
            assertThat(RecommendMatchCalculator.calculateMatchScore(
                    user, bookWithScores("  ", 4.5), defaultCoefficients(), new ObjectMapper(), null))
                    .isZero();
        }

        @Test
        void 非法JSON返回0() {
            User user = userWithFullProfile();
            assertThat(RecommendMatchCalculator.calculateMatchScore(
                    user, bookWithScores("{not-json", 4.5), defaultCoefficients(), new ObjectMapper(), null))
                    .isZero();
        }

        @Test
        void 画像契合的书得分高于不契合的书() {
            User user = userWithFullProfile();
            BookProjection good = bookWithScores(
                    "{\"20-29\":0.9,\"male\":0.9,\"married\":0.9,\"INTJ\":0.9,\"tech\":0.9,"
                            + "\"growth\":0.9,\"calm\":0.9}", 4.8);
            BookProjection bad = bookWithScores(
                    "{\"20-29\":0.1,\"male\":0.1,\"married\":0.1,\"INTJ\":0.1,\"tech\":0.1,"
                            + "\"growth\":0.1,\"calm\":0.1}", 2.0);

            double goodScore = RecommendMatchCalculator.calculateMatchScore(
                    user, good, defaultCoefficients(), new ObjectMapper(), null);
            double badScore = RecommendMatchCalculator.calculateMatchScore(
                    user, bad, defaultCoefficients(), new ObjectMapper(), null);

            // 全维度低分的加权偏差为负，Sigmoid 前被硬截断 → 0
            assertThat(badScore).isZero();
            assertThat(goodScore).isGreaterThan(0.25);
            assertThat(goodScore).isLessThanOrEqualTo(1.0);
            assertThat(goodScore).isGreaterThan(badScore);
        }

        @Test
        void 得分始终在0到1之间() {
            User user = userWithFullProfile();
            BookProjection book = bookWithScores(
                    "{\"20-29\":0.9,\"INTJ\":0.9,\"growth\":0.9}", 5.0);
            double score = RecommendMatchCalculator.calculateMatchScore(
                    user, book, defaultCoefficients(), new ObjectMapper(), null);
            assertThat(score).isBetween(0.0, 1.0);
        }
    }

    @Nested
    @DisplayName("calculateMatchScoreDetail — 匹配明细")
    class MatchScoreDetail {

        @Test
        void 无数据返回空壳明细() {
            User user = userWithFullProfile();
            var detail = RecommendMatchCalculator.calculateMatchScoreDetail(
                    user, bookWithScores(null, 4.0), defaultCoefficients(), new ObjectMapper(), null);
            assertThat(detail.getMatchedDimensions()).isZero();
            assertThat(detail.getDimensions()).isEmpty();
            assertThat(detail.getCoverageFactor()).isEqualTo(0.35);
        }

        @Test
        void 正常数据返回维度明细且总分保留2位() {
            User user = userWithFullProfile();
            BookProjection book = bookWithScores(
                    "{\"20-29\":0.9,\"male\":0.9,\"INTJ\":0.9,\"tech\":0.9,\"growth\":0.9,\"calm\":0.9}", 4.5);
            var detail = RecommendMatchCalculator.calculateMatchScoreDetail(
                    user, book, defaultCoefficients(), new ObjectMapper(), null);

            assertThat(detail.getMatchedDimensions()).isGreaterThanOrEqualTo(6);
            assertThat(detail.getDimensions()).isNotEmpty();
            // 总分是 round2：乘 100 后应为整数
            double scaled = detail.getOverallScore() * 100.0;
            assertThat(Math.abs(scaled - Math.round(scaled))).isLessThan(1e-6);
        }
    }
}
