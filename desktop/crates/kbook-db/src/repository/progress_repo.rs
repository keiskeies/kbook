use sqlx::SqlitePool;
use kbook_core::entity::ReadingProgress;

pub async fn upsert(
    pool: &SqlitePool,
    user_id: i64,
    book_id: i64,
    progress: f64,
    current_position: Option<&str>,
) -> anyhow::Result<ReadingProgress> {
    sqlx::query(
        "INSERT INTO reading_progress (user_id, book_id, progress, current_position, updated_at)
         VALUES (?, ?, ?, ?, datetime('now'))
         ON CONFLICT(user_id, book_id) DO UPDATE SET
            progress = CASE WHEN excluded.progress > reading_progress.progress THEN excluded.progress ELSE reading_progress.progress END,
            current_position = COALESCE(excluded.current_position, reading_progress.current_position),
            updated_at = datetime('now')"
    )
    .bind(user_id)
    .bind(book_id)
    .bind(progress)
    .bind(current_position)
    .execute(pool)
    .await?;

    let rp = sqlx::query_as::<_, ReadingProgress>(
        "SELECT * FROM reading_progress WHERE user_id = ? AND book_id = ?"
    )
    .bind(user_id)
    .bind(book_id)
    .fetch_one(pool)
    .await?;
    Ok(rp)
}

pub async fn get(pool: &SqlitePool, user_id: i64, book_id: i64) -> anyhow::Result<Option<ReadingProgress>> {
    let rp = sqlx::query_as::<_, ReadingProgress>(
        "SELECT * FROM reading_progress WHERE user_id = ? AND book_id = ?"
    )
    .bind(user_id)
    .bind(book_id)
    .fetch_optional(pool)
    .await?;
    Ok(rp)
}

pub async fn get_batch(
    pool: &SqlitePool,
    user_id: i64,
    book_ids: &[i64],
) -> anyhow::Result<Vec<ReadingProgress>> {
    if book_ids.is_empty() {
        return Ok(vec![]);
    }
    let placeholders: Vec<String> = book_ids.iter().map(|_| "?".to_string()).collect();
    let sql = format!(
        "SELECT * FROM reading_progress WHERE user_id = ? AND book_id IN ({})",
        placeholders.join(",")
    );
    let mut query = sqlx::query_as::<_, ReadingProgress>(&sql).bind(user_id);
    for bid in book_ids {
        query = query.bind(bid);
    }
    let rps = query.fetch_all(pool).await?;
    Ok(rps)
}

pub async fn get_recent(
    pool: &SqlitePool,
    user_id: i64,
    limit: i32,
) -> anyhow::Result<Vec<ReadingProgress>> {
    let rps = sqlx::query_as::<_, ReadingProgress>(
        "SELECT * FROM reading_progress WHERE user_id = ? ORDER BY updated_at DESC LIMIT ?"
    )
    .bind(user_id)
    .bind(limit)
    .fetch_all(pool)
    .await?;
    Ok(rps)
}
