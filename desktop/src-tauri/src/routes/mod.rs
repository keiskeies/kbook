pub mod auth;
pub mod user;
pub mod book;
pub mod progress;
pub mod comment;
pub mod ai;
pub mod debate;
pub mod round_table;
pub mod tts;
pub mod speech;
pub mod recommend;
pub mod home;
pub mod admin;
pub mod captcha;
pub mod book_trash;

use axum::{Router, middleware, routing::any, response::Response, http::StatusCode};
use std::sync::Arc;
use kbook_db::Database;
use kbook_ai::chat::AiChatService;
use kbook_ai::book_chat::BookChatService;
use kbook_ai::debate::DebateService;
use kbook_ai::round_table::RoundTableService;
use kbook_ai::recommend::RecommendService;
use tower_http::cors::{CorsLayer, Any};

use crate::routes::auth::auth_middleware;

const BOOK_BASE_URL: &str = "http://127.0.0.1:8282";

pub fn fix_cover_url(url: Option<&str>) -> Option<String> {
    url.map(|u| {
        if u.starts_with("http") { u.to_string() }
        else { format!("{}{}", BOOK_BASE_URL, u) }
    })
}

pub fn fix_book_cover(mut book: serde_json::Value) -> serde_json::Value {
    if let Some(obj) = book.as_object_mut() {
        if let Some(cu) = obj.get("coverUrl").and_then(|v| v.as_str()).map(|s| s.to_string()) {
            obj.insert("coverUrl".into(), serde_json::json!(fix_cover_url(Some(&cu))));
        }
    }
    book
}

pub fn fix_books_cover(books: Vec<kbook_core::entity::Book>) -> Vec<serde_json::Value> {
    books.into_iter().map(|b| {
        let v = serde_json::to_value(&b).unwrap_or_default();
        fix_book_cover(v)
    }).collect()
}

fn content_type_for(path: &str) -> &'static str {
    if path.ends_with(".js") { "application/javascript; charset=utf-8" }
    else if path.ends_with(".css") { "text/css; charset=utf-8" }
    else if path.ends_with(".json") { "application/json; charset=utf-8" }
    else if path.ends_with(".svg") { "image/svg+xml" }
    else if path.ends_with(".png") { "image/png" }
    else if path.ends_with(".jpg") || path.ends_with(".jpeg") { "image/jpeg" }
    else if path.ends_with(".ico") { "image/x-icon" }
    else if path.ends_with(".woff2") { "font/woff2" }
    else if path.ends_with(".webmanifest") { "application/manifest+json" }
    else { "application/octet-stream" }
}

static SPA_DIST: std::sync::LazyLock<std::path::PathBuf> = std::sync::LazyLock::new(|| {
    let exe_dir = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|p| p.to_path_buf()))
        .unwrap_or_default()
        .join("frontend/dist");
    if exe_dir.exists() {
        return exe_dir;
    }
    let dev_dir = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../frontend/dist");
    dev_dir
});

async fn spa_handler(uri: axum::http::Uri) -> Response {
    let path = uri.path().trim_start_matches('/');

    if path.starts_with("api/") {
        return StatusCode::NOT_FOUND.into_response();
    }

    if !path.is_empty() && path.starts_with("assets/") {
        let file_path = SPA_DIST.join(path);
        if let Ok(content) = tokio::fs::read(&file_path).await {
            return Response::builder()
                .header(axum::http::header::CONTENT_TYPE, content_type_for(path))
                .body(axum::body::Body::from(content))
                .unwrap();
        }
        return StatusCode::NOT_FOUND.into_response();
    }

    if !path.is_empty() {
        let file_path = SPA_DIST.join(path);
        if file_path.exists() && file_path.is_file() {
            if let Ok(content) = tokio::fs::read(&file_path).await {
                return Response::builder()
                    .header(axum::http::header::CONTENT_TYPE, content_type_for(path))
                    .body(axum::body::Body::from(content))
                    .unwrap();
            }
        }
    }

    match tokio::fs::read(SPA_DIST.join("index.html")).await {
        Ok(content) => Response::builder()
            .header(axum::http::header::CONTENT_TYPE, "text/html; charset=utf-8")
            .body(axum::body::Body::from(content))
            .unwrap(),
        Err(_) => StatusCode::NOT_FOUND.into_response(),
    }
}

use axum::response::IntoResponse;

pub fn create_router(
    db: Arc<Database>,
    ai_chat: Arc<AiChatService>,
    _book_chat: Arc<BookChatService>,
    debate: Arc<DebateService>,
    round_table: Arc<RoundTableService>,
    recommend: Arc<RecommendService>,
) -> Router {
    let auth_middleware = middleware::from_fn(auth_middleware);

    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods(Any)
        .allow_headers(Any);

    Router::new()
        .nest("/api/auth", auth::auth_routes(db.clone()))
        .nest("/api/user", user::user_routes(db.clone()).layer(auth_middleware.clone()))
        .nest("/api/books", book::book_routes(db.clone(), _book_chat.clone()))
        .nest("/api/book-trash", book_trash::book_trash_routes(db.clone()).layer(auth_middleware.clone()))
        .nest("/api/progress", progress::progress_routes(db.clone()).layer(auth_middleware.clone()))
        .nest("/api/comment", comment::comment_routes(db.clone()).layer(auth_middleware.clone()))
        .nest("/api/ai", ai::ai_routes(db.clone(), ai_chat.clone()).layer(auth_middleware.clone()))
        .nest("/api/debate", debate::debate_routes(db.clone(), debate.clone()).layer(auth_middleware.clone()))
        .nest("/api/round-table", round_table::round_table_routes(db.clone(), round_table.clone()).layer(auth_middleware.clone()))
        .nest("/api/tts", tts::tts_routes(db.clone()))
        .nest("/api/speech", speech::speech_routes(db.clone()))
        .nest("/api/captcha", captcha::captcha_routes(db.clone()))
        .nest("/api/recommend", recommend::recommend_routes(db.clone(), recommend.clone()).layer(auth_middleware.clone()))
        .nest("/api", home::home_routes(db.clone()).layer(auth_middleware.clone()))
        .nest("/api/admin", admin::admin_routes(db.clone()).layer(auth_middleware.clone()))
        .layer(cors)
        .fallback(any(spa_handler))
}
