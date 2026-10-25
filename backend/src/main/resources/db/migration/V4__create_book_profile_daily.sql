-- 单书画像的每日快照（P3 第三步）。设计见 docs/plans/admin-book-reader-profile.md 附录 AA.3。
-- 保留策略：永久保留，由运维按磁盘归档，因此这里不建任何过期索引、也不设分区。
CREATE TABLE IF NOT EXISTS book_profile_daily (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    book_id          BIGINT       NOT NULL,
    snapshot_date    DATE         NOT NULL,
    basis            VARCHAR(8)   NOT NULL,
    readers          INT          NOT NULL,
    profiled_users   INT          NOT NULL,
    sample_protected TINYINT(1)   NOT NULL,
    payload          LONGTEXT     NULL,
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_profile_daily (book_id, snapshot_date, basis),
    KEY idx_profile_daily_date (snapshot_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
