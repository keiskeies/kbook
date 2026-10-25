package com.kbook.service.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快照写路径的两层断言：候选筛选的口径（纯函数），以及事务边界真的落在被调用的那个 bean 上。
 *
 * 第二层是这次拆分的原因：定时任务原先在同一个类里调 {@code @Transactional} 方法，
 * self-invocation 不经过代理，事务静默失效——单测不会红、运行也不报错，只有"跑一半失败留下半批数据"
 * 时才看得出来。所以在这里把"注解在 writer 上、入口只做委托"钉死。
 */
class BookProfileSnapshotWriterTest {

    private static Object[] row(long bookId, long count) {
        return new Object[]{bookId, count};
    }

    @Test
    @DisplayName("按读者数降序，不是按 id")
    void sortsByReadersDescending() {
        List<Long> picked = BookProfileSnapshotWriter.pickCandidates(
                List.of(row(9, 3), row(3, 40), row(7, 12)), 10, count -> false);

        assertThat(picked).containsExactly(3L, 7L, 9L);
    }

    @Test
    @DisplayName("零读者与 cohort 保护的书都不入选")
    void filtersZeroAndProtected() {
        List<Long> picked = BookProfileSnapshotWriter.pickCandidates(
                List.of(row(1, 0), row(2, 4), row(3, 25)), 10, count -> count < 10);

        assertThat(picked).containsExactly(3L);
    }

    @Test
    @DisplayName("超过 max-books 时只取前 N 本（按读者数）")
    void capsAtMaxBooks() {
        List<Long> picked = BookProfileSnapshotWriter.pickCandidates(
                List.of(row(1, 5), row(2, 50), row(3, 30), row(4, 20)), 2, count -> false);

        assertThat(picked).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("max-books 为 0 或负数 → 一本都不拍（不静默当成不限量）")
    void nonPositiveMaxMeansNone() {
        List<Object[]> rows = List.of(row(1, 5), row(2, 50));

        assertThat(BookProfileSnapshotWriter.pickCandidates(rows, 0, count -> false)).isEmpty();
        assertThat(BookProfileSnapshotWriter.pickCandidates(rows, -3, count -> false)).isEmpty();
    }

    @Test
    @DisplayName("脏行（null / 列数不足 / book_id 不是数字）跳过而不是抛异常")
    void skipsMalformedRows() {
        List<Long> picked = BookProfileSnapshotWriter.pickCandidates(
                Arrays.asList(null, new Object[]{1L}, new Object[]{"x", 10L}, row(5, 8)), 10, count -> false);

        assertThat(picked).containsExactly(5L);
    }

    @Test
    @DisplayName("事务注解必须在 writer.snapshot 上（跨 bean 调用才会生效）")
    void transactionLivesOnTheWriter() throws Exception {
        var method = BookProfileSnapshotWriter.class.getDeclaredMethod("snapshot", java.time.LocalDate.class);

        assertThat(method.getAnnotation(Transactional.class))
                .as("writer.snapshot 上没有 @Transactional，定时快照就没有事务边界")
                .isNotNull();
    }

    /** 同一天被定时任务与手工接口同时触发时，第二个请求必须得到可读提示而不是唯一键 500（附录 AL） */
    @Test
    @DisplayName("快照运行串行化：有 tryLock 与可读的业务错误文案")
    void concurrentRunsAreSerialized() throws Exception {
        Path src = Path.of("src/main/java/com/kbook/service/admin/BookProfileSnapshotWriter.java");
        assertThat(Files.exists(src)).as("找不到 %s", src.toAbsolutePath()).isTrue();
        String code = Files.readString(src);

        assertThat(code).contains("runLock.tryLock()");
        assertThat(code).contains("快照正在执行，请稍后重试");
        assertThat(code).contains("snapshotLocked(date)");
    }

    /** 入口只做委托：既不能自己声明事务（会给人"这里是事务边界"的错觉），也不能自己 new writer */
    @Test
    @DisplayName("入口类不带 @Transactional，并且把写库委托给 writer")
    void entryPointOnlyDelegates() throws Exception {
        Path src = Path.of("src/main/java/com/kbook/service/admin/BookProfileSnapshotService.java");
        assertThat(Files.exists(src)).as("找不到 %s", src.toAbsolutePath()).isTrue();
        String code = Files.readString(src);

        assertThat(code).contains("writer.snapshot(");
        // 注意：Javadoc 里讨论这个注解时会出现字面量，所以查 import / 带参注解 / 字段声明，
        // 而不是裸标识符——这类"源码文本断言"必须选只有代码才会出现的子串。
        assertThat(code).doesNotContain("import org.springframework.transaction");
        assertThat(code).doesNotContain("@Transactional(rollbackFor");
        assertThat(code).doesNotContain("import jakarta.persistence.EntityManager");
        assertThat(code).doesNotContain("private final EntityManager");
    }
}
