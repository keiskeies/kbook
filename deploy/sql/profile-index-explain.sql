-- 画像索引 EXPLAIN 复跑（配合 profile-threshold-audit.sql 一起在生产执行）
--
-- 用法：把下面的 @BOOK 改成生产里一本"有读者"的书 id（README：A.3 的 1d 输出里能挑到），
-- 然后：
--   deploy/sql/run-audit.bat deploy/sql/profile-index-explain.sql        (Windows)
--   deploy/sql/run-audit.sh  deploy/sql/profile-index-explain.sql        (Linux/Mac)
--
-- 判定（附录 AC）：按书查的三条应为 type=ref/range 且 key 是本功能新增的索引。
-- bookshelf 在数据量小时可能被优化器选成全表扫，这是正常的；生产行数下再确认一次即可。
-- 纯只读，只有 EXPLAIN。

SET @BOOK := 24715;

SELECT '1) READ 读者池（按书查，应命中 idx_progress_book_updated）' AS step;
EXPLAIN SELECT DISTINCT x.user_id
FROM (SELECT rp.book_id AS book_id, rp.user_id AS user_id, rp.updated_at AS ts
      FROM reading_progress rp
      WHERE rp.progress > 0 AND rp.book_id = @BOOK) x;

SELECT '2) DEEP·notes 分支（应命中 idx_note_book_created）' AS step;
EXPLAIN SELECT n.book_id, n.user_id, n.created_at
FROM notes n
WHERE n.deleted_at IS NULL AND n.book_id = @BOOK;

SELECT '3) TOUCH 读者池（书架与阅读记录两分支，应按书查命中各自索引）' AS step;
EXPLAIN SELECT DISTINCT x.user_id
FROM (SELECT b.book_id, b.user_id, b.added_at AS ts
      FROM bookshelf b WHERE 1 = 1 AND b.book_id = @BOOK
      UNION ALL
      SELECT h.book_id, h.user_id, h.created_at
      FROM user_read_history h WHERE 1 = 1 AND h.book_id = @BOOK) x;
