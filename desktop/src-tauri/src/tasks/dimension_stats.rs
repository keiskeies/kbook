use kbook_db::Database;
use std::collections::HashMap;

fn exe_dir() -> std::path::PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|p| p.to_path_buf()))
        .unwrap_or_else(|| std::path::PathBuf::from("."))
}

pub async fn refresh(db: &Database) -> anyhow::Result<()> {
    let rows: Vec<(i64, Option<String>)> = sqlx::query_as(
        "SELECT id, relevance_scores FROM books WHERE relevance_scores IS NOT NULL AND relevance_scores != ''"
    )
    .fetch_all(&db.pool)
    .await?;

    let mut sums: HashMap<String, f64> = HashMap::new();
    let mut sum_squares: HashMap<String, f64> = HashMap::new();
    let mut counts: HashMap<String, i64> = HashMap::new();

    for (_book_id, relevance_json) in &rows {
        if let Some(json_str) = relevance_json {
            if let Ok(scores) = serde_json::from_str::<serde_json::Value>(json_str) {
                if let Some(obj) = scores.as_object() {
                    for (key, val) in obj {
                        if let Some(v) = val.as_f64() {
                            *sums.entry(key.clone()).or_insert(0.0) += v;
                            *sum_squares.entry(key.clone()).or_insert(0.0) += v * v;
                            *counts.entry(key.clone()).or_insert(0) += 1;
                        }
                    }
                }
            }
        }
    }

    let mut means = HashMap::new();
    let mut stddevs = HashMap::new();

    for key in sums.keys() {
        let n = *counts.get(key).unwrap_or(&1) as f64;
        let mean = sums[key] / n;
        let variance = (sum_squares[key] / n) - mean * mean;
        let stddev = (variance.max(0.0)).sqrt().max(0.15);
        means.insert(key.clone(), mean);
        stddevs.insert(key.clone(), stddev);
    }

    tracing::info!("[DIMENSION_STATS] Computed stats for {} dimensions from {} books",
        means.len(), rows.len());

    std::fs::write(
        exe_dir().join("dimension_stats.json"),
        serde_json::json!({"means": means, "stddevs": stddevs}).to_string(),
    ).ok();

    Ok(())
}

pub fn load_stats() -> (HashMap<String, f64>, HashMap<String, f64>) {
    let path = exe_dir().join("dimension_stats.json");

    if let Ok(content) = std::fs::read_to_string(&path) {
        if let Ok(json) = serde_json::from_str::<serde_json::Value>(&content) {
            let means = json["means"].as_object()
                .map(|m| m.iter().map(|(k, v)| (k.clone(), v.as_f64().unwrap_or(0.5))).collect())
                .unwrap_or_default();
            let stddevs = json["stddevs"].as_object()
                .map(|m| m.iter().map(|(k, v)| (k.clone(), v.as_f64().unwrap_or(0.15))).collect())
                .unwrap_or_default();
            return (means, stddevs);
        }
    }

    (HashMap::new(), HashMap::new())
}
