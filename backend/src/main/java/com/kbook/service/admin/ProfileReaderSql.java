package com.kbook.service.admin;

import com.kbook.service.ai.BookChatScope;

/**
 * 画像取数的 SQL 形状：只有字符串拼接，不碰 EntityManager，因此可以单测。
 *
 * 抽出来只有一个原因——「谁算读者」此前在这一个服务里写了 7 遍（读者池、榜单分子分母、
 * 分桶命中、维度分布、全站基线、全站分母、已填画像人数）。少写一处 `status = 'APPROVED'`
 * 不会报错，只会让未审核账号混进画像与反查榜，正好是第 4 节最不该发生的那类泄漏。
 * 区间条件与维度取值 SQL 仍由调用方传入：它们各自不同，不是这条口径的一部分。
 */
final class ProfileReaderSql {

    /** 唯一的「谁算读者」定义 */
    private static final String APPROVED = "u.status = 'APPROVED'";

    /** 明细子查询出来后过滤账号状态，三个口径共用同一次 JOIN */
    private static final String APPROVED_JOIN = " JOIN users u ON u.id = x.user_id AND " + APPROVED;

    private ProfileReaderSql() {
    }

    /**
     * 各口径的 (book_id, user_id, ts) 明细。谓词逐分支下发，保证走得到各表的
     * (book_id, 时间列) 与 (user_id, book_id) 索引，而不是物化整个 UNION 后全表过滤。
     * 列名固定来自代码，不接收外部输入。
     */
    static String bookUserSource(BookProfileService.Basis basis, String predicate) {
        String where = predicate == null || predicate.isBlank() ? "" : " AND " + predicate;
        return switch (basis) {
            case READ -> "SELECT rp.book_id AS book_id, rp.user_id AS user_id, rp.updated_at AS ts"
                    + " FROM reading_progress rp WHERE rp.progress > 0" + where;
            case TOUCH -> "SELECT b.book_id AS book_id, b.user_id AS user_id, b.added_at AS ts"
                    + " FROM bookshelf b WHERE 1 = 1" + where
                    + " UNION ALL SELECT h.book_id, h.user_id, h.created_at"
                    + " FROM user_read_history h WHERE 1 = 1" + where;
            case DEEP -> "SELECT r.book_id AS book_id, r.user_id AS user_id, r.created_at AS ts"
                    + " FROM book_reviews r WHERE 1 = 1" + where
                    + " UNION ALL SELECT n.book_id, n.user_id, n.created_at"
                    + " FROM notes n WHERE n.deleted_at IS NULL" + where
                    // 类型过滤是「和这本书聊过天的人」这句话的字面实现：ai_sessions 里只要出现别的
                    // 带 book_id 的会话，DEEP 人群就会静默换人，所以不依赖写入端的习惯
                    + " UNION ALL SELECT s.book_id, s.user_id, s.created_at"
                    + " FROM ai_sessions s WHERE " + BookChatScope.sqlFilter("s") + where;
        };
    }

    /** 读者池：一本书的去重读者 id */
    static String readerPool(BookProfileService.Basis basis, String windowCondition) {
        return "SELECT DISTINCT x.user_id FROM (" + bookUserSource(basis, "book_id = :bookId") + ") x"
                + APPROVED_JOIN
                + " WHERE 1 = 1" + windowCondition;
    }

    /** 榜单分子/分母：按书去重计人 */
    static String bookUserAggregate(BookProfileService.Basis basis, String predicate, String windowCondition) {
        return "SELECT x.book_id AS book_id, COUNT(DISTINCT x.user_id) AS cnt FROM ("
                + bookUserSource(basis, predicate) + ") x"
                + APPROVED_JOIN
                + " WHERE 1 = 1" + windowCondition
                + " GROUP BY x.book_id";
    }

    /** 命中某分桶的读者 id：原始值取回 Java 侧判定，避免归一化规则在 SQL 里再写一遍 */
    static String bucketUserIds(String valueSql) {
        return "SELECT u.id, " + valueSql + " AS bucket FROM users u WHERE " + APPROVED;
    }

    /** 某桶读者在本组 id 上的维度分布 */
    static String countBuckets(String valueSql) {
        return "SELECT " + valueSql + " AS bucket, COUNT(*) AS cnt"
                + " FROM users u WHERE " + APPROVED + " AND u.id IN (:ids)"
                + " GROUP BY bucket";
    }

    /** 全站基线分母 */
    static String siteReaders() {
        return "SELECT COUNT(*) FROM users u WHERE " + APPROVED;
    }

    /** 全站某维度的原始取值分布 */
    static String siteBucket(String valueSql) {
        return "SELECT " + valueSql + " AS bucket, COUNT(*) AS cnt"
                + " FROM users u WHERE " + APPROVED
                + " GROUP BY bucket";
    }

    /** 至少填了一个画像维度的读者数 */
    static String profiledUsers() {
        return "SELECT COUNT(*) FROM users u WHERE " + APPROVED + " AND u.id IN (:ids)"
                + " AND (NULLIF(TRIM(u.gender), '') IS NOT NULL"
                + "   OR NULLIF(TRIM(u.mbti), '') IS NOT NULL"
                + "   OR u.birthday IS NOT NULL"
                + "   OR u.is_married IS NOT NULL"
                + "   OR u.has_children IS NOT NULL"
                + "   OR NULLIF(TRIM(u.children_age_ranges), '') IS NOT NULL"
                + "   OR NULLIF(TRIM(u.occupation), '') IS NOT NULL"
                + "   OR NULLIF(TRIM(u.education), '') IS NOT NULL"
                + "   OR NULLIF(TRIM(u.annual_income), '') IS NOT NULL"
                + "   OR NULLIF(TRIM(u.entrepreneurship), '') IS NOT NULL"
                + "   OR NULLIF(TRIM(u.book_chat_style), '') IS NOT NULL)";
    }
}
