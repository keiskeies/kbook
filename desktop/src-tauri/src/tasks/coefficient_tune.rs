use kbook_db::Database;
use kbook_core::entity::RecommendCoefficient;
use std::collections::HashMap;

/// Auto-tune recommendation coefficients based on feedback data.
///
/// Matches Spring Boot's `RecommendCoefficientService.autoTuneCoefficients()`:
/// 1. Read configurable parameters from DB (TUNING category)
/// 2. Count feedback events in the time window; skip if insufficient
/// 3. Tune recall weights (FUSION category) by gradient adjustment
/// 4. Tune quality factors (QUALITY category) based on average rating
/// 5. Reload coefficient cache
/// 6. Clean up old feedback data
pub async fn auto_tune(db: &Database) -> anyhow::Result<()> {
    let pool = &db.pool;

    // ── 1. Read tuning parameters from DB ──────────────────────────────
    let window_hours = get_coefficient(pool, "TUNING", "feedback_window_hours", 24.0).await;
    let min_feedback = get_coefficient(pool, "TUNING", "min_feedback_count", 50.0).await as i64;
    let learning_rate = get_coefficient(pool, "TUNING", "learning_rate", 0.05).await;
    let retention_days = get_coefficient(pool, "TUNING", "data_retention_days", 30.0).await;

    // ── 2. Count feedback events in the time window ────────────────────
    let count: (i64,) = sqlx::query_as(
        "SELECT COUNT(*) FROM recommend_feedback_events WHERE created_at >= datetime('now', ?)"
    )
    .bind(format!("-{} hours", window_hours as i64))
    .fetch_one(pool)
    .await?;

    if count.0 < min_feedback {
        tracing::info!(
            "[TUNE] Only {} feedback events in {}h window (min: {}), skipping",
            count.0,
            window_hours as i64,
            min_feedback
        );
        return Ok(());
    }

    tracing::info!(
        "[TUNE] Starting auto-tune: feedbackCount={}, window={}h, learningRate={}",
        count.0,
        window_hours as i64,
        learning_rate
    );

    // ── 3. Tune recall weights (FUSION category) ──────────────────────
    tune_recall_weights(pool, learning_rate).await?;

    // ── 4. Tune quality factors (QUALITY category) ─────────────────────
    tune_quality_factors(pool, learning_rate).await?;

    // ── 5. Normalize fusion weights ────────────────────────────────────
    normalize_fusion_weights(pool).await?;

    // ── 6. Clean up old feedback data ──────────────────────────────────
    let deleted: (i64,) = sqlx::query_as(
        "SELECT COUNT(*) FROM recommend_feedback_events WHERE created_at < datetime('now', ?)"
    )
    .bind(format!("-{} days", retention_days as i64))
    .fetch_one(pool)
    .await?;

    if deleted.0 > 0 {
        sqlx::query("DELETE FROM recommend_feedback_events WHERE created_at < datetime('now', ?)")
            .bind(format!("-{} days", retention_days as i64))
            .execute(pool)
            .await?;
        tracing::info!(
            "[TUNE] Cleaned old feedback data: deleted={}, retention={}days",
            deleted.0,
            retention_days as i64
        );
    }

    tracing::info!("[TUNE] Auto-tune completed");
    Ok(())
}

/// Get a single coefficient value from the recommend_coefficient table.
async fn get_coefficient(
    pool: &sqlx::SqlitePool,
    category: &str,
    key: &str,
    fallback: f64,
) -> f64 {
    let row: Option<(f64,)> = sqlx::query_as(
        "SELECT coeff_value FROM recommend_coefficient WHERE category = ? AND coeff_key = ?"
    )
    .bind(category)
    .bind(key)
    .fetch_optional(pool)
    .await
    .ok()
    .flatten();
    row.map(|(v,)| v).unwrap_or(fallback)
}

/// Adjust a single coefficient by delta, respecting locked status and min/max bounds.
async fn adjust_coefficient(
    pool: &sqlx::SqlitePool,
    category: &str,
    key: &str,
    delta: f64,
) -> anyhow::Result<()> {
    let row = sqlx::query_as::<_, RecommendCoefficient>(
        "SELECT * FROM recommend_coefficient WHERE category = ? AND coeff_key = ?"
    )
    .bind(category)
    .bind(key)
    .fetch_optional(pool)
    .await?;

    let rc = match row {
        Some(rc) => rc,
        None => {
            tracing::debug!("[TUNE] Coefficient {}.{} not found, skipping", category, key);
            return Ok(());
        }
    };

    if rc.locked.unwrap_or(false) {
        tracing::debug!("[TUNE] Coefficient {}.{} is locked, skipping", category, key);
        return Ok(());
    }

    let current = rc.coeff_value.unwrap_or(0.0);
    let min_val = rc.min_value.unwrap_or(0.0);
    let max_val = rc.max_value.unwrap_or(1.0);
    let new_value = (current + delta).max(min_val).min(max_val);

    sqlx::query(
        "UPDATE recommend_coefficient SET coeff_value = ?, updated_at = datetime('now') WHERE category = ? AND coeff_key = ?"
    )
    .bind(new_value)
    .bind(category)
    .bind(key)
    .execute(pool)
    .await?;

    tracing::info!(
        "[TUNE] {}.{} {:.4} → {:.4} (delta={:.4})",
        category,
        key,
        current,
        new_value,
        delta
    );
    Ok(())
}

