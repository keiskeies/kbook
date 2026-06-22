use axum::{
    extract::{State, Extension},
    http::StatusCode,
    routing::get,
    Json, Router,
};
use std::sync::Arc;
use std::collections::HashMap;
use kbook_db::Database;
use kbook_auth::jwt::Claims;
use super::{fix_books_cover};

pub fn home_routes(db: Arc<Database>) -> Router {
    Router::new()
        .route("/health", get(health_check))
        .route("/home/stats", get(get_home_stats))
        .route("/home/tags", get(get_home_tags))
        .route("/home/recent", get(get_home_recent))
        .route("/home/personalized", get(get_home_personalized))
        .route("/home/categories", get(get_home_categories))
        .with_state(db)
}

async fn health_check() -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"status": "ok"})))
}

async fn get_home_stats(
    State(db): State<Arc<Database>>,
    claims: Option<Extension<Claims>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let user_id = claims.map(|c| c.sub).unwrap_or(0);
    if user_id == 0 {
        return Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": {"totalBooks": 0, "completedBooks": 0, "readingBooks": 0}})));
    }

    let total: (i64,) = sqlx::query_as("SELECT COUNT(DISTINCT book_id) FROM reading_progress WHERE user_id = ?")
        .bind(user_id).fetch_one(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let completed: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM reading_progress WHERE user_id = ? AND progress >= 0.95")
        .bind(user_id).fetch_one(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let reading: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM reading_progress WHERE user_id = ? AND progress > 0 AND progress < 0.95")
        .bind(user_id).fetch_one(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": {
        "totalBooks": total.0, "completedBooks": completed.0, "readingBooks": reading.0
    }})))
}

async fn get_home_tags(
    State(db): State<Arc<Database>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let books: Vec<(String,)> = sqlx::query_as(
        "SELECT format_tags FROM books WHERE format_tags IS NOT NULL AND format_tags != '' ORDER BY rating DESC LIMIT 5000"
    ).fetch_all(&db.pool).await.unwrap_or_default();

    let mut tag_counts: HashMap<String, i64> = HashMap::new();
    for (format_tags_str,) in books {
        let cleaned = format_tags_str.replace('[', "").replace(']', "").replace('"', "");
        for tag in cleaned.split(|c| c == ',' || c == '\u{FF0C}') {
            let tag = tag.trim().to_string();
            if !tag.is_empty() {
                *tag_counts.entry(tag).or_insert(0) += 1;
            }
        }
    }

    let mut tag_list: Vec<(String, i64)> = tag_counts.into_iter().collect();
    tag_list.sort_by(|a, b| b.1.cmp(&a.1));
    tag_list.truncate(120);

    let data: Vec<serde_json::Value> = tag_list.into_iter()
        .map(|(tag, count)| serde_json::json!({"name": tag, "count": count}))
        .collect();

    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": data})))
}

async fn get_home_recent(
    State(db): State<Arc<Database>>,
    claims: Option<Extension<Claims>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let user_id = claims.map(|c| c.sub).unwrap_or(0);
    if user_id == 0 {
        return Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": []})));
    }

    let items = sqlx::query_as::<_, (i64, i64, f64, Option<String>, Option<String>)>(
        "SELECT rp.book_id, rp.user_id, rp.progress, rp.current_position, b.title
         FROM reading_progress rp LEFT JOIN books b ON b.id = rp.book_id
         WHERE rp.user_id = ? ORDER BY rp.updated_at DESC LIMIT 4"
    ).bind(user_id).fetch_all(&db.pool).await.unwrap_or_default();

    let data: Vec<serde_json::Value> = items.into_iter()
        .map(|(book_id, _, progress, pos, title)| serde_json::json!({
            "bookId": book_id, "progress": progress, "currentPosition": pos, "title": title
        })).collect();

    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": data})))
}

async fn get_home_personalized(
    State(db): State<Arc<Database>>,
    claims: Option<Extension<Claims>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let user_id = claims.map(|c| c.sub).unwrap_or(0);

    let exclude = if user_id > 0 {
        let read_ids: Vec<(i64,)> = sqlx::query_as("SELECT DISTINCT book_id FROM reading_progress WHERE user_id = ?")
            .bind(user_id).fetch_all(&db.pool).await.unwrap_or_default();
        read_ids.into_iter().map(|(id,)| id).collect::<Vec<_>>()
    } else {
        vec![]
    };

    let books = if exclude.is_empty() {
        sqlx::query_as::<_, kbook_core::entity::Book>(
            "SELECT * FROM books ORDER BY rating DESC, read_count DESC LIMIT 6"
        ).fetch_all(&db.pool).await.unwrap_or_default()
    } else {
        let id_list = exclude.iter().map(|i| i.to_string()).collect::<Vec<_>>().join(",");
        sqlx::query_as::<_, kbook_core::entity::Book>(
            &format!("SELECT * FROM books WHERE id NOT IN ({}) ORDER BY rating DESC, read_count DESC LIMIT 6", id_list)
        ).fetch_all(&db.pool).await.unwrap_or_default()
    };

    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": fix_books_cover(books)})))
}

async fn get_home_categories(
    State(db): State<Arc<Database>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let raw_tags: Vec<(String,)> = sqlx::query_as(
        "SELECT format_tags FROM books WHERE format_tags IS NOT NULL AND format_tags != '' ORDER BY rating DESC LIMIT 5000"
    ).fetch_all(&db.pool).await.unwrap_or_default();

    let mut tag_counts: HashMap<String, i64> = HashMap::new();
    for (format_tags_str,) in raw_tags {
        let cleaned = format_tags_str.replace('[', "").replace(']', "").replace('"', "");
        for tag in cleaned.split(|c| c == ',' || c == '\u{FF0C}') {
            let tag = tag.trim().to_string();
            if !tag.is_empty() {
                *tag_counts.entry(tag).or_insert(0) += 1;
            }
        }
    }

    let mut tag_list: Vec<(String, i64)> = tag_counts.into_iter().collect();
    tag_list.sort_by(|a, b| b.1.cmp(&a.1));
    tag_list.truncate(20);

    let data: Vec<serde_json::Value> = tag_list.into_iter()
        .map(|(tag, count)| serde_json::json!({"name": tag, "count": count}))
        .collect();

    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": data})))
}
