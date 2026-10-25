-- =====================================================================
-- 图书 × 读者画像：上线前阈值校准清单（可执行版）
--
-- 来源：docs/plans/admin-book-reader-profile.md 附录 A.3。原文那段 SQL 要人脑算比例，
-- 本脚本把判定算在 SQL 里，跑完直接给结论，不依赖应用、不改任何数据（纯只读）。
--
-- 口径以代码为准，本脚本只做镜像，不改口径：
--   读者池   -> BookProfileService.bookUserSource / readerPool（含 JOIN users status='APPROVED'）
--   填表率   -> BookProfileDimension.getValueSql（非空口径）
--   脏值判定 -> 故意不在这里做。白名单只有 BookProfileDimension 一份，抄进 SQL 就成了
--              第二份取值表（附录 F 刚拆掉那类隐患）。有效样本与 dirtyCount 请读接口
--              GET /api/admin/dashboard/profile-dimensions。
--
-- 用法：mysql -h<host> -u<user> -p <db> < deploy/sql/profile-threshold-audit.sql
-- =====================================================================

-- 与后端配置保持一致（application.yml 的 kbook.admin.book-profile.*，默认 10/5/10/20）
SET @min_cohort := 10;

SELECT '0) 全局规模' AS step;
SELECT
    (SELECT COUNT(*) FROM users WHERE status = 'APPROVED')            AS approved_users,
    (SELECT COUNT(DISTINCT book_id) FROM reading_progress
       WHERE progress > 0)                                             AS books_with_read_progress,
    (SELECT COUNT(*) FROM books)                                       AS books_total;

-- ---------------------------------------------------------------------
-- 1) 阈值校准：三种读者口径各自的「单书读者数分布」
--    A.3 原文只算了 READ；抽屉默认口径确实是 READ，但 `互动`（DEEP）才是唯一
--    在有书评/笔记/AI 对话数据里出得来分布的那个，所以三口径都要有数。
-- ---------------------------------------------------------------------
SELECT '1a) READ 口径（阅读进度>0）' AS step;
SELECT bucket, COUNT(*) AS books,
       ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1) AS pct_of_books
FROM (
    SELECT CASE WHEN c < 5 THEN '1-4' WHEN c < 10 THEN '5-9' WHEN c < 20 THEN '10-19'
                WHEN c < 50 THEN '20-49' ELSE '>=50' END AS bucket
    FROM (
        SELECT rp.book_id, COUNT(DISTINCT rp.user_id) AS c
        FROM reading_progress rp
        JOIN users u ON u.id = rp.user_id AND u.status = 'APPROVED'
        WHERE rp.progress > 0
        GROUP BY rp.book_id
    ) per_book
) t
GROUP BY bucket
ORDER BY MIN(bucket);

SELECT '1b) TOUCH 口径（书架 ∪ 阅读记录，含想读未读）' AS step;
SELECT bucket, COUNT(*) AS books,
       ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1) AS pct_of_books
FROM (
    SELECT CASE WHEN c < 5 THEN '1-4' WHEN c < 10 THEN '5-9' WHEN c < 20 THEN '10-19'
                WHEN c < 50 THEN '20-49' ELSE '>=50' END AS bucket
    FROM (
        SELECT x.book_id, COUNT(DISTINCT x.user_id) AS c
        FROM (
            SELECT b.book_id, b.user_id FROM bookshelf b
            UNION ALL
            SELECT h.book_id, h.user_id FROM user_read_history h
        ) x
        JOIN users u ON u.id = x.user_id AND u.status = 'APPROVED'
        GROUP BY x.book_id
    ) per_book
) t
GROUP BY bucket
ORDER BY MIN(bucket);

SELECT '1c) DEEP 口径（书评 ∪ 笔记 ∪ 与本书的 AI 对话）' AS step;
SELECT bucket, COUNT(*) AS books,
       ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1) AS pct_of_books
