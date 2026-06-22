use axum::{
    extract::{State, Path, Extension, Query},
    http::StatusCode,
    routing::get,
    Json, Router,
};
use std::sync::Arc;
use kbook_db::Database;
use kbook_auth::jwt::Claims;
use kbook_ai::book_chat::BookChatService;
use super::book::BookState;

pub fn book_chat_routes(db: Arc<Database>, book_chat: Arc<BookChatService>) -> Router {
    Router::new()
        .route("/{bookId}/chat/stream", get(stream_book_chat))
        .route("/{bookId}/chat/suggestions", get(get_suggestions))
        .route("/{bookId}/chat/history", get(get_history))
        .route("/{bookId}/chat/sessions", get(get_sessions))
        .route("/{bookId}/chat/follow-up", get(generate_follow_up))
        .with_state((db, book_chat))
}

async fn stream_book_chat(
    State((db, book_chat)): State<BookState>,
    Extension(claims): Extension<Claims>,
    Path(book_id): Path<i64>,
    Json(req): Json<serde_json::Value>,
) -> Result<axum::response::Sse<impl futures::Stream<Item = Result<axum::response::sse::Event, std::convert::Infallible>>>, StatusCode> {
    let message = req.get("message").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let session_id = req.get("sessionId").and_then(|v| v.as_str()).unwrap_or("").to_string();

    let rx = book_chat.stream_book_chat(&db.pool, claims.sub, book_id, &message, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    let stream = async_stream::stream! {
        let mut rx = rx;
        while let Some(result) = rx.recv().await {
            match result {
                Ok(text) => {
                    yield Ok(axum::response::sse::Event::default().event("message").data(text));
                }
                Err(e) => {
                    let data = serde_json::json!({"error": e.to_string()});
                    yield Ok(axum::response::sse::Event::default().event("error").data(data.to_string()));
                }
            }
        }
        yield Ok(axum::response::sse::Event::default().event("done").data("[DONE]"));
    };

    Ok(axum::response::Sse::new(stream).keep_alive(axum::response::sse::KeepAlive::default()))
}

async fn get_suggestions(
    State((db, book_chat)): State<BookState>,
    Path(book_id): Path<i64>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let questions = book_chat.get_suggested_questions(&db.pool, book_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": questions})))
}

async fn get_history(
    State((db, _)): State<BookState>,
    Extension(_claims): Extension<Claims>,
    Path(_book_id): Path<i64>,
    Query(_params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": []})))
}

async fn get_sessions(
    State((db, _)): State<BookState>,
    Extension(_claims): Extension<Claims>,
    Path(_book_id): Path<i64>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": []})))
}

async fn generate_follow_up(
    State((db, _)): State<BookState>,
    Extension(_claims): Extension<Claims>,
    Path(_book_id): Path<i64>,
    Json(_req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": []})))
}