/// Tune recall weights based on positive feedback rates per recall path.
///
/// Strategy: paths with positive rate above average get increased weight,
/// paths below average get decreased weight.
/// delta = learning_rate * (path_rate - avg_rate)
async fn tune_recall_weights(
    pool: &sqlx::SqlitePool,
    learning_rate: f64,
) -> anyhow::Result<()> {
    let window_hours = get_coefficient(pool, "TUNING", "feedback_window_hours", 24.0).await;

    // Query: group by recall_paths, count total and positive (strength > 0)
    let rows: Vec<(String, i64, i64)> = sqlx::query_as(
        "SELECT recall_paths, COUNT(*), SUM(CASE WHEN strength > 0 THEN 1 ELSE 0 END) \
         FROM recommend_feedback_events \
         WHERE created_at >= datetime('now', ?) AND recall_paths IS NOT NULL \
         GROUP BY recall_paths"
    )
    .bind(format!("-{} hours", window_hours as i64))
    .fetch_all(pool)
    .await?;

    if rows.is_empty() {
        tracing::debug!("[TUNE] No recall path feedback data, skipping weight tuning");
        return Ok(());
    }

    // Calculate positive rate per path
    let mut path_rates: HashMap<String, f64> = HashMap::new();
    let mut total_rate = 0.0;
    let mut path_count = 0;

    for (paths, total, positive) in &rows {
        let rate = if *total > 0 { *positive as f64 / *total as f64 } else { 0.5 };
        path_rates.insert(paths.clone(), rate);
        total_rate += rate;
        path_count += 1;
    }

    let avg_rate = if path_count > 0 { total_rate / path_count as f64 } else { 0.5 };

    // Map: recall path keyword → coefficient key
    let path_to_coeff: &[(&str, &str)] = &[
        ("RULE", "weight_rule"),
        ("VECTOR", "weight_vector"),
        ("COLLAB", "weight_collab"),
        ("EXPLORE", "weight_explore"),
    ];

    for (path_name, coeff_key) in path_to_coeff {
        // Find the matching path rate (recall_paths may contain multiple paths)
        let mut found_rate: Option<f64> = None;
        for (paths, rate) in &path_rates {
            if paths.contains(path_name) {
                found_rate = Some(*rate);
                break;
            }
        }

        if let Some(path_rate) = found_rate {
            let delta = learning_rate * (path_rate - avg_rate);
            adjust_coefficient(pool, "FUSION", coeff_key, delta).await?;
        }
    }

    Ok(())
}

/// Tune quality factors based on average rating from feedback.
///
/// - avg rating < 2.5: increase suppression (decrease very_low, low, unknown)
/// - avg rating > 3.5: relax suppression (increase very_low, low)
async fn tune_quality_factors(
    pool: &sqlx::SqlitePool,
    learning_rate: f64,
) -> anyhow::Result<()> {
    let window_hours = get_coefficient(pool, "TUNING", "feedback_window_hours", 24.0).await;

    // Query: group by feedback_type, get count and avg strength
    let rows: Vec<(String, i64, f64)> = sqlx::query_as(
        "SELECT feedback_type, COUNT(*), AVG(strength) \
         FROM recommend_feedback_events \
         WHERE created_at >= datetime('now', ?) \
         GROUP BY feedback_type"
    )
    .bind(format!("-{} hours", window_hours as i64))
    .fetch_all(pool)
    .await?;

    // Calculate average rating from RATE feedback type
    let mut avg_rating = 3.0;
    for (fb_type, count, avg_strength) in &rows {
        if fb_type == "RATE" && *count > 0 {
            // Infer average rating from strength
            let rating = if *avg_strength > 0.0 { *avg_strength / 0.1 } else { 3.0 };
            avg_rating = rating.max(1.0).min(5.0);
        }
    }

    if avg_rating < 2.5 {
        // Low average rating → increase suppression
        adjust_coefficient(pool, "QUALITY", "very_low", -learning_rate * 0.5).await?;
        adjust_coefficient(pool, "QUALITY", "low", -learning_rate * 0.3).await?;
        adjust_coefficient(pool, "QUALITY", "unknown", -learning_rate * 0.2).await?;
    } else if avg_rating > 3.5 {
        // High average rating → relax suppression
        adjust_coefficient(pool, "QUALITY", "very_low", learning_rate * 0.3).await?;
        adjust_coefficient(pool, "QUALITY", "low", learning_rate * 0.2).await?;
    }

    Ok(())
}

/// Normalize fusion weights so they sum to 1.0.
async fn normalize_fusion_weights(pool: &sqlx::SqlitePool) -> anyhow::Result<()> {
    let keys = ["weight_rule", "weight_vector", "weight_collab", "weight_explore"];

    // Read current values
    let mut values: HashMap<&str, f64> = HashMap::new();
    let mut sum = 0.0;
    for key in &keys {
        let val = get_coefficient(pool, "FUSION", key, 0.0).await;
        values.insert(key, val);
        sum += val;
    }

    if sum <= 0.0 {
        return Ok(());
    }

    // Normalize and update
    for key in &keys {
        let current = values[key];
        let normalized = current / sum;

        // Check if locked before updating
        let row = sqlx::query_as::<_, RecommendCoefficient>(
            "SELECT * FROM recommend_coefficient WHERE category = 'FUSION' AND coeff_key = ?"
        )
        .bind(key)
        .fetch_optional(pool)
        .await?;

        if let Some(rc) = row {
            if rc.locked.unwrap_or(false) {
                continue;
            }
            let min_val = rc.min_value.unwrap_or(0.0);
            let max_val = rc.max_value.unwrap_or(1.0);
            let clamped = normalized.max(min_val).min(max_val);

            sqlx::query(
                "UPDATE recommend_coefficient SET coeff_value = ?, updated_at = datetime('now') WHERE category = 'FUSION' AND coeff_key = ?"
            )
            .bind(clamped)
            .bind(key)
            .execute(pool)
            .await?;
        }
    }

    Ok(())
}
