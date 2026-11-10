-- 图书问答「有用 / 没用」反馈与待标注队列（方案 P2-5；设计见
-- docs/plans/book-qa-accuracy-grounding-and-eval-plan.md §4.5）。
-- 差评（DOWN）自动置为 QUEUED 进入标注队列，标注后进黄金集，形成迭代闭环。
-- 只加表，不改既有列，可安全回滚；用 IF NOT EXISTS 兼容开发库 ddl-auto:update 抢先建表。
CREATE TABLE IF NOT EXISTS answer_feedback (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    book_id         BIGINT       NOT NULL,
    conversation_id BIGINT       NOT NULL,
    session_id      VARCHAR(100) NULL,
    rating          VARCHAR(10)  NOT NULL,
    reason          VARCHAR(40)  NULL,
    comment         VARCHAR(500) NULL,
    context_mode    VARCHAR(16)  NULL,
    review_status   VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    review_note     VARCHAR(500) NULL,
    created_at      DATETIME(6)  NULL,
    updated_at      DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_answer_feedback_conv_user (conversation_id, user_id),
    KEY idx_answer_feedback_book (book_id),
    KEY idx_answer_feedback_user (user_id),
    KEY idx_answer_feedback_queue (review_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
