use sqlx::SqlitePool;
use serde::{Deserialize, Serialize};
use crate::embedding::EmbeddingClient;

// ── Row types ──────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
#[serde(rename_all = "camelCase")]
pub struct BookVectorRow {
    pub book_id: i64,
    pub vector: String,       // JSON array of f32
    pub vector_type: String,  // "metadata" | "content"
    pub updated_at: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
#[serde(rename_all = "camelCase")]
pub struct ContentVectorRow {
    pub id: i64,
    pub book_id: i64,
    pub chunk_index: i64,
    pub chunk_text: String,
    pub vector: String,       // JSON array of f32
    pub updated_at: Option<String>,
}

// ── Table creation ─────────────────────────────────────────────────────

pub async fn init_tables(pool: &SqlitePool) -> anyhow::Result<()> {
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS book_vectors (
            book_id   INTEGER PRIMARY KEY,
            vector    TEXT NOT NULL,
            vector_type TEXT NOT NULL DEFAULT 'metadata',
            updated_at TEXT
        )"
    ).execute(pool).await?;

    sqlx::query(
        "CREATE TABLE IF NOT EXISTS book_content_vectors (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            book_id     INTEGER NOT NULL,
            chunk_index INTEGER NOT NULL,
            chunk_text  TEXT NOT NULL,
            vector      TEXT NOT NULL,
            updated_at  TEXT,
            UNIQUE(book_id, chunk_index)
        )"
    ).execute(pool).await?;

    Ok(())
}

// ── Cosine similarity (in-memory) ─────────────────────────────────────

fn cosine_similarity(a: &[f32], b: &[f32]) -> f64 {
    if a.len() != b.len() || a.is_empty() {
        return 0.0;
    }
    let dot: f64 = a.iter().zip(b.iter()).map(|(x, y)| (*x as f64) * (*y as f64)).sum();
    let norm_a: f64 = a.iter().map(|x| (*x as f64).powi(2)).sum::<f64>().sqrt();
    let norm_b: f64 = b.iter().map(|x| (*x as f64).powi(2)).sum::<f64>().sqrt();
    if norm_a == 0.0 || norm_b == 0.0 {
        return 0.0;
    }
    dot / (norm_a * norm_b)
}

fn parse_vector(json: &str) -> anyhow::Result<Vec<f32>> {
    Ok(serde_json::from_str::<Vec<f32>>(json)?)
}

// ── Book metadata vector CRUD ──────────────────────────────────────────

pub async fn upsert_book_vector(
    pool: &SqlitePool,
    book_id: i64,
    vector: &[f32],
    vector_type: &str,
) -> anyhow::Result<()> {
    let json = serde_json::to_string(vector)?;
    sqlx::query(
        "INSERT INTO book_vectors (book_id, vector, vector_type, updated_at)
         VALUES (?, ?, ?, datetime('now'))
         ON CONFLICT(book_id) DO UPDATE SET
            vector = excluded.vector,
            vector_type = excluded.vector_type,
            updated_at = excluded.updated_at"
    )
    .bind(book_id)
    .bind(&json)
    .bind(vector_type)
    .execute(pool)
    .await?;
    Ok(())
}

