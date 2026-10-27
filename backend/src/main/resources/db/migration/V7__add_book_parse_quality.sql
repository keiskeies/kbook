-- 解析/入库质量报告：字符数、乱码率、章节覆盖率、被拒 chunk 数与原因分布（JSON）。
-- 设计见 docs/plans/rag-ingestion-and-chunking-plan.md §4.1；阶段 A 验收项「解析报告可查」依赖此列。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS；用 information_schema 做幂等守卫，
-- 避免开发库已被 ddl-auto 加过列时迁移失败。
SET @add_parse_quality_sql = (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'books'
           AND COLUMN_NAME = 'parse_quality') = 0,
        'ALTER TABLE books ADD COLUMN parse_quality TEXT NULL',
        'SELECT 1'
    )
);
PREPARE add_parse_quality_stmt FROM @add_parse_quality_sql;
EXECUTE add_parse_quality_stmt;
DEALLOCATE PREPARE add_parse_quality_stmt;
