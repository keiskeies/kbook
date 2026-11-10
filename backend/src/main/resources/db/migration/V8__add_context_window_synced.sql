-- 模型上下文长度校准标记（见 docs/plans/book-qa-scale-aware-routing-plan.md P0-3）。
-- 历史种子把 ai_provider_config.max_tokens 固定写为 32768，与 ai-providers.yml 的
-- 128K/1M 不符，导致按「上下文 − 历史 − 输出」判定的分档阈值分子失真。
-- 启动时由 AiModelContextLengthResolver 对 synced != true 且仍为旧默认的行校准一次；
-- 本列只是「是否已校准」的标记，不存放真实窗口值，管理员可随时手工改 max_tokens。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS；用 information_schema 幂等守卫，
-- 兼容开发库已被 ddl-auto:update 抢先加列的情况（同 V7 的做法）。
SET @add_ctx_sync_sql = (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'ai_provider_config'
           AND COLUMN_NAME = 'context_window_synced') = 0,
        'ALTER TABLE ai_provider_config ADD COLUMN context_window_synced TINYINT(1) NULL DEFAULT 0',
        'SELECT 1'
    )
);
PREPARE add_ctx_sync_stmt FROM @add_ctx_sync_sql;
EXECUTE add_ctx_sync_stmt;
DEALLOCATE PREPARE add_ctx_sync_stmt;
