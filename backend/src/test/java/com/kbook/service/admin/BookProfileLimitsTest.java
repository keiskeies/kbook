package com.kbook.service.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把"代码常量 ↔ 文档数字"钉在一起：这些数会被接口或错误文案直接暴露给使用者，
 * 改常量不会编译失败，也不会让别的测试变红，只会让文档变成假话。
 */
class BookProfileLimitsTest {

    private static int intConstant(Class<?> owner, String fieldName) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    @Test
    @DisplayName("反查 limit 上限 = 50（§6.2 的 1..50）")
    void queryLimitMatchesContract() throws Exception {
        assertThat(intConstant(BookProfileService.class, "MAX_QUERY_LIMIT"))
                .as("接口契约写的是 1..50，改常量等于改契约")
                .isEqualTo(50);
    }

    @Test
    @DisplayName("自定义区间上限 = 366 天（§2.2 报错文案里的数字）")
    void customRangeLimitMatchesErrorText() throws Exception {
        assertThat(intConstant(AdminDashboardService.class, "MAX_CUSTOM_RANGE_DAYS"))
                .as("错误文案直接拼这个数，改了文案就与文档不一致")
                .isEqualTo(366);
    }

    @Test
    @DisplayName("IN 分片 2000，且必须小于 §9 说的 5000 阈值")
    void idChunkStaysUnderTheSingleQueryThreshold() throws Exception {
        int chunk = intConstant(BookProfileService.class, "ID_CHUNK_SIZE");
        assertThat(chunk).isEqualTo(2000);
        assertThat(chunk)
                .as("§9 的判定是 IN 超过 5000 就改子查询；分片必须低于它，否则该结论失效")
                .isLessThan(5000);
    }
}
