use axum::{
    extract::{State, Path, Query, Extension},
    http::StatusCode,
    routing::{get, post},
    Json, Router,
};
use std::sync::Arc;
use kbook_db::Database;
use kbook_auth::jwt::Claims;

pub fn progress_routes(db: Arc<Database>) -> Router {
    Router::new()
        .route("/", post(report_progress))
        .route("/batch", post(batch_report_progress))
        .route("/{bookId}", get(get_progress))
        .route("/batch-get", post(get_progress_batch))
        .route("/history", get(get_reading_history))
        .route("/recent", get(get_recent_reading))
        .route("/stats", get(get_reading_stats))
        .with_state(db)
}

async fn report_progress(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let book_id = req.get("bookId").and_then(|v| v.as_i64()).ok_or(StatusCode::BAD_REQUEST)?;
    let progress = req.get("progress").and_then(|v| v.as_f64()).unwrap_or(0.0);
    let current_position = req.get("currentPosition").and_then(|v| v.as_str());

    let rp = kbook_db::repository::progress_repo::upsert(
        &db.pool, claims.sub, book_id, progress, current_position,
    )
    .await
    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(Json(serde_json::json!({
        "code": 0,
        "message": "success",
        "data": rp
    })))
}

async fn batch_report_progress(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Json(items): Json<Vec<serde_json::Value>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    for item in &items {
        if let (Some(book_id), Some(progress)) = (
            item.get("bookId").and_then(|v| v.as_i64()),
            item.get("progress").and_then(|v| v.as_f64()),
        ) {
            let _ = kbook_db::repository::progress_repo::upsert(
                &db.pool, claims.sub, book_id, progress, None,
            ).await;
        }
    }
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn get_progress(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Path(book_id): Path<i64>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let rp = kbook_db::repository::progress_repo::get(&db.pool, claims.sub, book_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(Json(serde_json::json!({
        "code": 0,
        "message": "success",
        "data": rp
    })))
}

async fn get_progress_batch(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let book_ids: Vec<i64> = req.get("bookIds")
        .and_then(|v| v.as_array())
        .map(|arr| arr.iter().filter_map(|v| v.as_i64()).collect())
        .unwrap_or_default();

    let rps = kbook_db::repository::progress_repo::get_batch(&db.pool, claims.sub, &book_ids)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    let map: std::collections::HashMap<i64, _> = rps.into_iter().map(|rp| (rp.book_id, rp)).collect();
    Ok(Json(serde_json::json!({
        "code": 0,
        "message": "success",
        "data": map
    })))
}

async fn get_reading_history(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let page: i32 = params.get("page").and_then(|s| s.parse().ok()).unwrap_or(0);
    let size: i32 = params.get("size").and_then(|s| s.parse().ok()).unwrap_or(10);
    let offset = page * size;

    let total: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM reading_progress WHERE user_id = ?")
        .bind(claims.sub)
        .fetch_one(&db.pool)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    let items = sqlx::query_as::<_, kbook_core::entity::ReadingProgress>(
        "SELECT * FROM reading_progress WHERE user_id = ? ORDER BY updated_at DESC LIMIT ? OFFSET ?"
    )
    .bind(claims.sub)
    .bind(size)
    .bind(offset)
    .fetch_all(&db.pool)
    .await
    .unwrap_or_default();

    let _total_pages = (total.0 as i32 + size - 1) / size;

    Ok(Json(serde_json::json!({
        "code": 0,
        "message": "success",
        "data": {
            "list": items,
            "total": total.0,
            "page": page,
            "size": size
        }
    })))
}

async fn get_recent_reading(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let limit = params.get("limit").and_then(|s| s.parse::<i32>().ok()).unwrap_or(10);
    let rps = kbook_db::repository::progress_repo::get_recent(&db.pool, claims.sub, limit)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(Json(serde_json::json!({
        "code": 0,
        "message": "success",
        "data": rps
    })))
}

async fn get_reading_stats(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let total: (i64,) = sqlx::query_as("SELECT COUNT(DISTINCT book_id) FROM reading_progress WHERE user_id = ?")
        .bind(claims.sub)
        .fetch_one(&db.pool)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    let completed: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM reading_progress WHERE user_id = ? AND progress >= 0.95")
        .bind(claims.sub)
        .fetch_one(&db.pool)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    let avg: (f64,) = sqlx::query_as("SELECT COALESCE(AVG(progress), 0.0) FROM reading_progress WHERE user_id = ?")
        .bind(claims.sub)
        .fetch_one(&db.pool)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(Json(serde_json::json!({
        "code": 0,
        "message": "success",
        "data": {
            "totalBooks": total.0,
            "completedBooks": completed.0,
            "totalReadingTime": 0,
            "averageProgress": avg.0
        }
    })))
}
