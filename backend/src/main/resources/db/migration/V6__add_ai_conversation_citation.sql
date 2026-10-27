-- 图书问答引用校验结果：合法/非法片段编号计数。
-- 设计见 docs/plans/book-qa-accuracy-grounding-and-eval-plan.md §3.5。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS；用 information_schema 做幂等守卫，
-- 避免开发库已被 ddl-auto 加过列时迁移失败。
SET @add_citation_sql = (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'ai_conversations'
           AND COLUMN_NAME = 'citation_json') = 0,
        'ALTER TABLE ai_conversations ADD COLUMN citation_json TEXT NULL',
        'SELECT 1'
    )
);
PREPARE add_citation_stmt FROM @add_citation_sql;
EXECUTE add_citation_stmt;
DEALLOCATE PREPARE add_citation_stmt;
