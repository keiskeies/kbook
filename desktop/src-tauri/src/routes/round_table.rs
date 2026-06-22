use axum::{
    extract::{State, Path, Extension, Query},
    http::StatusCode,
    routing::{get, post, put},
    Json, Router,
};
use std::sync::Arc;
use kbook_db::Database;
use kbook_auth::jwt::Claims;
use kbook_ai::round_table::RoundTableService;

pub fn round_table_routes(db: Arc<Database>, round_table: Arc<RoundTableService>) -> Router {
    Router::new()
        .route("/sessions", get(get_global_sessions))
        .route("/sessions/{sessionId}", get(get_session).delete(delete_session))
        .route("/sessions/{sessionId}/status", put(update_session_status))
        .route("/sessions/{sessionId}/messages", get(get_messages))
        .route("/sessions/{sessionId}/coverage", get(get_coverage))
        .route("/sessions/{sessionId}/coverage/refresh", post(refresh_coverage))
        .route("/sessions/{sessionId}/report", get(get_report).post(trigger_report))
        .route("/sessions/{sessionId}/next-speaker", post(get_next_speaker))
        .route("/books/{bookId}/roles", get(get_roles))
        .route("/books/{bookId}/sessions", post(create_session).get(get_sessions_by_book))
        .route("/books/{bookId}/speak", post(speak))
        .with_state((db, round_table))
}

async fn get_global_sessions(
    State((db, _)): State<(Arc<Database>, Arc<RoundTableService>)>,
    Extension(_claims): Extension<Claims>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let sessions = kbook_db::repository::round_table_repo::list_all_sessions(&db.pool)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    let page: usize = params.get("page").and_then(|s| s.parse().ok()).unwrap_or(0);
    let size: usize = params.get("size").and_then(|s| s.parse().ok()).unwrap_or(20);
    let total = sessions.len();
    let start = page * size;
    let end = (start + size).min(total);
    let content: Vec<_> = if start < total { sessions[start..end].to_vec() } else { vec![] };
    let last = end >= total;

    Ok(Json(serde_json::json!({
        "code": 0, "message": "success",
        "data": {
            "content": content,
            "totalElements": total,
            "totalPages": (total + size - 1) / size,
            "size": size,
            "number": page,
            "last": last,
            "first": page == 0
        }
    })))
}

async fn get_session(
    State((db, _)): State<(Arc<Database>, Arc<RoundTableService>)>,
    Path(session_id): Path<String>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let session = kbook_db::repository::round_table_repo::find_session(&db.pool, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": session})))
}

async fn update_session_status(
    State((_db, _)): State<(Arc<Database>, Arc<RoundTableService>)>,
    Path(_session_id): Path<String>,
    Json(_req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn delete_session(
    State((db, _)): State<(Arc<Database>, Arc<RoundTableService>)>,
    Path(session_id): Path<String>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    kbook_db::repository::round_table_repo::delete_session(&db.pool, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn get_messages(
    State((db, _)): State<(Arc<Database>, Arc<RoundTableService>)>,
    Path(session_id): Path<String>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let msgs = kbook_db::repository::round_table_repo::get_messages(&db.pool, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": msgs})))
}

async fn get_coverage(
    State((db, _)): State<(Arc<Database>, Arc<RoundTableService>)>,
    Path(session_id): Path<String>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let coverage = sqlx::query_as::<_, kbook_core::entity::RoundTableCoverage>(
        "SELECT * FROM round_table_coverages WHERE session_id = ?"
    )
    .bind(&session_id)
    .fetch_optional(&db.pool)
    .await
    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": coverage})))
}

async fn refresh_coverage(
    State((db, _)): State<(Arc<Database>, Arc<RoundTableService>)>,
    Path(session_id): Path<String>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let coverage = sqlx::query_as::<_, kbook_core::entity::RoundTableCoverage>(
        "SELECT * FROM round_table_coverages WHERE session_id = ?"
    )
    .bind(&session_id)
    .fetch_optional(&db.pool)
    .await
    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": coverage})))
}

async fn get_report(
    State((db, _)): State<(Arc<Database>, Arc<RoundTableService>)>,
    Path(session_id): Path<String>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let report = kbook_db::repository::round_table_repo::get_report(&db.pool, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": report})))
}

async fn trigger_report(
    State((db, rt)): State<(Arc<Database>, Arc<RoundTableService>)>,
    Path(session_id): Path<String>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let content = rt.generate_report(&db.pool, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    kbook_db::repository::round_table_repo::insert_report(&db.pool, &session_id, &content)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let report = kbook_db::repository::round_table_repo::get_report(&db.pool, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": report})))
}

async fn get_next_speaker(
    State((db, _)): State<(Arc<Database>, Arc<RoundTableService>)>,
    Path(session_id): Path<String>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let session = kbook_db::repository::round_table_repo::find_session(&db.pool, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .ok_or(StatusCode::NOT_FOUND)?;

    let messages = kbook_db::repository::round_table_repo::get_messages(&db.pool, &session_id)
        .await
        .unwrap_or_default();

    let role_keys: Vec<&str> = session.role_keys.as_deref().unwrap_or("")
        .split(',').filter(|s| !s.is_empty()).collect();

    if role_keys.is_empty() {
        return Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": ""})));
    }

    let next_idx = messages.len() % role_keys.len();
    let next_role = role_keys[next_idx].to_string();

    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": next_role})))
}

async fn get_roles(
    State((_db, _)): State<(Arc<Database>, Arc<RoundTableService>)>,
    Path(_book_id): Path<i64>,
    Query(_params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": []})))
}

async fn create_session(
    State((db, _)): State<(Arc<Database>, Arc<RoundTableService>)>,
    Extension(claims): Extension<Claims>,
    Path(book_id): Path<i64>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let role_keys = req.get("roleKeys")
        .and_then(|v| v.as_array())
        .map(|arr| arr.iter().filter_map(|v| v.as_str()).collect::<Vec<_>>().join(","))
        .unwrap_or_default();
    let session_id = uuid::Uuid::new_v4().to_string();
    let session = kbook_db::repository::round_table_repo::create_session(
        &db.pool, claims.sub, book_id, &session_id, &role_keys,
        req.get("roleConfigs").and_then(|v| v.as_str()),
    )
    .await
    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": session})))
}

async fn get_sessions_by_book(
    State((db, _)): State<(Arc<Database>, Arc<RoundTableService>)>,
    Path(book_id): Path<i64>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let sessions = kbook_db::repository::round_table_repo::list_sessions_by_book(&db.pool, book_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": sessions})))
}

async fn speak(
    State((db, rt)): State<(Arc<Database>, Arc<RoundTableService>)>,
    Path(_book_id): Path<i64>,
    Json(req): Json<serde_json::Value>,
) -> Result<axum::response::Sse<impl futures::Stream<Item = Result<axum::response::sse::Event, std::convert::Infallible>>>, StatusCode> {
    let session_id = req.get("sessionId").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let role_key = req.get("roleKey").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let topic = req.get("topic").and_then(|v| v.as_str()).unwrap_or("").to_string();

    let rx = rt.stream_speech(&db.pool, &session_id, &role_key, &role_key, &topic, "你是圆桌派的参与者。请根据讨论话题发表你的看法。")
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    let stream = async_stream::stream! {
        let mut rx = rx;
        while let Some(result) = rx.recv().await {
            match result {
                Ok(text) => {
                    let data = serde_json::json!({"roleKey": role_key, "text": text});
                    yield Ok(axum::response::sse::Event::default().event("message").data(data.to_string()));
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