FROM (
    SELECT CASE WHEN c < 5 THEN '1-4' WHEN c < 10 THEN '5-9' WHEN c < 20 THEN '10-19'
                WHEN c < 50 THEN '20-49' ELSE '>=50' END AS bucket
    FROM (
        SELECT x.book_id, COUNT(DISTINCT x.user_id) AS c
        FROM (
            SELECT r.book_id, r.user_id FROM book_reviews r
            UNION ALL
            SELECT n.book_id, n.user_id FROM notes n WHERE n.deleted_at IS NULL
            UNION ALL
            SELECT s.book_id, s.user_id FROM ai_sessions s
        ) x
        JOIN users u ON u.id = x.user_id AND u.status = 'APPROVED'
        WHERE x.book_id IS NOT NULL
        GROUP BY x.book_id
    ) per_book
) t
GROUP BY bucket
ORDER BY MIN(bucket);

-- 判定：达到 min-cohort 的书占比 <20% -> 先做人群选书（profile-books），单书画像按兜底形态上线
SELECT '1d) 判定（对应 A.3 判定规则第一条）' AS step;
SELECT basis,
       books_with_readers,
       books_over_min_cohort,
       ROUND(books_over_min_cohort * 100.0 / NULLIF(books_with_readers, 0), 2) AS pct_over_min_cohort,
       CASE WHEN books_over_min_cohort * 100.0 / NULLIF(books_with_readers, 0) < 20
            THEN '低于 20%：先做人群选书，单书画像会大面积走保护态'
            ELSE '可以主推单书画像' END AS verdict
FROM (
    SELECT 'READ' AS basis,
           COUNT(*) AS books_with_readers,
           SUM(c >= @min_cohort) AS books_over_min_cohort
    FROM (
        SELECT rp.book_id, COUNT(DISTINCT rp.user_id) AS c
        FROM reading_progress rp
        JOIN users u ON u.id = rp.user_id AND u.status = 'APPROVED'
        WHERE rp.progress > 0
        GROUP BY rp.book_id
    ) a
    UNION ALL
    SELECT 'TOUCH', COUNT(*), SUM(c >= @min_cohort)
    FROM (
        SELECT x.book_id, COUNT(DISTINCT x.user_id) AS c
        FROM (
            SELECT b.book_id, b.user_id FROM bookshelf b
            UNION ALL
            SELECT h.book_id, h.user_id FROM user_read_history h
        ) x
        JOIN users u ON u.id = x.user_id AND u.status = 'APPROVED'
        GROUP BY x.book_id
    ) b
    UNION ALL
    SELECT 'DEEP', COUNT(*), SUM(c >= @min_cohort)
    FROM (
        SELECT x.book_id, COUNT(DISTINCT x.user_id) AS c
        FROM (
            SELECT r.book_id, r.user_id FROM book_reviews r
            UNION ALL
            SELECT n.book_id, n.user_id FROM notes n WHERE n.deleted_at IS NULL
            UNION ALL
            SELECT s.book_id, s.user_id FROM ai_sessions s
        ) x
        JOIN users u ON u.id = x.user_id AND u.status = 'APPROVED'
        WHERE x.book_id IS NOT NULL
        GROUP BY x.book_id
    ) c
) v
ORDER BY basis;

-- ---------------------------------------------------------------------
-- 1e) 阈值扫描：同一份分布下，3/5/10/20 各档能覆盖多少书（生产上直接据此选档）。
--     三口径的取数形状与 1a-1c 相同；DEEP 的会话类型过滤与 BookChatScope 保持一致，改动时两处一起改。
SELECT t.basis, k.min_cohort, COUNT(*) AS books,
       SUM(t.c >= k.min_cohort) AS books_over,
       ROUND(SUM(t.c >= k.min_cohort) * 100.0 / COUNT(*), 2) AS pct_over
FROM (
  SELECT 'READ' AS basis, rp.book_id AS book_id, COUNT(DISTINCT rp.user_id) AS c
  FROM reading_progress rp JOIN users u ON u.id = rp.user_id AND u.status = 'APPROVED'
  WHERE rp.progress > 0 GROUP BY rp.book_id
  UNION ALL
  SELECT 'TOUCH', x.book_id, COUNT(DISTINCT x.user_id)
  FROM (SELECT book_id, user_id FROM bookshelf
        UNION ALL SELECT book_id, user_id FROM user_read_history) x
  JOIN users u ON u.id = x.user_id AND u.status = 'APPROVED'
  GROUP BY x.book_id
  UNION ALL
  SELECT 'DEEP', x.book_id, COUNT(DISTINCT x.user_id)
  FROM (SELECT book_id, user_id FROM book_reviews
        UNION ALL SELECT book_id, user_id FROM notes WHERE deleted_at IS NULL
        UNION ALL SELECT book_id, user_id FROM ai_sessions
                 WHERE book_id IS NOT NULL AND type IN ('book_chat', 'BOOK')) x
  JOIN users u ON u.id = x.user_id AND u.status = 'APPROVED'
  GROUP BY x.book_id
) t
JOIN (SELECT 3 AS min_cohort UNION ALL SELECT 5 UNION ALL SELECT 10 UNION ALL SELECT 20) k
GROUP BY t.basis, k.min_cohort
ORDER BY t.basis, k.min_cohort;

