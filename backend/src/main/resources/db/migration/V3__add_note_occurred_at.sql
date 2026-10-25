-- 笔记时光机依赖 notes.occurred_at，但该列一直没建出来：
-- Hibernate 的 `alter table notes add column occurred_at datetime(6) not null`
-- 在有存量行的表上会被 MySQL 严格模式拒绝（Incorrect datetime value: '0000-00-00 00:00:00'），
-- 于是列缺失 → 索引缺失 → /api/notes/timeline 与 /api/notes/stats 全部 500。
-- 这里分三步：先可空补列，回填历史数据，再收紧为 NOT NULL。
ALTER TABLE notes ADD COLUMN occurred_at DATETIME(6) NULL;

UPDATE notes SET occurred_at = COALESCE(created_at, updated_at, NOW(6)) WHERE occurred_at IS NULL;

ALTER TABLE notes MODIFY COLUMN occurred_at DATETIME(6) NOT NULL;

CREATE INDEX idx_note_user_occurred ON notes (user_id, occurred_at);
