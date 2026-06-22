use sqlx::SqlitePool;
use kbook_core::entity::Book;

pub async fn find_by_id(pool: &SqlitePool, id: i64) -> anyhow::Result<Option<Book>> {
    let book = sqlx::query_as::<_, Book>("SELECT * FROM books WHERE id = ?")
        .bind(id)
        .fetch_optional(pool)
        .await?;
    Ok(book)
}

pub async fn search(
    pool: &SqlitePool,
    keyword: Option<&str>,
    format: Option<&str>,
    tag: Option<&str>,
    page: i32,
    size: i32,
) -> anyhow::Result<(Vec<Book>, i64)> {
    let offset = (page - 1) * size;

    if let Some(kw) = keyword {
        if !kw.trim().is_empty() {
            let ids = fts_search(pool, kw.trim(), 1000).await?;

            if ids.is_empty() {
                return Ok((vec![], 0));
            }

            let mut where_parts = vec!["id IN (?)".to_string()];
            let mut binds: Vec<String> = vec![ids.iter().map(|i| i.to_string()).collect::<Vec<_>>().join(",")];

            if let Some(f) = format {
                where_parts.push("format = ?".to_string());
                binds.push(f.to_string());
            }
            if let Some(t) = tag {
                where_parts.push("(concept_tags LIKE ? OR reader_need_tags LIKE ? OR target_reader_tags LIKE ?)".to_string());
                let tg = format!("%{}%", t);
                binds.push(tg.clone());
                binds.push(tg.clone());
                binds.push(tg);
            }

            let total = ids.len() as i64;
            let paged_ids: Vec<i64> = ids.into_iter().skip(offset as usize).take(size as usize).collect();

            if paged_ids.is_empty() {
                return Ok((vec![], total));
            }

            let id_list = paged_ids.iter().map(|i| i.to_string()).collect::<Vec<_>>().join(",");
            let mut query = format!("SELECT * FROM books WHERE id IN ({})", id_list);
            if let Some(f) = format {
                query.push_str(&format!(" AND format = '{}'", f));
            }
            if let Some(t) = tag {
                query.push_str(&format!(" AND (concept_tags LIKE '%{}%' OR reader_need_tags LIKE '%{}%' OR target_reader_tags LIKE '%{}%')", t, t, t));
            }

            let books = sqlx::query_as::<_, Book>(&query)
                .fetch_all(pool)
                .await?;

            return Ok((books, total));
        }
    }

    if let Some(t) = tag {
        let tg = format!("%{}%", t);
        let total: (i64,) = sqlx::query_as(
            "SELECT COUNT(*) FROM books WHERE (concept_tags LIKE ? OR reader_need_tags LIKE ? OR target_reader_tags LIKE ?)"
        ).bind(&tg).bind(&tg).bind(&tg).fetch_one(pool).await?;

        let books = sqlx::query_as::<_, Book>(
            "SELECT * FROM books WHERE (concept_tags LIKE ? OR reader_need_tags LIKE ? OR target_reader_tags LIKE ?) ORDER BY read_count DESC LIMIT ? OFFSET ?"
        ).bind(&tg).bind(&tg).bind(&tg).bind(size).bind(offset).fetch_all(pool).await?;

        return Ok((books, total.0));
    }

    Ok((vec![], 0))
}

