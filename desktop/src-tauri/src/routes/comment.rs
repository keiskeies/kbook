use axum::{
    extract::{State, Path, Query, Extension},
    http::StatusCode,
    routing::{get, post, delete},
    Json, Router,
};
use std::sync::Arc;
use kbook_db::Database;
use kbook_auth::jwt::Claims;

pub fn comment_routes(db: Arc<Database>) -> Router {
    Router::new()
        .route("/", post(create_comment))
        .route("/book/{bookId}", get(list_comments))
        .route("/{commentId}", delete(delete_comment))
        .with_state(db)
}

async fn create_comment(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let book_id = req.get("bookId").and_then(|v| v.as_i64()).ok_or(StatusCode::BAD_REQUEST)?;
    let content = req.get("content").and_then(|v| v.as_str()).ok_or(StatusCode::BAD_REQUEST)?;
    let chapter_id = req.get("chapterId").and_then(|v| v.as_str());
    let parent_id = req.get("parentId").and_then(|v| v.as_i64());

    let comment = kbook_db::repository::comment_repo::create(
        &db.pool, claims.sub, book_id, chapter_id, parent_id, content,
    )
    .await
    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(Json(serde_json::json!({
        "code": 0,
        "message": "success",
        "data": comment
    })))
}

async fn list_comments(
    State(db): State<Arc<Database>>,
    Path(book_id): Path<i64>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let page = params.get("page").and_then(|s| s.parse::<i32>().ok()).unwrap_or(1);
    let size = params.get("size").and_then(|s| s.parse::<i32>().ok()).unwrap_or(20);

    let (comments, total) = kbook_db::repository::comment_repo::list_by_book(&db.pool, book_id, page, size)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(Json(serde_json::json!({
        "code": 0,
        "message": "success",
        "data": {
            "list": comments,
            "total": total,
            "page": page,
            "size": size
        }
    })))
}

async fn delete_comment(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Path(comment_id): Path<i64>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    kbook_db::repository::comment_repo::delete(&db.pool, comment_id, claims.sub)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}
