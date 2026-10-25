package com.kbook.service.admin;

import com.kbook.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 时间窗共享判定的单测（设计文档 2.2 节 + 附录 G.7）。
 *
 * 要钉住的是「只有一个地方知道档位」这件事：主看板与画像接口此前各写一份 switch，
 * 加一档而漏改另一份时，画像会静默按 7 天算、看板按新档算，页面两处数字对不上却不报错。
 */
class AdminRangeRulesTest {

    @ParameterizedTest(name = "days={0} -> {1}")
    @CsvSource({
            "7, 7", "30, 30", "90, 90", "0, 0",
            "15, 7", "365, 7", "-1, 7", "1, 7",
    })
    @DisplayName("预设档位：只有 30/90/0 有效，其余一律按近 7 天")
    void normalizeDaysFallsBackToSeven(int days, int expected) {
        assertThat(AdminRangeRules.normalizeDays(days)).isEqualTo(expected);
    }

    @Test
    @DisplayName("只给单边日期时报错，不能让另一边静默为 null")
    void customRangeNeedsBothEnds() {
        LocalDate day = LocalDate.of(2026, 10, 1);
        assertThatThrownBy(() -> AdminRangeRules.requireValidCustomRange(null, day))
                .isInstanceOf(BusinessException.class)
                .hasMessage("自定义区间需要同时提供开始和结束日期");
        assertThatThrownBy(() -> AdminRangeRules.requireValidCustomRange(day, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("自定义区间需要同时提供开始和结束日期");
    }

    @Test
    @DisplayName("开始晚于结束单独报错，不与长度上限混成一句")
    void startAfterEndIsRejected() {
        assertThatThrownBy(() -> AdminRangeRules.requireValidCustomRange(
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("开始日期不能晚于结束日期");
    }

    @Test
    @DisplayName("上限是闭区间的 366 天：366 通过、367 拒绝，文案带常量值")
    void customRangeCapsAt366DaysInclusive() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        assertThatCode(() -> AdminRangeRules.requireValidCustomRange(
                start, start.plusDays(AdminDashboardService.MAX_CUSTOM_RANGE_DAYS - 1L)))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> AdminRangeRules.requireValidCustomRange(
                start, start.plusDays(AdminDashboardService.MAX_CUSTOM_RANGE_DAYS)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("自定义区间最长支持 " + AdminDashboardService.MAX_CUSTOM_RANGE_DAYS
                        + " 天，全部数据请使用「全部」");
    }

    @Test
    @DisplayName("同一天是合法区间，跨度 1 天")
    void singleDayRangeIsValid() {
        LocalDate day = LocalDate.of(2026, 10, 1);
        assertThatCode(() -> AdminRangeRules.requireValidCustomRange(day, day))
                .doesNotThrowAnyException();
    }

    // ==================== resolve：第 11 节那条优先级 ====================

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

    @Test
    @DisplayName("days 与 start/end 同时给：以 start/end 为准，days 完全不参与")
    void customRangeOverridesDays() {
        LocalDate start = LocalDate.of(2026, 10, 20);
        LocalDate end = LocalDate.of(2026, 10, 24);

        // days=0 是「全部时间」，若它先判，区间会变成 (null,null) —— 这条就是钉住分支顺序
        assertThat(AdminRangeRules.resolve(start, end, 0, TODAY))
                .isEqualTo(new AdminRangeRules.Range(start, end));
        assertThat(AdminRangeRules.resolve(start, end, 7, TODAY))
                .isEqualTo(new AdminRangeRules.Range(start, end));
        assertThat(AdminRangeRules.resolve(start, end, 15, TODAY))
                .isEqualTo(new AdminRangeRules.Range(start, end));
    }

    @ParameterizedTest(name = "days={0} -> 起日 = today-{1}")
    @CsvSource({"7, 6", "30, 29", "90, 89", "15, 6", "1, 6", "-1, 6"})
    @DisplayName("无 start/end 时按档位取闭区间窗口（含今天，所以是 N-1 天）")
    void presetWindowIsInclusiveOfToday(int days, int startOffset) {
        AdminRangeRules.Range range = AdminRangeRules.resolve(null, null, days, TODAY);

        assertThat(range.startDate()).isEqualTo(TODAY.minusDays(startOffset));
        assertThat(range.endDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("days=0 且无 start/end 才是全部时间")
    void zeroDaysMeansAllTimeOnlyWhenNoCustomRange() {
        assertThat(AdminRangeRules.resolve(null, null, 0, TODAY))
                .isEqualTo(new AdminRangeRules.Range(null, null));
    }

    @Test
    @DisplayName("单边日期不能被 days 静默兜掉")
    void halfGivenRangeThrowsInsteadOfFallingBackToPreset() {
        LocalDate start = LocalDate.of(2026, 10, 20);

        assertThatThrownBy(() -> AdminRangeRules.resolve(start, null, 7, TODAY))
                .isInstanceOf(BusinessException.class)
                .hasMessage("自定义区间需要同时提供开始和结束日期");
    }
}
