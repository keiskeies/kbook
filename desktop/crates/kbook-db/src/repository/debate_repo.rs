use sqlx::SqlitePool;
use kbook_core::entity::{DebateSession, DebateMessage, DebateScore, DebateReport};

pub async fn create_session(
    pool: &SqlitePool,
    user_id: i64,
    book_id: i64,
    session_id: &str,
    topic: &str,
    topic_source: Option<&str>,
    pro_role_keys: Option<&str>,
    con_role_keys: Option<&str>,
) -> anyhow::Result<DebateSession> {
    sqlx::query(
        "INSERT INTO debate_sessions (user_id, book_id, session_id, topic, topic_source, pro_role_keys, con_role_keys)
         VALUES (?, ?, ?, ?, ?, ?, ?)"
    )
    .bind(user_id)
    .bind(book_id)
    .bind(session_id)
    .bind(topic)
    .bind(topic_source)
    .bind(pro_role_keys)
    .bind(con_role_keys)
    .execute(pool)
    .await?;

    let session = sqlx::query_as::<_, DebateSession>(
        "SELECT * FROM debate_sessions WHERE session_id = ?"
    )
    .bind(session_id)
    .fetch_one(pool)
    .await?;
    Ok(session)
}

pub async fn find_session(pool: &SqlitePool, session_id: &str) -> anyhow::Result<Option<DebateSession>> {
    let s = sqlx::query_as::<_, DebateSession>(
        "SELECT * FROM debate_sessions WHERE session_id = ?"
    )
    .bind(session_id)
    .fetch_optional(pool)
    .await?;
    Ok(s)
}

pub async fn list_all_sessions(pool: &SqlitePool) -> anyhow::Result<Vec<DebateSession>> {
    let sessions = sqlx::query_as::<_, DebateSession>(
        "SELECT * FROM debate_sessions ORDER BY created_at DESC"
    )
    .fetch_all(pool)
    .await?;
    Ok(sessions)
}

pub async fn list_sessions_by_book(pool: &SqlitePool, book_id: i64) -> anyhow::Result<Vec<DebateSession>> {
    let sessions = sqlx::query_as::<_, DebateSession>(
        "SELECT * FROM debate_sessions WHERE book_id = ? ORDER BY created_at DESC"
    )
    .bind(book_id)
    .fetch_all(pool)
    .await?;
    Ok(sessions)
}

pub async fn insert_message(
    pool: &SqlitePool,
    session_id: &str,
    role_key: &str,
    role_name: &str,
    side: &str,
    round_type: &str,
    round_number: i32,
    content: &str,
    thinking_content: Option<&str>,
    token_count: Option<i32>,
) -> anyhow::Result<DebateMessage> {
    sqlx::query(
        "INSERT INTO debate_messages (session_id, role_key, role_name, side, round_type, round_number, content, thinking_content, token_count)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
    )
    .bind(session_id)
    .bind(role_key)
    .bind(role_name)
    .bind(side)
    .bind(round_type)
    .bind(round_number)
    .bind(content)
    .bind(thinking_content)
    .bind(token_count)
    .execute(pool)
    .await?;

    let msg = sqlx::query_as::<_, DebateMessage>(
        "SELECT * FROM debate_messages WHERE session_id = ? ORDER BY id DESC LIMIT 1"
    )
    .bind(session_id)
    .fetch_one(pool)
    .await?;
    Ok(msg)
}

pub async fn get_messages(pool: &SqlitePool, session_id: &str) -> anyhow::Result<Vec<DebateMessage>> {
    let msgs = sqlx::query_as::<_, DebateMessage>(
        "SELECT * FROM debate_messages WHERE session_id = ? ORDER BY id ASC"
    )
    .bind(session_id)
    .fetch_all(pool)
    .await?;
    Ok(msgs)
}

pub async fn advance_round(pool: &SqlitePool, session_id: &str) -> anyhow::Result<()> {
    sqlx::query(
        "UPDATE debate_sessions SET current_round = current_round + 1, updated_at = datetime('now') WHERE session_id = ?"
    )
    .bind(session_id)
    .execute(pool)
    .await?;
    Ok(())
}

pub async fn get_scores(pool: &SqlitePool, session_id: &str) -> anyhow::Result<Vec<DebateScore>> {
    let scores = sqlx::query_as::<_, DebateScore>(
        "SELECT * FROM debate_scores WHERE session_id = ? ORDER BY id ASC"
    )
    .bind(session_id)
    .fetch_all(pool)
    .await?;
    Ok(scores)
}

pub async fn insert_report(
    pool: &SqlitePool,
    session_id: &str,
    content: &str,
) -> anyhow::Result<DebateReport> {
    sqlx::query(
        "INSERT OR REPLACE INTO debate_reports (session_id, content) VALUES (?, ?)"
    )
    .bind(session_id)
    .bind(content)
    .execute(pool)
    .await?;

    let report = sqlx::query_as::<_, DebateReport>(
        "SELECT * FROM debate_reports WHERE session_id = ?"
    )
    .bind(session_id)
    .fetch_one(pool)
    .await?;
    Ok(report)
}

pub async fn get_report(pool: &SqlitePool, session_id: &str) -> anyhow::Result<Option<DebateReport>> {
    let report = sqlx::query_as::<_, DebateReport>(
        "SELECT * FROM debate_reports WHERE session_id = ?"
    )
    .bind(session_id)
    .fetch_optional(pool)
    .await?;
    Ok(report)
}

pub async fn delete_session(pool: &SqlitePool, session_id: &str) -> anyhow::Result<()> {
    sqlx::query("DELETE FROM debate_messages WHERE session_id = ?").bind(session_id).execute(pool).await?;
    sqlx::query("DELETE FROM debate_scores WHERE session_id = ?").bind(session_id).execute(pool).await?;
    sqlx::query("DELETE FROM debate_reports WHERE session_id = ?").bind(session_id).execute(pool).await?;
    sqlx::query("DELETE FROM debate_sessions WHERE session_id = ?").bind(session_id).execute(pool).await?;
    Ok(())
}
