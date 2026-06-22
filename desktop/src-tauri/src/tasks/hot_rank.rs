use kbook_db::Database;
use std::collections::HashMap;

fn exe_dir() -> std::path::PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|p| p.to_path_buf()))
        .unwrap_or_else(|| std::path::PathBuf::from("."))
}

pub async fn refresh(db: &Database) -> anyhow::Result<()> {

    let rows: Vec<(i64, Option<i64>, Option<f64>, Option<String>)> = sqlx::query_as(
        "SELECT b.id, b.read_count, b.rating, b.format_tags FROM books b"
    )
    .fetch_all(&db.pool)
    .await?;

    let ai_counts: Vec<(i64, i64)> = sqlx::query_as(
        "SELECT book_id, COUNT(*) as cnt FROM ai_sessions GROUP BY book_id"
    ).fetch_all(&db.pool).await.unwrap_or_default();
    let ai_map: HashMap<i64, i64> = ai_counts.into_iter().collect();

    let rt_counts: Vec<(i64, i64)> = sqlx::query_as(
        "SELECT book_id, COUNT(*) as cnt FROM round_table_sessions GROUP BY book_id"
    ).fetch_all(&db.pool).await.unwrap_or_default();
    let rt_map: HashMap<i64, i64> = rt_counts.into_iter().collect();

    let db_counts: Vec<(i64, i64)> = sqlx::query_as(
        "SELECT book_id, COUNT(*) as cnt FROM debate_sessions GROUP BY book_id"
    ).fetch_all(&db.pool).await.unwrap_or_default();
    let db_map: HashMap<i64, i64> = db_counts.into_iter().collect();

    let bs_counts: Vec<(i64, i64)> = sqlx::query_as(
        "SELECT book_id, COUNT(*) as cnt FROM bookshelf GROUP BY book_id"
    ).fetch_all(&db.pool).await.unwrap_or_default();
    let bs_map: HashMap<i64, i64> = bs_counts.into_iter().collect();

    let mut scored: Vec<(i64, f64)> = rows.iter().map(|(id, read_count, rating, _format_tags)| {
        let rc = read_count.unwrap_or(0) as f64;
        let rt = rating.unwrap_or(0.0);
        let ai = *ai_map.get(id).unwrap_or(&0) as f64;
        let rt_cnt = *rt_map.get(id).unwrap_or(&0) as f64;
        let debate_cnt = *db_map.get(id).unwrap_or(&0) as f64;
        let shelf_cnt = *bs_map.get(id).unwrap_or(&0) as f64;

        let hotness = rc * 1.0
            + ai * 2.0
            + rt_cnt * 2.5
            + debate_cnt * 3.0
            + shelf_cnt * 1.5
            + rt * 0.2;
        (*id, hotness)
    }).collect();

    scored.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
    scored.truncate(300);

    let hot_ids: Vec<i64> = scored.iter().enumerate()
        .map(|(_rank, (id, _))| *id)
        .collect();

    std::fs::write(
        exe_dir().join("hot_rank.json"),
        serde_json::json!({"ids": hot_ids, "updated_at": "now"}).to_string(),
    ).ok();

    tracing::info!("[HOT_RANK] Refreshed with {} books", scored.len());

    Ok(())
}

pub fn get_hot_rank() -> Vec<i64> {
    let path = exe_dir().join("hot_rank.json");

    if let Ok(content) = std::fs::read_to_string(&path) {
        if let Ok(json) = serde_json::from_str::<serde_json::Value>(&content) {
            return json["ids"].as_array()
                .map(|a| a.iter().filter_map(|v| v.as_i64()).collect())
                .unwrap_or_default();
        }
    }
    vec![]
}
