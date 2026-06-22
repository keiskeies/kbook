use axum::{
    extract::{State, Path, Extension, Query},
    http::StatusCode,
    routing::{get, post},
    Json, Router,
};
use std::sync::Arc;
use kbook_db::Database;
use kbook_auth::jwt::Claims;
use kbook_ai::debate::DebateService;

pub fn debate_routes(db: Arc<Database>, debate: Arc<DebateService>) -> Router {
    Router::new()
        .route("/roles", get(get_roles))
        .route("/sessions", get(get_global_sessions))
        .route("/sessions/{sessionId}", get(get_session).delete(delete_session))
        .route("/sessions/{sessionId}/messages", get(get_messages))
        .route("/sessions/{sessionId}/scores", get(get_scores))
        .route("/sessions/{sessionId}/scores/round/{round}", get(get_scores_by_round))
        .route("/sessions/{sessionId}/report", get(get_report).post(trigger_report))
        .route("/sessions/{sessionId}/next-speaker", post(get_next_speaker))
        .route("/sessions/{sessionId}/advance-round", post(advance_round))
        .route("/books/{bookId}/topics", get(get_topics))
        .route("/books/{bookId}/sessions", post(create_session).get(get_sessions_by_book))
        .route("/books/{bookId}/optimize-topic", post(optimize_topic))
        .route("/books/{bookId}/speak/{speak_type}", post(speak))
        .route("/sessions/{sessionId}/host-commentary", post(host_commentary))
        .with_state((db, debate))
}

async fn get_roles() -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": []})))
}

