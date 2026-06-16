-- ============================================================
-- 修复 debate_scores 表 role_key 字段历史数据
-- 根因：DebateService 调用 scoreSpeechAsync 时把 positionKey 传给了 roleKey 参数
-- 导致 debate_scores.role_key 存的是 PRO_1/CON_1 等位置键，而非性格键
-- ============================================================

-- 1. 先查看受影响的数据量
SELECT
    COUNT(*) AS total_scores,
    SUM(CASE WHEN ds.role_key LIKE 'PRO_%' OR ds.role_key LIKE 'CON_%' OR ds.role_key = 'HOST' THEN 1 ELSE 0 END) AS broken_scores
FROM debate_scores ds;

-- 2. 修复：根据同 session + position_key 的 debate_messages 反查正确的 role_key
-- 原理：debate_messages 表保存了正确的性格键（role_key）和位置键（position_key）
UPDATE debate_scores ds
    INNER JOIN (
        -- 为每个 session + position_key 找到最新的 message 的 role_key
        SELECT
            session_id,
            position_key,
            role_key AS correct_role_key
        FROM (
            SELECT
                session_id,
                position_key,
                role_key,
                ROW_NUMBER() OVER (PARTITION BY session_id, position_key ORDER BY id DESC) AS rn
            FROM debate_messages
            WHERE position_key IS NOT NULL
        ) t
        WHERE t.rn = 1
    ) dm ON ds.session_id = dm.session_id AND ds.position_key = dm.position_key
SET ds.role_key = dm.correct_role_key
WHERE ds.role_key LIKE 'PRO_%'
   OR ds.role_key LIKE 'CON_%'
   OR ds.role_key = 'HOST';

-- 3. 验证修复结果
SELECT
    ds.session_id,
    ds.position_key,
    ds.role_key,
    dm.role_name
FROM debate_scores ds
         LEFT JOIN debate_messages dm
                   ON ds.session_id = dm.session_id
                       AND ds.position_key = dm.position_key
                       AND dm.role_key = ds.role_key
WHERE ds.position_key IS NOT NULL
GROUP BY ds.session_id, ds.position_key, ds.role_key
ORDER BY ds.session_id, ds.position_key;
