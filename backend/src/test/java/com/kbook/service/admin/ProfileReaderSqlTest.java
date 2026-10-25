package com.kbook.service.admin;

import com.kbook.service.ai.BookChatScope;
import com.kbook.service.admin.BookProfileService.Basis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 画像取数 SQL 的形状断言（设计文档 §2.1 口径 + §4「只算 APPROVED 账号」+ §7.2 索引约束）。
 *
 * 这些 SQL 之前不有一份能跑的测试，因为它们散在服务的 7 个方法里、且都直接喂给 EntityManager。
 * 现在形状集中在 {@link ProfileReaderSql}，就能钉住两件只有编译器管不到的事：
 * 「谁算读者」的谓词是否还在每一条上（漏一处不会报错，只会让未审核账号进画像），
 * 以及谓词是否仍逐分支下发（提到 UNION 外面会全表物化，§7.2 的索引前提就没了）。
 */
class ProfileReaderSqlTest {

    private static final String APPROVED = "u.status = 'APPROVED'";
    private static final String WINDOW = " AND x.ts >= :start AND x.ts < :end";
    private static final String VALUE_SQL = "NULLIF(TRIM(u.education), '')";

    private static int occurrences(String haystack, String needle) {
        return haystack.split(Pattern.quote(needle), -1).length - 1;
    }

    @ParameterizedTest(name = "读者池 {0}")
    @EnumSource(Basis.class)
    @DisplayName("读者池：三口径都过滤 APPROVED，且按 user_id 去重")
    void readerPoolKeepsApprovedAndDedups(Basis basis) {
        String sql = ProfileReaderSql.readerPool(basis, WINDOW);

        assertThat(sql).startsWith("SELECT DISTINCT x.user_id FROM (");
        assertThat(sql).contains(APPROVED);
        assertThat(sql).contains("x.ts >= :start AND x.ts < :end");
    }

    @ParameterizedTest(name = "聚合 {0} + {1}")
    @EnumSource(Basis.class)
    @DisplayName("榜单分子与分母共用同一条聚合：APPROVED + 按书去重计人")
    void aggregateSharesTheSameReaderDefinition(Basis basis) {
        for (String predicate : new String[]{"user_id IN (:ids)", "book_id IN (:ids)"}) {
            String sql = ProfileReaderSql.bookUserAggregate(basis, predicate, WINDOW);

            assertThat(sql).contains("COUNT(DISTINCT x.user_id)");
            assertThat(sql).contains(APPROVED);
            assertThat(sql).contains(" GROUP BY x.book_id");
        }
    }

    @Test
    @DisplayName("四条只碰 users 的 SQL 也必须各带 APPROVED——漏一条就是未审核账号进画像")
    void everyUsersQueryFiltersApproved() {
        assertThat(ProfileReaderSql.bucketUserIds(VALUE_SQL)).contains(APPROVED);
        assertThat(ProfileReaderSql.countBuckets(VALUE_SQL)).contains(APPROVED);
        assertThat(ProfileReaderSql.siteReaders()).contains(APPROVED);
        assertThat(ProfileReaderSql.siteBucket(VALUE_SQL)).contains(APPROVED);
        assertThat(ProfileReaderSql.profiledUsers()).contains(APPROVED);
    }

    @Test
    @DisplayName("本书分布与全站基线是同一条口径，只差 ids 限定")
    void bookAndSiteBucketsUseSameShape() {
        assertThat(ProfileReaderSql.countBuckets(VALUE_SQL))
                .isEqualTo(ProfileReaderSql.siteBucket(VALUE_SQL).replace(
                        APPROVED, APPROVED + " AND u.id IN (:ids)"));
    }

