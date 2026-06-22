use axum::{
    extract::{State, Query},
    http::StatusCode,
    routing::{get, post},
    Json, Router,
};
use std::sync::Arc;
use kbook_db::Database;

pub fn tts_routes(db: Arc<Database>) -> Router {
    Router::new()
        .route("/config/active", get(get_active_config))
        .route("/streaming-supported", get(check_streaming_supported))
        .route("/gpt-sovits/voices", get(get_gpt_sovits_voices))
        .route("/synthesize", post(synthesize).get(synthesize))
        .route("/synthesize/stream", get(synthesize_stream))
        .with_state(db)
}

async fn get_active_config(
    State(_db): State<Arc<Database>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": null})))
}

async fn check_streaming_supported(
    Query(_params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": false})))
}

async fn get_gpt_sovits_voices() -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": []})))
}

async fn synthesize() -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": null})))
}

async fn synthesize_stream() -> Result<axum::response::Sse<impl futures::Stream<Item = Result<axum::response::sse::Event, std::convert::Infallible>>>, StatusCode> {
    let stream = async_stream::stream! {
        yield Ok(axum::response::sse::Event::default().event("done").data(""));
    };
    Ok(axum::response::Sse::new(stream).keep_alive(axum::response::sse::KeepAlive::default()))
}
