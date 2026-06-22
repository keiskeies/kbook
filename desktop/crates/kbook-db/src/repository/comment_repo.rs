use sqlx::SqlitePool;
use kbook_core::entity::Comment;

pub async fn create(
    pool: &SqlitePool,
    user_id: i64,
    book_id: i64,
    chapter_id: Option<&str>,
    parent_id: Option<i64>,
    content: &str,
) -> anyhow::Result<Comment> {
    sqlx::query(
        "INSERT INTO comments (user_id, book_id, chapter_id, parent_id, content) VALUES (?, ?, ?, ?, ?)"
    )
    .bind(user_id)
    .bind(book_id)
    .bind(chapter_id)
    .bind(parent_id)
    .bind(content)
    .execute(pool)
    .await?;

    let comment = sqlx::query_as::<_, Comment>(
        "SELECT * FROM comments WHERE user_id = ? AND book_id = ? ORDER BY id DESC LIMIT 1"
    )
    .bind(user_id)
    .bind(book_id)
    .fetch_one(pool)
    .await?;
    Ok(comment)
}

pub async fn list_by_book(
    pool: &SqlitePool,
    book_id: i64,
    page: i32,
    size: i32,
) -> anyhow::Result<(Vec<Comment>, i64)> {
    let offset = (page - 1) * size;
    let comments = sqlx::query_as::<_, Comment>(
        "SELECT * FROM comments WHERE book_id = ? AND parent_id IS NULL ORDER BY created_at DESC LIMIT ? OFFSET ?"
    )
    .bind(book_id)
    .bind(size)
    .bind(offset)
    .fetch_all(pool)
    .await?;
    let total: (i64,) = sqlx::query_as(
        "SELECT COUNT(*) FROM comments WHERE book_id = ? AND parent_id IS NULL"
    )
    .bind(book_id)
    .fetch_one(pool)
    .await?;
    Ok((comments, total.0))
}

pub async fn list_replies(
    pool: &SqlitePool,
    comment_id: i64,
) -> anyhow::Result<Vec<Comment>> {
    let replies = sqlx::query_as::<_, Comment>(
        "SELECT * FROM comments WHERE parent_id = ? ORDER BY created_at ASC"
    )
    .bind(comment_id)
    .fetch_all(pool)
    .await?;
    Ok(replies)
}

pub async fn delete(pool: &SqlitePool, comment_id: i64, user_id: i64) -> anyhow::Result<()> {
    sqlx::query("DELETE FROM comments WHERE id = ? AND user_id = ?")
        .bind(comment_id)
        .bind(user_id)
        .execute(pool)
        .await?;
    Ok(())
}