async fn get_global_sessions(
    State((db, _)): State<(Arc<Database>, Arc<DebateService>)>,
    Extension(_claims): Extension<Claims>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let sessions = kbook_db::repository::debate_repo::list_all_sessions(&db.pool)
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
    State((db, _)): State<(Arc<Database>, Arc<DebateService>)>,
    Path(session_id): Path<String>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let session = kbook_db::repository::debate_repo::find_session(&db.pool, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": session})))
}

async fn delete_session(
    State((db, _)): State<(Arc<Database>, Arc<DebateService>)>,
    Path(session_id): Path<String>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    kbook_db::repository::debate_repo::delete_session(&db.pool, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn get_messages(
    State((db, _)): State<(Arc<Database>, Arc<DebateService>)>,
    Path(session_id): Path<String>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let msgs = kbook_db::repository::debate_repo::get_messages(&db.pool, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": msgs})))
}

async fn get_scores(
    State((db, _)): State<(Arc<Database>, Arc<DebateService>)>,
    Path(session_id): Path<String>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let scores = kbook_db::repository::debate_repo::get_scores(&db.pool, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": scores})))
}

async fn get_scores_by_round(
    State((db, _)): State<(Arc<Database>, Arc<DebateService>)>,
    Path((session_id, round)): Path<(String, i32)>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let scores = sqlx::query_as::<_, kbook_core::entity::DebateScore>(
        "SELECT * FROM debate_scores WHERE session_id = ? AND round_number = ? ORDER BY id ASC"
    )
    .bind(&session_id)
    .bind(round)
    .fetch_all(&db.pool)
    .await
    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": scores})))
}

async fn get_report(
    State((db, _)): State<(Arc<Database>, Arc<DebateService>)>,
    Path(session_id): Path<String>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let report = kbook_db::repository::debate_repo::get_report(&db.pool, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": report})))
}

async fn trigger_report(
    State((db, debate)): State<(Arc<Database>, Arc<DebateService>)>,
    Path(session_id): Path<String>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let content = debate.generate_report(&db.pool, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    kbook_db::repository::debate_repo::insert_report(&db.pool, &session_id, &content)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    let report = kbook_db::repository::debate_repo::get_report(&db.pool, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": report})))
}

async fn get_next_speaker(
    State((db, _)): State<(Arc<Database>, Arc<DebateService>)>,
    Path(session_id): Path<String>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let session = kbook_db::repository::debate_repo::find_session(&db.pool, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .ok_or(StatusCode::NOT_FOUND)?;

    let messages = kbook_db::repository::debate_repo::get_messages(&db.pool, &session_id)
        .await
        .unwrap_or_default();

    let pro_keys: Vec<&str> = session.pro_role_keys.as_deref().unwrap_or("").split(',').filter(|s| !s.is_empty()).collect();
    let con_keys: Vec<&str> = session.con_role_keys.as_deref().unwrap_or("").split(',').filter(|s| !s.is_empty()).collect();

    let phase_order = ["OPENING", "CROSS_EXAM", "REBUTTAL", "FREE", "CLOSING"];
    let current_idx = messages.len() / (pro_keys.len() + con_keys.len()).max(1);
    let phase_idx = (current_idx / 2).min(phase_order.len() - 1);
    let phase = phase_order[phase_idx];

    let mut all_roles: Vec<&str> = Vec::new();
    all_roles.extend(&pro_keys);
    all_roles.extend(&con_keys);

    let msg_in_phase = messages.iter().filter(|m| m.round_type.as_deref() == Some(phase)).count();
    let next_idx = msg_in_phase % all_roles.len();
    let next_role = all_roles.get(next_idx).unwrap_or(&"").to_string();

    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": next_role})))
}

async fn advance_round(
    State((db, _)): State<(Arc<Database>, Arc<DebateService>)>,
    Path(session_id): Path<String>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    kbook_db::repository::debate_repo::advance_round(&db.pool, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let session = kbook_db::repository::debate_repo::find_session(&db.pool, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .ok_or(StatusCode::NOT_FOUND)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": session})))
}

async fn get_topics(
    State((_db, _)): State<(Arc<Database>, Arc<DebateService>)>,
    Path(_book_id): Path<i64>,
    Query(_params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": []})))
}

async fn create_session(
    State((db, _)): State<(Arc<Database>, Arc<DebateService>)>,
    Extension(claims): Extension<Claims>,
    Path(book_id): Path<i64>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let topic = req.get("topic").and_then(|v| v.as_str()).unwrap_or("");
    let session_id = uuid::Uuid::new_v4().to_string();
    let session = kbook_db::repository::debate_repo::create_session(
        &db.pool, claims.sub, book_id, &session_id, topic, None,
        req.get("proRoleKeys").and_then(|v| v.as_str()),
        req.get("conRoleKeys").and_then(|v| v.as_str()),
    )
    .await
    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": session})))
}

async fn get_sessions_by_book(
    State((db, _)): State<(Arc<Database>, Arc<DebateService>)>,
    Path(book_id): Path<i64>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let sessions = kbook_db::repository::debate_repo::list_sessions_by_book(&db.pool, book_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": sessions})))
}

async fn optimize_topic(
    State((_db, _)): State<(Arc<Database>, Arc<DebateService>)>,
    Path(_book_id): Path<i64>,
    Json(_req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": null})))
}

async fn speak(
    State((db, debate)): State<(Arc<Database>, Arc<DebateService>)>,
    Path((_book_id, speak_type)): Path<(i64, String)>,
    Json(req): Json<serde_json::Value>,
) -> Result<axum::response::Sse<impl futures::Stream<Item = Result<axum::response::sse::Event, std::convert::Infallible>>>, StatusCode> {
    let session_id = req.get("sessionId").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let role_key = req.get("roleKey").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let context = req.get("opponentSpeech").or(req.get("lastSpeech")).or(req.get("defenderOpening")).or(req.get("questionContent")).or(req.get("crossExamContext")).and_then(|v| v.as_str()).unwrap_or("").to_string();

    let rx = debate.stream_speech(&db.pool, &session_id, &role_key, &role_key, "pro", &speak_type, 1, &context, "你是辩论赛参与者。请根据上下文发表你的观点。")
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

async fn host_commentary(
    State((db, _)): State<(Arc<Database>, Arc<DebateService>)>,
    Path(session_id): Path<String>,
    Json(_req): Json<serde_json::Value>,
) -> Result<axum::response::Sse<impl futures::Stream<Item = Result<axum::response::sse::Event, std::convert::Infallible>>>, StatusCode> {
    let session = kbook_db::repository::debate_repo::find_session(&db.pool, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .ok_or(StatusCode::NOT_FOUND)?;

    let _messages = kbook_db::repository::debate_repo::get_messages(&db.pool, &session_id)
        .await
        .unwrap_or_default();

    let topic = &session.topic;
    let commentary = format!(
        "主持人点评：围绕「{}」，双方辩手各抒己见。正方观点有力，反方反驳犀利。这场辩论展现了多角度的思考。",
        topic
    );

    let stream = async_stream::stream! {
        for chunk in commentary.chars().collect::<Vec<_>>().chunks(5) {
            let text: String = chunk.iter().collect();
            let data = serde_json::json!({"roleKey": "HOST", "text": text});
            yield Ok(axum::response::sse::Event::default()
                .event("message")
                .data(data.to_string()));
            tokio::time::sleep(std::time::Duration::from_millis(30)).await;
        }
        yield Ok(axum::response::sse::Event::default().event("done").data("[DONE]"));
    };

    Ok(axum::response::Sse::new(stream).keep_alive(axum::response::sse::KeepAlive::default()))
}
