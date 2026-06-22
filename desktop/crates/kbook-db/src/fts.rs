use sqlx::SqlitePool;
use rusqlite::Connection;
use std::path::Path;

pub async fn init_fts(pool: &SqlitePool) -> anyhow::Result<()> {
    sqlx::query(CREATE_FTS).execute(pool).await?;
    tracing::info!("FTS5 indexes initialized");
    Ok(())
}

const CREATE_FTS: &str = r#"
CREATE VIRTUAL TABLE IF NOT EXISTS books_fts USING fts5(
    title,
    author,
    description,
    concept_tags,
    reader_need_tags,
    target_reader_tags,
    content='books',
    content_rowid='id',
    tokenize='unicode61 remove_diacritics 2'
);

CREATE TRIGGER IF NOT EXISTS books_ai AFTER INSERT ON books BEGIN
    INSERT INTO books_fts(rowid, title, author, description, concept_tags, reader_need_tags, target_reader_tags)
    VALUES (new.id, new.title, new.author, new.description, new.concept_tags, new.reader_need_tags, new.target_reader_tags);
END;

CREATE TRIGGER IF NOT EXISTS books_ad AFTER DELETE ON books BEGIN
    INSERT INTO books_fts(books_fts, rowid, title, author, description, concept_tags, reader_need_tags, target_reader_tags)
    VALUES ('delete', old.id, old.title, old.author, old.description, old.concept_tags, old.reader_need_tags, old.target_reader_tags);
END;

CREATE TRIGGER IF NOT EXISTS books_au AFTER UPDATE ON books BEGIN
    INSERT INTO books_fts(books_fts, rowid, title, author, description, concept_tags, reader_need_tags, target_reader_tags)
    VALUES ('delete', old.id, old.title, old.author, old.description, old.concept_tags, old.reader_need_tags, old.target_reader_tags);
    INSERT INTO books_fts(rowid, title, author, description, concept_tags, reader_need_tags, target_reader_tags)
    VALUES (new.id, new.title, new.author, new.description, new.concept_tags, new.reader_need_tags, new.target_reader_tags);
END;
"#;

/// Simple Chinese character-by-character tokenization.
/// Each CJK character is separated by a space so FTS5 can match individual characters.
/// Non-CJK (ASCII) runs are kept together as words.
/// The original text is also appended as a phrase for multi-character matching.
///
/// Example: "三体小说" → "三 体 小说 三体小说"
/// Example: "三体 novel" → "三 体 novel 三体"
pub fn tokenize_chinese(text: &str) -> String {
    let mut result = String::new();
    let mut cjk_buffer = String::new();

    let flush_cjk = |buf: &str, res: &mut String| {
        if buf.is_empty() { return; }
        // Add each CJK character individually
        for ch in buf.chars() {
            res.push(ch);
            res.push(' ');
        }
        // Also add the original CJK phrase for multi-character matching
        res.push_str(buf);
        res.push(' ');
    };

    for ch in text.chars() {
        if is_cjk_character(ch) {
            cjk_buffer.push(ch);
        } else {
            // Flush accumulated CJK characters
            if !cjk_buffer.is_empty() {
                flush_cjk(&cjk_buffer, &mut result);
                cjk_buffer.clear();
            }
            if ch.is_ascii_whitespace() {
                if !result.is_empty() && !result.ends_with(' ') {
                    result.push(' ');
                }
            } else {
                result.push(ch);
            }
        }
    }

    // Flush remaining CJK buffer
    if !cjk_buffer.is_empty() {
        flush_cjk(&cjk_buffer, &mut result);
    }

    result.trim().to_string()
}

/// Check if a character is a CJK (Chinese/Japanese/Korean) ideograph
fn is_cjk_character(ch: char) -> bool {
    matches!(ch,
        '\u{4E00}'..='\u{9FFF}' |   // CJK Unified Ideographs
        '\u{3400}'..='\u{4DBF}' |   // CJK Unified Ideographs Extension A
        '\u{20000}'..='\u{2A6DF}' | // CJK Unified Ideographs Extension B
        '\u{2A700}'..='\u{2B73F}' | // CJK Unified Ideographs Extension C
        '\u{2B740}'..='\u{2B81F}' | // CJK Unified Ideographs Extension D
        '\u{F900}'..='\u{FAFF}' |   // CJK Compatibility Ideographs
        '\u{2F800}'..='\u{2FA1F}'   // CJK Compatibility Ideographs Supplement
    )
}

pub fn search_books_fts(db_path: &Path, query: &str, limit: i32) -> anyhow::Result<Vec<i64>> {
    let conn = Connection::open(db_path)?;

    // Try tokenized query first for Chinese support
    let tokenized = tokenize_chinese(query);
    let ids = if let Ok(results) = search_books_fts_with_query(&conn, &tokenized, limit) {
        if results.is_empty() && query != tokenized {
            // Fallback to original query if tokenized yields nothing
            search_books_fts_with_query(&conn, query, limit)?
        } else {
            results
        }
    } else {
        // If tokenized query fails (e.g. FTS syntax error), try original
        search_books_fts_with_query(&conn, query, limit)?
    };

    Ok(ids)
}

fn search_books_fts_with_query(conn: &Connection, query: &str, limit: i32) -> anyhow::Result<Vec<i64>> {
    let mut stmt = conn.prepare(
        "SELECT rowid FROM books_fts WHERE books_fts MATCH ?1 ORDER BY rank LIMIT ?2"
    )?;
    let rows = stmt.query_map(rusqlite::params![query, limit], |row| {
        row.get::<_, i64>(0)
    })?;
    let ids: Vec<i64> = rows.filter_map(|r| r.ok()).collect();
    Ok(ids)
}

pub fn rebuild_fts_index(db_path: &Path) -> anyhow::Result<()> {
    let conn = Connection::open(db_path)?;
    conn.execute("INSERT INTO books_fts(books_fts) VALUES('rebuild')", [])?;
    Ok(())
}

/// FTS5 search using SqlitePool (async, for use with hybrid_search).
/// Returns book IDs ordered by BM25 rank.
pub async fn search_fts_pool(pool: &SqlitePool, query: &str, limit: i32) -> anyhow::Result<Vec<i64>> {
    // Try tokenized query first for Chinese support
    let tokenized = tokenize_chinese(query);

    match search_fts_pool_inner(pool, &tokenized, limit).await {
        Ok(ids) if !ids.is_empty() => Ok(ids),
        Ok(_) if query != tokenized => {
            // Tokenized query returned nothing, try original
            search_fts_pool_inner(pool, query, limit).await
        }
        Ok(ids) => Ok(ids),
        Err(_) if query != tokenized => {
            // Tokenized query failed, try original
            search_fts_pool_inner(pool, query, limit).await
        }
        Err(e) => Err(e),
    }
}

/// Chinese-aware FTS5 search: tokenizes the query before searching.
/// This is the preferred entry point for user-facing search.
pub async fn search_fts_chinese(pool: &SqlitePool, query: &str, limit: i32) -> anyhow::Result<Vec<i64>> {
    search_fts_pool(pool, query, limit).await
}

async fn search_fts_pool_inner(pool: &SqlitePool, query: &str, limit: i32) -> anyhow::Result<Vec<i64>> {
    let rows = sqlx::query_scalar::<_, i64>(
        "SELECT rowid FROM books_fts WHERE books_fts MATCH ?1 ORDER BY rank LIMIT ?2"
    )
    .bind(query)
    .bind(limit)
    .fetch_all(pool)
    .await?;
    Ok(rows)
}