async fn fts_search(pool: &SqlitePool, query: &str, limit: i32) -> anyhow::Result<Vec<i64>> {
    let rows = sqlx::query_scalar::<_, i64>(
        "SELECT rowid FROM books_fts WHERE books_fts MATCH ?1 ORDER BY rank LIMIT ?2"
    )
    .bind(query)
    .bind(limit)
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

pub async fn suggest(pool: &SqlitePool, keyword: &str) -> anyhow::Result<Vec<String>> {
    if keyword.trim().is_empty() {
        return Ok(vec![]);
    }

    let ids = fts_search(pool, &format!("{}*", keyword.trim()), 8).await?;

    if ids.is_empty() {
        let like_kw = format!("%{}%", keyword);
        let results: Vec<(String,)> = sqlx::query_as(
            "SELECT title FROM books WHERE title LIKE ? LIMIT 8"
        ).bind(&like_kw).fetch_all(pool).await.unwrap_or_default();
        return Ok(results.into_iter().map(|(t,)| t).collect());
    }

    let id_list = ids.iter().map(|i| i.to_string()).collect::<Vec<_>>().join(",");
    let results: Vec<(String,)> = sqlx::query_as(
        &format!("SELECT title FROM books WHERE id IN ({})", id_list)
    ).fetch_all(pool).await.unwrap_or_default();

    Ok(results.into_iter().map(|(t,)| t).collect())
}

pub async fn get_read_rank(pool: &SqlitePool, page: i32, size: i32) -> anyhow::Result<(Vec<Book>, i64)> {
    let offset = (page - 1) * size;
    let books = sqlx::query_as::<_, Book>(
        "SELECT * FROM books ORDER BY read_count DESC LIMIT ? OFFSET ?"
    )
    .bind(size)
    .bind(offset)
    .fetch_all(pool)
    .await?;
    let total: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM books")
        .fetch_one(pool)
        .await?;
    Ok((books, total.0))
}

pub async fn get_rating_rank(pool: &SqlitePool, page: i32, size: i32) -> anyhow::Result<(Vec<Book>, i64)> {
    let offset = (page - 1) * size;
    let books = sqlx::query_as::<_, Book>(
        "SELECT * FROM books WHERE rating_count > 0 ORDER BY rating DESC LIMIT ? OFFSET ?"
    )
    .bind(size)
    .bind(offset)
    .fetch_all(pool)
    .await?;
    let total: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM books WHERE rating_count > 0")
        .fetch_one(pool)
        .await?;
    Ok((books, total.0))
}

pub async fn get_new_rank(pool: &SqlitePool, page: i32, size: i32) -> anyhow::Result<(Vec<Book>, i64)> {
    let offset = (page - 1) * size;
    let books = sqlx::query_as::<_, Book>(
        "SELECT * FROM books ORDER BY created_at DESC LIMIT ? OFFSET ?"
    )
    .bind(size)
    .bind(offset)
    .fetch_all(pool)
    .await?;
    let total: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM books")
        .fetch_one(pool)
        .await?;
    Ok((books, total.0))
}

pub async fn increment_read_count(pool: &SqlitePool, book_id: i64) -> anyhow::Result<()> {
    sqlx::query("UPDATE books SET read_count = read_count + 1 WHERE id = ?")
        .bind(book_id)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn rate(pool: &SqlitePool, book_id: i64, rating: f64, _user_id: i64) -> anyhow::Result<()> {
    sqlx::query(
        "UPDATE books SET
            rating = (rating * rating_count + ?) / (rating_count + 1),
            rating_count = rating_count + 1,
            updated_at = datetime('now')
         WHERE id = ?"
    )
    .bind(rating)
    .bind(book_id)
    .execute(pool)
    .await?;
    Ok(())
}

pub async fn count(pool: &SqlitePool) -> anyhow::Result<i64> {
    let row: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM books")
        .fetch_one(pool)
        .await?;
    Ok(row.0)
}

pub async fn get_top_by_rating(pool: &SqlitePool, limit: i32) -> anyhow::Result<Vec<Book>> {
    let books = sqlx::query_as::<_, Book>(
        "SELECT * FROM books ORDER BY rating DESC, rating_count DESC LIMIT ?"
    )
    .bind(limit)
    .fetch_all(pool)
    .await?;
    Ok(books)
}

pub async fn find_by_ids(pool: &SqlitePool, ids: &[i64]) -> anyhow::Result<Vec<Book>> {
    if ids.is_empty() {
        return Ok(vec![]);
    }
    let id_list = ids.iter().map(|i| i.to_string()).collect::<Vec<_>>().join(",");
    let books = sqlx::query_as::<_, Book>(
        &format!("SELECT * FROM books WHERE id IN ({})", id_list)
    )
    .fetch_all(pool)
    .await?;
    Ok(books)
}
