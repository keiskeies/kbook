use sqlx::SqlitePool;
use kbook_core::entity::{AiSession, AiConversation};

pub async fn create_session(
    pool: &SqlitePool,
    user_id: i64,
    session_type: &str,
    book_id: Option<i64>,
    session_id: &str,
    title: Option<&str>,
) -> anyhow::Result<AiSession> {
    sqlx::query(
        "INSERT INTO ai_sessions (user_id, type, book_id, session_id, title) VALUES (?, ?, ?, ?, ?)"
    )
    .bind(user_id)
    .bind(session_type)
    .bind(book_id)
    .bind(session_id)
    .bind(title)
    .execute(pool)
    .await?;

    let session = sqlx::query_as::<_, AiSession>("SELECT * FROM ai_sessions WHERE session_id = ?")
        .bind(session_id)
        .fetch_one(pool)
        .await?;
    Ok(session)
}

pub async fn find_session_by_id(pool: &SqlitePool, session_id: &str) -> anyhow::Result<Option<AiSession>> {
    let session = sqlx::query_as::<_, AiSession>("SELECT * FROM ai_sessions WHERE session_id = ?")
        .bind(session_id)
        .fetch_optional(pool)
        .await?;
    Ok(session)
}

pub async fn list_sessions(
    pool: &SqlitePool,
    user_id: i64,
    session_type: &str,
    book_id: Option<i64>,
) -> anyhow::Result<Vec<AiSession>> {
    let sessions = if let Some(bid) = book_id {
        sqlx::query_as::<_, AiSession>(
            "SELECT * FROM ai_sessions WHERE user_id = ? AND type = ? AND book_id = ? ORDER BY updated_at DESC"
        )
        .bind(user_id)
        .bind(session_type)
        .bind(bid)
        .fetch_all(pool)
        .await?
    } else {
        sqlx::query_as::<_, AiSession>(
            "SELECT * FROM ai_sessions WHERE user_id = ? AND type = ? ORDER BY updated_at DESC"
        )
        .bind(user_id)
        .bind(session_type)
        .fetch_all(pool)
        .await?
    };
    Ok(sessions)
}

pub async fn delete_session(pool: &SqlitePool, session_id: &str) -> anyhow::Result<()> {
    sqlx::query("DELETE FROM ai_conversations WHERE session_id = ?")
        .bind(session_id)
        .execute(pool)
        .await?;
    sqlx::query("DELETE FROM ai_sessions WHERE session_id = ?")
        .bind(session_id)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn insert_conversation(
    pool: &SqlitePool,
    user_id: i64,
    session_id: &str,
    conv_type: Option<&str>,
    book_id: Option<i64>,
    role: &str,
    content: &str,
    thinking_content: Option<&str>,
    token_count: Option<i32>,
) -> anyhow::Result<AiConversation> {
    sqlx::query(
        "INSERT INTO ai_conversations (user_id, session_id, type, book_id, role, content, thinking_content, token_count)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
    )
    .bind(user_id)
    .bind(session_id)
    .bind(conv_type)
    .bind(book_id)
    .bind(role)
    .bind(content)
    .bind(thinking_content)
    .bind(token_count)
    .execute(pool)
    .await?;

    let conv = sqlx::query_as::<_, AiConversation>(
        "SELECT * FROM ai_conversations WHERE user_id = ? AND session_id = ? ORDER BY id DESC LIMIT 1"
    )
    .bind(user_id)
    .bind(session_id)
    .fetch_one(pool)
    .await?;
    Ok(conv)
}

pub async fn get_conversations(
    pool: &SqlitePool,
    user_id: i64,
    session_id: &str,
) -> anyhow::Result<Vec<AiConversation>> {
    let convs = sqlx::query_as::<_, AiConversation>(
        "SELECT * FROM ai_conversations WHERE user_id = ? AND session_id = ? ORDER BY id ASC"
    )
    .bind(user_id)
    .bind(session_id)
    .fetch_all(pool)
    .await?;
    Ok(convs)
}
