-- 图书问答检索依据快照：每条 assistant 回答记录了哪些 chunk。
-- 设计见 docs/plans/book-qa-accuracy-grounding-and-eval-plan.md §3.3。
CREATE TABLE IF NOT EXISTS ai_retrieval_snapshots (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id   BIGINT       NOT NULL,
    book_id           BIGINT       NOT NULL,
    chunk_index       INT          NOT NULL,
    chunk_end_index   INT          DEFAULT NULL,
    score             DOUBLE       DEFAULT NULL,
    rerank_score      DOUBLE       DEFAULT NULL,
    source            VARCHAR(20)  DEFAULT NULL,
    text_hash         CHAR(64)     DEFAULT NULL,
    text_preview      VARCHAR(200) DEFAULT NULL,
    prompt_version    VARCHAR(64)  DEFAULT NULL,
    created_at        DATETIME(6)  DEFAULT NULL,
    updated_at        DATETIME(6)  DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_snapshot_conv (conversation_id),
    KEY idx_snapshot_book_chunk (book_id, chunk_index)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
