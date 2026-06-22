use sqlx::SqlitePool;

pub async fn get_coefficients(
    pool: &SqlitePool,
    user_id: i64,
) -> anyhow::Result<Vec<(String, f64)>> {
    let rows = sqlx::query_as::<_, (String, f64)>(
        "SELECT dimension, coefficient FROM recommend_coefficients WHERE user_id = ?"
    )
    .bind(user_id)
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

pub async fn upsert_coefficient(
    pool: &SqlitePool,
    user_id: i64,
    dimension: &str,
    coefficient: f64,
) -> anyhow::Result<()> {
    sqlx::query(
        "INSERT INTO recommend_coefficients (user_id, dimension, coefficient, updated_at)
         VALUES (?, ?, ?, datetime('now'))
         ON CONFLICT(user_id, dimension) DO UPDATE SET coefficient = excluded.coefficient, updated_at = datetime('now')"
    )
    .bind(user_id)
    .bind(dimension)
    .bind(coefficient)
    .execute(pool)
    .await?;
    Ok(())
}

pub async fn record_feedback(
    pool: &SqlitePool,
    user_id: i64,
    book_id: i64,
    event_type: &str,
) -> anyhow::Result<()> {
    sqlx::query(
        "INSERT INTO recommend_feedback_events (user_id, book_id, event_type) VALUES (?, ?, ?)"
    )
    .bind(user_id)
    .bind(book_id)
    .bind(event_type)
    .execute(pool)
    .await?;
    Ok(())
}