    @ParameterizedTest(name = "{0} 的分支数")
    @EnumSource(Basis.class)
    @DisplayName("谓词逐分支下发：出现次数必须等于该口径的 UNION 分支数（§7.2 的索引前提）")
    void predicateIsPushedDownIntoEveryBranch(Basis basis) {
        String sql = ProfileReaderSql.bookUserSource(basis, "book_id = :bookId");
        int branches = switch (basis) {
            case READ -> 1;
            case TOUCH -> 2;
            case DEEP -> 3;
        };

        assertThat(occurrences(sql, " AND book_id = :bookId")).isEqualTo(branches);
        assertThat(occurrences(sql, "UNION ALL")).isEqualTo(branches - 1);
    }

    @ParameterizedTest(name = "{0} 的时间列")
    @EnumSource(Basis.class)
    @DisplayName("三口径各自锁定的时间列与前置条件（§2.1，改列名等于改口径）")
    void basisTimeColumnsAreFixed(Basis basis) {
        String sql = ProfileReaderSql.bookUserSource(basis, null);

        switch (basis) {
            case READ -> assertThat(sql).contains("rp.updated_at AS ts").contains("rp.progress > 0")
                    .doesNotContain(" AND ");
            case TOUCH -> assertThat(sql).contains("b.added_at AS ts").contains("h.created_at");
            case DEEP -> assertThat(sql).contains("r.created_at AS ts").contains("n.created_at")
                    .contains("s.created_at").contains("n.deleted_at IS NULL");
        }
    }

    @Test
    @DisplayName("DEEP 的 ai_sessions 分支只算图书问答：写侧将来加一种带 book_id 的会话不会静默换人群")
    void deepChatBranchIsScopedToBookChat() {
        String sql = ProfileReaderSql.bookUserSource(Basis.DEEP, null);

        assertThat(occurrences(sql, BookChatScope.sqlFilter("s"))).isEqualTo(1);
        assertThat(sql).contains("FROM ai_sessions s WHERE " + BookChatScope.sqlFilter("s"));
        assertThat(ProfileReaderSql.bookUserSource(Basis.TOUCH, null)).doesNotContain("ai_sessions");
    }

    @Test
    @DisplayName("看板的问答数与画像 DEEP 共用同一份类型定义，不各写一份 IN 列表")
    void dashboardSharesTheSameChatTypeDefinition() throws Exception {
        var field = AdminDashboardService.class.getDeclaredField("CHAT_TYPE_FILTER");
        field.setAccessible(true);

        assertThat(field.get(null)).isEqualTo(BookChatScope.sqlFilter(""));
    }

    @DisplayName("阈值分布查询：一次 GROUP BY 出全部档位，仍然带 APPROVED，档位来自常量")
    void cohortDistributionShape() {
        for (BookProfileService.Basis basis : BookProfileService.Basis.values()) {
            String sql = ProfileReaderSql.cohortDistribution(basis, ThresholdCalibration.COHORT_TIERS);

            assertThat(sql)
                    .as("%s 的分布查询漏掉账号状态过滤，未审核读者会虚增达标书数", basis)
                    .contains("u.status = 'APPROVED'");
            assertThat(sql).contains("GROUP BY x.book_id");
            for (Integer tier : ThresholdCalibration.COHORT_TIERS) {
                assertThat(sql).as("档位 %s 没进分布查询，自适应就只能看到部分档位", tier)
                        .contains("SUM(g.cnt >= " + tier + ") AS t" + tier);
            }
            // 外层是单行聚合：不能有 LIMIT，也不能残留给调用方的命名参数
            assertThat(sql).doesNotContain(":bookId").doesNotContain("LIMIT");
        }
    }

    @Test
    @DisplayName("全时间窗口不拼区间条件，也不留悬空的 :start / :end")
    void allTimeWindowOmitsBindingPlaceholders() {
        for (Basis basis : Basis.values()) {
            assertThat(ProfileReaderSql.readerPool(basis, "")).doesNotContain(":start").doesNotContain(":end");
            assertThat(ProfileReaderSql.bookUserAggregate(basis, "book_id IN (:ids)", ""))
                    .doesNotContain(":start").doesNotContain(":end");
        }
    }
}