pub async fn delete_book_vector(pool: &SqlitePool, book_id: i64) -> anyhow::Result<()> {
    sqlx::query("DELETE FROM book_vectors WHERE book_id = ?")
        .bind(book_id)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn get_book_vector(pool: &SqlitePool, book_id: i64) -> anyhow::Result<Option<BookVectorRow>> {
    let row = sqlx::query_as::<_, BookVectorRow>(
        "SELECT book_id, vector, vector_type, updated_at FROM book_vectors WHERE book_id = ?"
    )
    .bind(book_id)
    .fetch_optional(pool)
    .await?;
    Ok(row)
}

// ── Book content vector CRUD ───────────────────────────────────────────

pub async fn upsert_content_vector(
    pool: &SqlitePool,
    book_id: i64,
    chunk_index: i64,
    chunk_text: &str,
    vector: &[f32],
) -> anyhow::Result<()> {
    let json = serde_json::to_string(vector)?;
    sqlx::query(
        "INSERT INTO book_content_vectors (book_id, chunk_index, chunk_text, vector, updated_at)
         VALUES (?, ?, ?, ?, datetime('now'))
         ON CONFLICT(book_id, chunk_index) DO UPDATE SET
            chunk_text = excluded.chunk_text,
            vector = excluded.vector,
            updated_at = excluded.updated_at"
    )
    .bind(book_id)
    .bind(chunk_index)
    .bind(chunk_text)
    .bind(&json)
    .execute(pool)
    .await?;
    Ok(())
}

pub async fn delete_content_vectors(pool: &SqlitePool, book_id: i64) -> anyhow::Result<()> {
    sqlx::query("DELETE FROM book_content_vectors WHERE book_id = ?")
        .bind(book_id)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn get_content_vectors(pool: &SqlitePool, book_id: i64) -> anyhow::Result<Vec<ContentVectorRow>> {
    let rows = sqlx::query_as::<_, ContentVectorRow>(
        "SELECT id, book_id, chunk_index, chunk_text, vector, updated_at
         FROM book_content_vectors WHERE book_id = ?
         ORDER BY chunk_index"
    )
    .bind(book_id)
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

// ── Vector search ──────────────────────────────────────────────────────

/// Search similar books by cosine similarity.
/// Returns (book_id, similarity_score) sorted by similarity descending.
pub async fn search_similar(
    pool: &SqlitePool,
    query_vector: &[f32],
    limit: usize,
    threshold: f64,
) -> anyhow::Result<Vec<(i64, f64)>> {
    let rows = sqlx::query_as::<_, BookVectorRow>(
        "SELECT book_id, vector, vector_type, updated_at FROM book_vectors"
    )
    .fetch_all(pool)
    .await?;

    let mut scored: Vec<(i64, f64)> = rows
        .iter()
        .filter_map(|row| {
            let vec = parse_vector(&row.vector).ok()?;
            let sim = cosine_similarity(query_vector, &vec);
            if sim >= threshold { Some((row.book_id, sim)) } else { None }
        })
        .collect();

    scored.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
    scored.truncate(limit);
    Ok(scored)
}

/// Search content chunks within a specific book for RAG.
/// Returns (chunk_text, similarity_score) sorted by similarity descending.
pub async fn search_content(
    pool: &SqlitePool,
    book_id: i64,
    query_vector: &[f32],
    limit: usize,
) -> anyhow::Result<Vec<(String, f64)>> {
    let rows = sqlx::query_as::<_, ContentVectorRow>(
        "SELECT id, book_id, chunk_index, chunk_text, vector, updated_at
         FROM book_content_vectors WHERE book_id = ?"
    )
    .bind(book_id)
    .fetch_all(pool)
    .await?;

    let mut scored: Vec<(String, f64)> = rows
        .iter()
        .filter_map(|row| {
            let vec = parse_vector(&row.vector).ok()?;
            let sim = cosine_similarity(query_vector, &vec);
            Some((row.chunk_text.clone(), sim))
        })
        .collect();

    scored.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
    scored.truncate(limit);
    Ok(scored)
}

// ── High-level: generate book metadata vector ──────────────────────────

/// Generate an embedding from the book's search-friendly description
/// (title + author + concept_tags + reader_need_tags + target_reader_tags + description),
/// then store it in book_vectors.
/// This replaces Spring Boot's `generateBookMetadataVector`.
pub async fn generate_book_vector(
    pool: &SqlitePool,
    embedding_client: &EmbeddingClient,
    book_id: i64,
) -> anyhow::Result<()> {
    let book = sqlx::query_as::<_, kbook_core::entity::Book>(
        "SELECT * FROM books WHERE id = ?"
    )
    .bind(book_id)
    .fetch_optional(pool)
    .await?
    .ok_or_else(|| anyhow::anyhow!("Book not found: {}", book_id))?;

    let mut parts: Vec<String> = Vec::new();
    parts.push(book.title.clone());
    if let Some(author) = &book.author {
        if !author.is_empty() {
            parts.push(author.clone());
        }
    }
    if let Some(tags) = &book.concept_tags {
        if !tags.is_empty() {
            parts.push(tags.clone());
        }
    }
    if let Some(tags) = &book.reader_need_tags {
        if !tags.is_empty() {
            parts.push(tags.clone());
        }
    }
    if let Some(tags) = &book.target_reader_tags {
        if !tags.is_empty() {
            parts.push(tags.clone());
        }
    }
    if let Some(desc) = &book.description {
        if !desc.is_empty() {
            parts.push(desc.clone());
        }
    }

    let text = parts.join(" ");
    let vector = embedding_client.embed_single(&text).await?;
    upsert_book_vector(pool, book_id, &vector, "metadata").await?;

    tracing::info!(book_id, "Book metadata vector generated");
    Ok(())
}

// ── High-level: book content RAG ───────────────────────────────────────

/// Generate embeddings for text chunks and store them in book_content_vectors.
/// This replaces Spring Boot's content embedding logic.
pub async fn generate_content_vectors(
    pool: &SqlitePool,
    embedding_client: &EmbeddingClient,
    book_id: i64,
    chunks: &[String],
) -> anyhow::Result<()> {
    if chunks.is_empty() {
        return Ok(());
    }

    // Embed in batches of 20 to avoid API limits
    let batch_size = 20;
    for (batch_start, batch_chunks) in chunks.chunks(batch_size).enumerate() {
        let embeddings = embedding_client.embed(&batch_chunks.to_vec()).await?;
        for (i, embedding) in embeddings.into_iter().enumerate() {
            let chunk_index = (batch_start * batch_size + i) as i64;
            upsert_content_vector(
                pool,
                book_id,
                chunk_index,
                &batch_chunks[i],
                &embedding,
            )
            .await?;
        }
    }

    tracing::info!(book_id, chunks = chunks.len(), "Content vectors generated");
    Ok(())
}

/// Embed the query, search content vectors for the given book, return matching chunks with scores.
/// This replaces Spring Boot's `searchBookContent` for RAG.
pub async fn search_book_content(
    pool: &SqlitePool,
    embedding_client: &EmbeddingClient,
    book_id: i64,
    query: &str,
    limit: usize,
) -> anyhow::Result<Vec<(String, f64)>> {
    let query_vector = embedding_client.embed_single(query).await?;
    search_content(pool, book_id, &query_vector, limit).await
}