-- 2) 画像非空率（填表率）。<30 人这条判定交给接口更准：这里是不去脏的「非空」，
--    有效样本要扣掉白名单之外的值，看 GET /api/admin/dashboard/profile-dimensions
--    的 coverage / unknownCount / dirtyCount 三个数。
-- ---------------------------------------------------------------------
SELECT '2) 各维度非空人数（分母=审核通过用户）' AS step;
SELECT d.dim_key, d.label, d.non_null_users,
       ROUND(d.non_null_users * 100.0 / NULLIF(t.approved, 0), 1) AS pct_filled,
       CASE WHEN d.non_null_users < 30 THEN '样本 <30，分布只能当弱证据' ELSE '' END AS note
FROM (
    SELECT 'GENDER' dim_key, '性别' label,
           COUNT(NULLIF(TRIM(gender), '')) non_null_users FROM users WHERE status = 'APPROVED'
    UNION ALL SELECT 'AGE', '年龄段', COUNT(birthday) FROM users WHERE status = 'APPROVED'
    UNION ALL SELECT 'MBTI_TYPE', 'MBTI 类型', COUNT(NULLIF(TRIM(mbti), '')) FROM users WHERE status = 'APPROVED'
    UNION ALL SELECT 'MARITAL', '婚姻状况', COUNT(is_married) FROM users WHERE status = 'APPROVED'
    UNION ALL SELECT 'CHILDREN', '是否有孩子', COUNT(has_children) FROM users WHERE status = 'APPROVED'
    UNION ALL SELECT 'CHILD_AGE', '孩子年龄段', COUNT(NULLIF(TRIM(children_age_ranges), '')) FROM users WHERE status = 'APPROVED'
    UNION ALL SELECT 'OCCUPATION', '职业分类', COUNT(NULLIF(TRIM(occupation), '')) FROM users WHERE status = 'APPROVED'
    UNION ALL SELECT 'EDUCATION', '学历（当前/目标）', COUNT(NULLIF(TRIM(education), '')) FROM users WHERE status = 'APPROVED'
    UNION ALL SELECT 'INCOME', '年收入（当前/期望）', COUNT(NULLIF(TRIM(annual_income), '')) FROM users WHERE status = 'APPROVED'
    UNION ALL SELECT 'ENTREPRENEURSHIP', '创业意向', COUNT(NULLIF(TRIM(entrepreneurship), '')) FROM users WHERE status = 'APPROVED'
    UNION ALL SELECT 'CHAT_STYLE', '共读风格', COUNT(NULLIF(TRIM(book_chat_style), '')) FROM users WHERE status = 'APPROVED'
) d
CROSS JOIN (SELECT COUNT(*) approved FROM users WHERE status = 'APPROVED') t
ORDER BY d.dim_key;

