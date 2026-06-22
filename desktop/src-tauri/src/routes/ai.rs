use axum::{
    extract::{State, Path, Extension},
    http::StatusCode,
    routing::{get, post, delete},
    Json, Router,
};
use std::sync::Arc;
use kbook_db::Database;
use kbook_auth::jwt::Claims;
use kbook_ai::chat::AiChatService;

pub fn ai_routes(db: Arc<Database>, ai_chat: Arc<AiChatService>) -> Router {
    Router::new()
        .route("/sessions", post(create_session).get(list_sessions))
        .route("/sessions/{sessionId}", delete(delete_session))
        .route("/chat", post(chat))
        .route("/chat/stream", post(stream_chat))
        .route("/history", get(get_history))
        .route("/hot-prompts", get(get_hot_prompts))
        .route("/providers/presets", get(get_providers_presets))
        .with_state((db, ai_chat))
}

async fn create_session(
    State((db, _)): State<(Arc<Database>, Arc<AiChatService>)>,
    Extension(claims): Extension<Claims>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let session_id = uuid::Uuid::new_v4().to_string();
    let session = kbook_db::repository::ai_repo::create_session(
        &db.pool, claims.sub, "assistant", None, &session_id, None,
    )
    .await
    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(Json(serde_json::json!({
        "code": 0, "message": "success", "data": session
    })))
}

async fn list_sessions(
    State((db, _)): State<(Arc<Database>, Arc<AiChatService>)>,
    Extension(claims): Extension<Claims>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let sessions = kbook_db::repository::ai_repo::list_sessions(&db.pool, claims.sub, "assistant", None)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": sessions})))
}

async fn delete_session(
    State((db, _)): State<(Arc<Database>, Arc<AiChatService>)>,
    Path(session_id): Path<String>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    kbook_db::repository::ai_repo::delete_session(&db.pool, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn chat(
    State((db, ai_chat)): State<(Arc<Database>, Arc<AiChatService>)>,
    Extension(claims): Extension<Claims>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let message = req.get("message").and_then(|v| v.as_str()).ok_or(StatusCode::BAD_REQUEST)?;
    let session_id = req.get("sessionId").and_then(|v| v.as_str()).unwrap_or("");

    let response = ai_chat.chat(&db.pool, claims.sub, session_id, message, "你是KBook的AI助手。你可以帮助用户搜索图书、获取图书详情、查看排行榜等。请用中文回答。")
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(Json(serde_json::json!({
        "code": 0, "message": "success",
        "data": { "content": response, "sessionId": session_id }
    })))
}

async fn stream_chat(
    State((db, ai_chat)): State<(Arc<Database>, Arc<AiChatService>)>,
    Extension(claims): Extension<Claims>,
    Json(req): Json<serde_json::Value>,
) -> Result<axum::response::Sse<impl futures::Stream<Item = Result<axum::response::sse::Event, std::convert::Infallible>>>, StatusCode> {
    let message = req.get("message").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let session_id = req.get("sessionId").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let sid = session_id.clone();

    let rx = ai_chat.stream_chat(&db.pool, claims.sub, &sid, &message, "你是KBook的AI助手。你可以帮助用户搜索图书、获取图书详情、查看排行榜等。请用中文回答。")
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

async fn get_history(
    State((db, _)): State<(Arc<Database>, Arc<AiChatService>)>,
    Extension(claims): Extension<Claims>,
    axum::extract::Query(params): axum::extract::Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let session_id = params.get("sessionId").map(|s| s.as_str()).unwrap_or("");
    let convs = kbook_db::repository::ai_repo::get_conversations(&db.pool, claims.sub, session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": convs})))
}

async fn get_hot_prompts() -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({
        "code": 0, "message": "success",
        "data": ["推荐几本关于成长与情感的高分书籍","有哪些值得读的历史类好书？","职场新人适合读什么书来提升自己？","最近有什么精彩的悬疑或科幻小说推荐吗？"]
    })))
}

async fn get_providers_presets() -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": []})))
}
