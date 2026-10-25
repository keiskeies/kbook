package com.kbook.service.recommend;

import com.kbook.entity.User;
import com.kbook.service.tools.DimensionStatsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 匹配度 SQL 表达式构建单测（阶段 2 第 1 批）。
 * <p>
 * 协作对象零 mock：两个服务均为非 final 类，匿名子类覆写所需方法即可，
 * 期望列名从 {@link BookDimensionScoreService#DIMENSION_COLUMNS} 动态取，避免硬编码漂移。
 */
class ScoreSqlBuilderTest {

    private static RecommendCoefficientService defaultCoefficients() {
        return new RecommendCoefficientService() {
            @Override
            public double getCoefficient(String category, String key, double fallback) {
                return fallback;
            }
        };
    }

    /** 均值 0.5 / 标准差 1 的中性统计桩 */
    private static DimensionStatsService neutralStats() {
        return new DimensionStatsService(null, null) {
            @Override
            public double getMean(String dimensionKey) {
                return 0.5;
            }

            @Override
            public double getStddev(String dimensionKey) {
                return 1.0;
            }
        };
    }

    private final ScoreSqlBuilder builder = new ScoreSqlBuilder();

    @Test
    @DisplayName("空画像用户只有评分项 — 单维度、默认权重 0.8")
    void emptyProfileProducesOnlyRatingTerm() {
        ScoreSqlBuilder.BuildResult result = builder.build(new User(), neutralStats(), defaultCoefficients());

        assertThat(result.weightedSumSql())
                .contains("(COALESCE(b.rating/5.0,0)-0.5)*0.800000");
        assertThat(result.activeDimensionCount()).isEqualTo(1);
        assertThat(result.totalWeight()).isEqualTo(0.8);
        assertThat(result.intentKey()).isNull();
    }

    @Test
    @DisplayName("25 岁用户展开主年龄组与前后相邻组衰减项")
    void ageProducesMainAndAdjacentTerms() {
        User user = new User();
        user.setBirthday(LocalDate.now().minusYears(25));

        ScoreSqlBuilder.BuildResult result = builder.build(user, neutralStats(), defaultCoefficients());

        String sql = result.weightedSumSql();
        String mainCol = BookDimensionScoreService.DIMENSION_COLUMNS.get("20-29");
        String youngerCol = BookDimensionScoreService.DIMENSION_COLUMNS.get("10-19");
        String olderCol = BookDimensionScoreService.DIMENSION_COLUMNS.get("30-39");
        assertThat(mainCol).isNotNull();
        assertThat(sql).contains("ds." + mainCol);
        assertThat(sql).contains("ds." + youngerCol);
        assertThat(sql).contains("ds." + olderCol);
        // 相邻项权重 = 1.5 * 0.4 = 0.6
        assertThat(sql).contains("*0.600000");
        assertThat(result.activeDimensionCount()).isEqualTo(2); // rating + age
    }

    @Test
    @DisplayName("性别维度带反向惩罚 GREATEST 项")
    void genderProducesOppositePenalty() {
        User user = new User();
        user.setGender("MALE");

        String sql = builder.build(user, neutralStats(), defaultCoefficients()).weightedSumSql();

        assertThat(sql).contains("GREATEST(");
        assertThat(sql).contains(BookDimensionScoreService.DIMENSION_COLUMNS.get("male"));
        assertThat(sql).contains(BookDimensionScoreService.DIMENSION_COLUMNS.get("female"));
    }

    @Test
    @DisplayName("mood 逗号前段解析为意图 key")
    void moodPipeSplitsIntent() {
        User user = new User();
        user.setMood("growth|calm");

        ScoreSqlBuilder.BuildResult result = builder.build(user, neutralStats(), defaultCoefficients());

        assertThat(result.intentKey()).isEqualTo("growth");
        assertThat(result.weightedSumSql()).contains(
                "ds." + BookDimensionScoreService.DIMENSION_COLUMNS.get("growth"));
        assertThat(result.weightedSumSql()).contains(
                "ds." + BookDimensionScoreService.DIMENSION_COLUMNS.get("calm"));
    }

    @Test
    @DisplayName("表达式以最后一项收尾，不残留 ' + ' 后缀")
    void expressionHasNoTrailingPlus() {
        User user = new User();
        user.setGender("FEMALE");
        user.setMbti("INTJ");

        String sql = builder.build(user, neutralStats(), defaultCoefficients()).weightedSumSql();

        assertThat(sql).isNotEmpty();
        assertThat(sql).doesNotEndWith(" + ");
    }
}
