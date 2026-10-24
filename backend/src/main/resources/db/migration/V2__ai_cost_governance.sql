-- AI 成本治理扩展：
-- 1) 用量明细补充归因（用户/图书/会话）、质量（延迟/成功/结束原因）与费用快照
-- 2) AI 配置补充输入/输出单价（元 / 百万 tokens）
-- 3) 月预算配置

ALTER TABLE ai_usage_log
    ADD COLUMN user_id      BIGINT       NULL,
    ADD COLUMN book_id      BIGINT       NULL,
    ADD COLUMN session_id   VARCHAR(100) NULL,
    ADD COLUMN latency_ms   INT          NULL,
    ADD COLUMN success      TINYINT(1)   NOT NULL DEFAULT 1,
    ADD COLUMN finish_reason VARCHAR(20) NULL,
    ADD COLUMN error_type   VARCHAR(100) NULL,
    ADD COLUMN input_price  DECIMAL(12,6) NULL,
    ADD COLUMN output_price DECIMAL(12,6) NULL,
    ADD COLUMN cost         DECIMAL(14,6) NOT NULL DEFAULT 0;

ALTER TABLE ai_usage_log
    ADD INDEX idx_usage_user_id (user_id),
    ADD INDEX idx_usage_book_id (book_id);

ALTER TABLE ai_provider_config
    ADD COLUMN input_price  DECIMAL(12,6) NULL,
    ADD COLUMN output_price DECIMAL(12,6) NULL;

CREATE TABLE IF NOT EXISTS ai_budget_config (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    monthly_budget    DECIMAL(14,2) NOT NULL DEFAULT 0,
    enabled           TINYINT(1)   NOT NULL DEFAULT 0,
    alert_percent     INT          NOT NULL DEFAULT 80,
    last_alert_month  VARCHAR(7)   DEFAULT NULL,
    created_at        DATETIME     DEFAULT NULL,
    updated_at        DATETIME     DEFAULT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
