use axum::{
    extract::{State, Path, Extension},
    http::StatusCode,
    routing::{get, post},
    Json, Router,
};
use std::sync::Arc;
use kbook_db::Database;
use kbook_auth::jwt::Claims;

pub fn book_trash_routes(db: Arc<Database>) -> Router {
    Router::new()
        .route("/", get(list_trash))
        .route("/count", get(get_trash_count))
        .route("/{bookId}", post(move_to_trash).delete(restore_from_trash))
        .route("/check/{bookId}", get(check_in_trash))
        .with_state(db)
}

async fn list_trash(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let items = sqlx::query_as::<_, (i64, i64, i64, Option<String>, Option<String>)>(
        "SELECT bt.id, bt.user_id, bt.book_id, bt.created_at, b.title
         FROM book_trash bt
         LEFT JOIN books b ON b.id = bt.book_id
         WHERE bt.user_id = ?
         ORDER BY bt.created_at DESC"
    )
    .bind(claims.sub)
    .fetch_all(&db.pool)
    .await
    .unwrap_or_default();

    let data: Vec<serde_json::Value> = items.into_iter()
        .map(|(id, user_id, book_id, created_at, title)| serde_json::json!({
            "id": id, "userId": user_id, "bookId": book_id,
            "createdAt": created_at, "title": title
        }))
        .collect();

    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": data})))
}

async fn get_trash_count(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let count: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM book_trash WHERE user_id = ?")
        .bind(claims.sub)
        .fetch_one(&db.pool)
        .await
        .unwrap_or((0,));

    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": count.0})))
}

async fn move_to_trash(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Path(book_id): Path<i64>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    sqlx::query("INSERT OR IGNORE INTO book_trash (user_id, book_id) VALUES (?, ?)")
        .bind(claims.sub)
        .bind(book_id)
        .execute(&db.pool)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn restore_from_trash(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Path(book_id): Path<i64>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    sqlx::query("DELETE FROM book_trash WHERE user_id = ? AND book_id = ?")
        .bind(claims.sub)
        .bind(book_id)
        .execute(&db.pool)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn check_in_trash(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Path(book_id): Path<i64>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let count: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM book_trash WHERE user_id = ? AND book_id = ?")
        .bind(claims.sub)
        .bind(book_id)
        .fetch_one(&db.pool)
        .await
        .unwrap_or((0,));

    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": count.0 > 0})))
}
