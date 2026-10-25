package com.kbook.service.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每日快照的四个默认值，其中 `snapshot-enabled=false` 是安全默认：
 * 写成 true 意味着生产环境一启动就每晚自动写库，而且不会有任何提示。
 *
 * 与阈值/TTL 不同，这四项**只存在于 @Value**（没有 yml 条目），所以这里只断言注解原文；
 * env 名本身由 `deploy/verify/env-names.mjs` 核对，二者分工不重叠。
 */
class BookProfileSnapshotConfigTest {

    private static String placeholder(String fieldName) throws Exception {
        Field field = BookProfileSnapshotService.class.getDeclaredField(fieldName);
        Value value = field.getAnnotation(Value.class);
        assertThat(value).as("%s 上没有 @Value", fieldName).isNotNull();
        return value.value();
    }

    @Test
    @DisplayName("定时任务默认必须关闭——写成 true 就是生产每晚自动写库且无提示")
    void schedulerIsOffByDefault() throws Exception {
        assertThat(placeholder("enabled"))
                .isEqualTo("${kbook.admin.book-profile.snapshot-enabled:false}");
    }

    @Test
    @DisplayName("其余三个默认值与文档一致（DEEP / 500 / 03:10）")
    void otherDefaultsMatchDocumentation() throws Exception {
        Field bases = BookProfileSnapshotWriter.class.getDeclaredField("bases");
        Field maxBooks = BookProfileSnapshotWriter.class.getDeclaredField("maxBooks");
        assertThat(bases.getAnnotation(Value.class).value())
                .isEqualTo("${kbook.admin.book-profile.snapshot-bases:DEEP}");
        assertThat(maxBooks.getAnnotation(Value.class).value())
                .isEqualTo("${kbook.admin.book-profile.snapshot-max-books:500}");

        var scheduled = BookProfileSnapshotService.class
                .getDeclaredMethod("scheduledSnapshot")
                .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertThat(scheduled).as("scheduledSnapshot 上没有 @Scheduled").isNotNull();
        assertThat(scheduled.cron())
                .isEqualTo("${kbook.admin.book-profile.snapshot-cron:0 10 3 * * *}");
    }
}
