-- AI 调用用量日志：管理后台成本监控的数据源
-- 每次真实 LLM API 调用一行，含工具类调用（查询扩展/压缩/圆桌/辩论等）
CREATE TABLE IF NOT EXISTS ai_usage_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    scene         VARCHAR(50)  NOT NULL,
    model_name    VARCHAR(100) DEFAULT NULL,
    config_name   VARCHAR(100) DEFAULT NULL,
    input_tokens  INT          NOT NULL DEFAULT 0,
    output_tokens INT          NOT NULL DEFAULT 0,
    total_tokens  INT          NOT NULL DEFAULT 0,
    call_type     VARCHAR(20)  DEFAULT NULL,
    created_at    DATETIME     DEFAULT NULL,
    updated_at    DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_usage_created_at (created_at),
    KEY idx_usage_scene (scene)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