-- ---------------------------------------------------------------------
-- 3) 脏值体检：把可能不受控的列按取值列全，人工与白名单对照。
--    出现任何非白名单值 -> 先按实测值补归一化（BookProfileDimension），再开工 P3。
--
--    必须分 scope 看：看板只统计 APPROVED，非审核账号里的脏值不影响分布，但影响
--    「历史脏值要不要清洗」那条决策。A.3 原文没带 status 条件，两条口径会被混成
--    一个数——dev 实测就是这样：脏值全在 status<>'APPROVED' 的行里，只按 A.3 跑会
--    高估看板风险，只按 APPROVED 过滤又会漏掉清洗范围。
-- ---------------------------------------------------------------------
SELECT '3) 原始取值分布（按 scope·人数降序）' AS step;
SELECT dim_key, scope, value, COUNT(*) users
FROM (
    SELECT 'EDUCATION' dim_key,
           CASE WHEN status = 'APPROVED' THEN 'APPROVED' ELSE '非审核通过' END scope,
           education value
    FROM users WHERE education IS NOT NULL AND TRIM(education) <> ''
    UNION ALL SELECT 'INCOME',
           CASE WHEN status = 'APPROVED' THEN 'APPROVED' ELSE '非审核通过' END, annual_income
    FROM users WHERE annual_income IS NOT NULL AND TRIM(annual_income) <> ''
    UNION ALL SELECT 'ENTREPRENEURSHIP',
           CASE WHEN status = 'APPROVED' THEN 'APPROVED' ELSE '非审核通过' END, entrepreneurship
    FROM users WHERE entrepreneurship IS NOT NULL AND TRIM(entrepreneurship) <> ''
    UNION ALL SELECT 'GENDER',
           CASE WHEN status = 'APPROVED' THEN 'APPROVED' ELSE '非审核通过' END, gender
    FROM users WHERE gender IS NOT NULL AND TRIM(gender) <> ''
    UNION ALL SELECT 'MBTI',
           CASE WHEN status = 'APPROVED' THEN 'APPROVED' ELSE '非审核通过' END, mbti
    FROM users WHERE mbti IS NOT NULL AND TRIM(mbti) <> ''
    UNION ALL SELECT 'OCCUPATION',
           CASE WHEN status = 'APPROVED' THEN 'APPROVED' ELSE '非审核通过' END, occupation
    FROM users WHERE occupation IS NOT NULL AND TRIM(occupation) <> ''
    UNION ALL SELECT 'CHILD_AGE',
           CASE WHEN status = 'APPROVED' THEN 'APPROVED' ELSE '非审核通过' END, children_age_ranges
    FROM users WHERE children_age_ranges IS NOT NULL AND TRIM(children_age_ranges) <> ''
    UNION ALL SELECT 'CHAT_STYLE',
           CASE WHEN status = 'APPROVED' THEN 'APPROVED' ELSE '非审核通过' END, book_chat_style
    FROM users WHERE book_chat_style IS NOT NULL AND TRIM(book_chat_style) <> ''
) raw
GROUP BY dim_key, scope, value
ORDER BY dim_key, scope, users DESC;

-- 3b) 年龄列单独看：生日是脏数据重灾区（未来日期、0000-00-00），归一化按 -1 当脏值处理
SELECT '3b) 生日异常值' AS step;
SELECT CASE WHEN birthday IS NULL THEN 'NULL'
            WHEN birthday > CURDATE() THEN '未来日期（算脏值）'
            WHEN TIMESTAMPDIFF(YEAR, birthday, CURDATE()) < 13 THEN '13 岁以下'
            WHEN TIMESTAMPDIFF(YEAR, birthday, CURDATE()) > 90 THEN '90 岁以上'
            ELSE '正常' END AS kind,
       COUNT(*) users
FROM users WHERE status = 'APPROVED'
GROUP BY kind
ORDER BY users DESC;

-- 4) DEEP 口径前置检查（附录 M）：带 book_id 的会话是否只有图书问答类型。
--    画像的 DEEP 分支现在按 BookChatScope 过滤（book_chat / BOOK）。这里若是 0 行，
--    说明过滤与不过滤等价；若出现别的 type，就是有人在助理/管理端会话上也写了 book_id，
--    「深度互动读者」会自动排除他们——需要重新拍板的是口径本身，不是要不要过滤。
SELECT step, type, rows_cnt, users_cnt FROM (
  SELECT '4a) 带 book_id 的会话按类型' AS step, COALESCE(type, '(NULL)') AS type,
         COUNT(*) AS rows_cnt, COUNT(DISTINCT user_id) AS users_cnt
  FROM ai_sessions WHERE book_id IS NOT NULL GROUP BY type
  UNION ALL
  SELECT '4b) 其中不在 BookChatScope 内', '(合计)', COUNT(*), COUNT(DISTINCT user_id)
  FROM ai_sessions WHERE book_id IS NOT NULL AND type NOT IN ('book_chat', 'BOOK')
) probe ORDER BY step, rows_cnt DESC;
