-- 章节结构缓存（见 docs/plans/rag-ingestion-and-chunking-plan.md §4.1「books.structure_json」）。
-- 按书规模分档的 MEDIUM 档需要按章取回原文；此前每轮都要对全书重跑 TextStructureExtractor，
-- 百万字书籍上这是不必要的 CPU 开销。索引时算一次、持久化即可。
-- 幂等守卫同 V7/V8：开发库可能已被 ddl-auto:update 抢先加列。
SET @add_structure_sql = (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'books'
           AND COLUMN_NAME = 'structure_json') = 0,
        'ALTER TABLE books ADD COLUMN structure_json TEXT NULL',
        'SELECT 1'
    )
);
PREPARE add_structure_stmt FROM @add_structure_sql;
EXECUTE add_structure_stmt;
DEALLOCATE PREPARE add_structure_stmt;
