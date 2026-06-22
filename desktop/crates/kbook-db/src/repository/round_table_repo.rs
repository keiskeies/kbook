use sqlx::SqlitePool;
use kbook_core::entity::{RoundTableSession, RoundTableMessage, RoundTableReport};

pub async fn create_session(
    pool: &SqlitePool,
    user_id: i64,
    book_id: i64,
    session_id: &str,
    role_keys: &str,
    role_configs: Option<&str>,
) -> anyhow::Result<RoundTableSession> {
    sqlx::query(
        "INSERT INTO round_table_sessions (user_id, book_id, session_id, role_keys, role_configs) VALUES (?, ?, ?, ?, ?)"
    )
    .bind(user_id)
    .bind(book_id)
    .bind(session_id)
    .bind(role_keys)
    .bind(role_configs)
    .execute(pool)
    .await?;

    let s = sqlx::query_as::<_, RoundTableSession>(
        "SELECT * FROM round_table_sessions WHERE session_id = ?"
    )
    .bind(session_id)
    .fetch_one(pool)
    .await?;
    Ok(s)
}

pub async fn find_session(pool: &SqlitePool, session_id: &str) -> anyhow::Result<Option<RoundTableSession>> {
    let s = sqlx::query_as::<_, RoundTableSession>(
        "SELECT * FROM round_table_sessions WHERE session_id = ?"
    )
    .bind(session_id)
    .fetch_optional(pool)
    .await?;
    Ok(s)
}

pub async fn list_all_sessions(pool: &SqlitePool) -> anyhow::Result<Vec<RoundTableSession>> {
    let sessions = sqlx::query_as::<_, RoundTableSession>(
        "SELECT * FROM round_table_sessions ORDER BY created_at DESC"
    )
    .fetch_all(pool)
    .await?;
    Ok(sessions)
}

pub async fn list_sessions_by_book(pool: &SqlitePool, book_id: i64) -> anyhow::Result<Vec<RoundTableSession>> {
    let sessions = sqlx::query_as::<_, RoundTableSession>(
        "SELECT * FROM round_table_sessions WHERE book_id = ? ORDER BY created_at DESC"
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
    content: &str,
    thinking_content: Option<&str>,
    token_count: Option<i32>,
) -> anyhow::Result<RoundTableMessage> {
    sqlx::query(
        "INSERT INTO round_table_messages (session_id, role_key, role_name, content, thinking_content, token_count)
         VALUES (?, ?, ?, ?, ?, ?)"
    )
    .bind(session_id)
    .bind(role_key)
    .bind(role_name)
    .bind(content)
    .bind(thinking_content)
    .bind(token_count)
    .execute(pool)
    .await?;

    let msg = sqlx::query_as::<_, RoundTableMessage>(
        "SELECT * FROM round_table_messages WHERE session_id = ? ORDER BY id DESC LIMIT 1"
    )
    .bind(session_id)
    .fetch_one(pool)
    .await?;
    Ok(msg)
}

pub async fn get_messages(pool: &SqlitePool, session_id: &str) -> anyhow::Result<Vec<RoundTableMessage>> {
    let msgs = sqlx::query_as::<_, RoundTableMessage>(
        "SELECT * FROM round_table_messages WHERE session_id = ? ORDER BY id ASC"
    )
    .bind(session_id)
    .fetch_all(pool)
    .await?;
    Ok(msgs)
}

pub async fn insert_report(
    pool: &SqlitePool,
    session_id: &str,
    content: &str,
) -> anyhow::Result<RoundTableReport> {
    sqlx::query(
        "INSERT OR REPLACE INTO round_table_reports (session_id, content) VALUES (?, ?)"
    )
    .bind(session_id)
    .bind(content)
    .execute(pool)
    .await?;

    let report = sqlx::query_as::<_, RoundTableReport>(
        "SELECT * FROM round_table_reports WHERE session_id = ?"
    )
    .bind(session_id)
    .fetch_one(pool)
    .await?;
    Ok(report)
}

pub async fn get_report(pool: &SqlitePool, session_id: &str) -> anyhow::Result<Option<RoundTableReport>> {
    let report = sqlx::query_as::<_, RoundTableReport>(
        "SELECT * FROM round_table_reports WHERE session_id = ?"
    )
    .bind(session_id)
    .fetch_optional(pool)
    .await?;
    Ok(report)
}

pub async fn delete_session(pool: &SqlitePool, session_id: &str) -> anyhow::Result<()> {
    sqlx::query("DELETE FROM round_table_messages WHERE session_id = ?").bind(session_id).execute(pool).await?;
    sqlx::query("DELETE FROM round_table_coverages WHERE session_id = ?").bind(session_id).execute(pool).await?;
    sqlx::query("DELETE FROM round_table_reports WHERE session_id = ?").bind(session_id).execute(pool).await?;
    sqlx::query("DELETE FROM round_table_sessions WHERE session_id = ?").bind(session_id).execute(pool).await?;
    Ok(())
}
