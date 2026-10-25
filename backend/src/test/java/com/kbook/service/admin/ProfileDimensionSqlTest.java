package com.kbook.service.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 维度取值 SQL 的形状断言（设计文档 §5 / §5.1 / §11 第 4 条的 SQL 一侧）。
 *
 * 归一化（toToken）有一整份单测，但喂给它的那串 SQL 没有。缺的不是覆盖率，是两类只会静默发生的事：
 * 布尔列的 CAST 写法退化会让「填了」的人全变成未填写（见 boolSql 注释里的实测），
 * 年龄的未来日期分支被删会让负数年龄进「18 岁以下」。两种都不会让接口报错，只会让数字变小、桶变脏。
 */
class ProfileDimensionSqlTest {

    private static final Set<BookProfileDimension> BOOL_COLUMNS = Set.of(
            BookProfileDimension.MARITAL, BookProfileDimension.CHILDREN);

    @Test
    @DisplayName("布尔列必须 CAST(CAST(col AS UNSIGNED) AS CHAR)，退回单 CAST 就是丢掉这两维的样本")
    void boolColumnsNeedTheNestedCast() {
        for (BookProfileDimension dim : BOOL_COLUMNS) {
            String column = dim == BookProfileDimension.MARITAL ? "u.is_married" : "u.has_children";

            assertThat(dim.getValueSql())
                    .contains("CASE WHEN " + column + " IS NULL THEN '" + BookProfileDimension.UNKNOWN + "'")
                    .contains("CAST(CAST(" + column + " AS UNSIGNED) AS CHAR)")
                    .doesNotContain("CAST(" + column + " AS CHAR)");
        }
    }

    @Test
    @DisplayName("未填写只可能来自这两列的 NULL，别的写法（空串、0）都归不到未填写")
    void onlyBoolColumnsAreBitTyped() {
        assertThat(BOOL_COLUMNS).allSatisfy(dim ->
                assertThat(dim.getValueSql()).contains("AS UNSIGNED"));
        for (BookProfileDimension dim : BookProfileDimension.values()) {
            if (!BOOL_COLUMNS.contains(dim)) {
                assertThat(dim.getValueSql()).doesNotContain("AS UNSIGNED");
            }
        }
    }

    @Test
    @DisplayName("年龄：NULL 进未填写、未来日期进 -1（脏值），其余按整岁取")
    void ageGuardsNullAndFutureBirthday() {
        assertThat(BookProfileDimension.AGE.getValueSql())
                .contains("WHEN u.birthday > CURDATE() THEN '-1'")
                .contains("TIMESTAMPDIFF(YEAR, u.birthday, CURDATE())");
        // 删掉这一分支不会报错，只会让 -5 岁落进「18 岁以下」
        assertThat(BookProfileDimension.AGE.toToken("-1")).isEqualTo(BookProfileDimension.DIRTY);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BookProfileDimension.class)
    @DisplayName("字符串列必须把空串折叠成未填写——§5.1「NULL 与空串同桶」是在 GROUP BY 之前成立的")
    void emptyStringsFoldIntoUnfilled(BookProfileDimension dim) {
        if (BOOL_COLUMNS.contains(dim) || dim == BookProfileDimension.AGE) {
            return;
        }
        assertThat(dim.getValueSql())
                .contains("COALESCE(NULLIF(TRIM(u.")
                .contains("'" + BookProfileDimension.UNKNOWN + "'");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BookProfileDimension.class)
    @DisplayName("维度 SQL 只认 users 的别名 u：ProfileReaderSql 的 JOIN 就是这个名字，写成 users. 会整片 500")
    void dimensionsReadTheUsersAlias(BookProfileDimension dim) {
        assertThat(dim.getValueSql()).contains("u.");
        assertThat(dim.getValueSql()).doesNotContain("users.");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BookProfileDimension.class)
    @DisplayName("维度里不许再写一遍「谁算读者」：APPROVED 只在 ProfileReaderSql 有一份（附录 K）")
    void dimensionsDoNotReimplementTheReaderFilter(BookProfileDimension dim) {
        assertThat(dim.getValueSql()).doesNotContain("status");
    }
}
