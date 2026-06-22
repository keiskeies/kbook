use axum::{
    extract::{State, Extension, Path, Query, Multipart},
    http::StatusCode,
    routing::{get, put, post},
    Json, Router,
};
use std::sync::Arc;
use kbook_db::Database;
use kbook_auth::jwt::Claims;

pub fn user_routes(db: Arc<Database>) -> Router {
    Router::new()
        .route("/me", get(get_current_user))
        .route("/profile", put(update_profile))
        .route("/profile/traits", put(update_traits))
        .route("/profile/mood", put(update_mood))
        .route("/profile/book-chat-style", put(update_book_chat_style))
        .route("/profile/bio", put(update_bio))
        .route("/avatar", post(upload_avatar))
        .route("/avatar/{filename}", get(get_avatar))
        .route("/preferences", get(get_preferences))
        .route("/preferences/exclude", get(get_exclude_preferences).post(add_exclude_preference).delete(remove_exclude_preference))
        .route("/preferences/include", get(get_include_preferences).post(add_include_preference).delete(remove_include_preference))
        .with_state(db)
}

async fn get_current_user(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let user = kbook_db::repository::user_repo::find_by_id(&db.pool, claims.sub)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .ok_or(StatusCode::NOT_FOUND)?;
    Ok(Json(serde_json::json!({
        "code": 0, "message": "success",
        "data": user
    })))
}

async fn update_profile(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    if let Some(nickname) = params.get("nickname") {
        kbook_db::repository::user_repo::update_nickname(&db.pool, claims.sub, nickname)
            .await
            .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    }
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn update_traits(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    kbook_db::repository::user_repo::update_traits(
        &db.pool, claims.sub,
        req.get("birthday").and_then(|v| v.as_str()),
        req.get("gender").and_then(|v| v.as_str()),
        req.get("married").and_then(|v| v.as_bool()),
        req.get("hasChildren").and_then(|v| v.as_bool()),
        req.get("childrenAgeRanges").and_then(|v| v.as_str()),
        req.get("mbti").and_then(|v| v.as_str()),
        req.get("occupation").and_then(|v| v.as_str()),
        req.get("aspirationEducation").and_then(|v| v.as_str()),
        req.get("entrepreneurship").and_then(|v| v.as_str()),
        req.get("aspirationIncome").and_then(|v| v.as_str()),
    )
    .await
    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn update_mood(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    if let Some(mood) = params.get("mood") {
        sqlx::query("UPDATE users SET mood = ?, updated_at = datetime('now') WHERE id = ?")
            .bind(mood)
            .bind(claims.sub)
            .execute(&db.pool)
            .await
            .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    }
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn update_book_chat_style(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    if let Some(style) = params.get("style") {
        sqlx::query("UPDATE users SET book_chat_style = ?, updated_at = datetime('now') WHERE id = ?")
            .bind(style)
            .bind(claims.sub)
            .execute(&db.pool)
            .await
            .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    }
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn update_bio(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    if let Some(bio) = req.get("bio").and_then(|v| v.as_str()) {
        sqlx::query("UPDATE users SET bio = ?, updated_at = datetime('now') WHERE id = ?")
            .bind(bio)
            .bind(claims.sub)
            .execute(&db.pool)
            .await
            .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    }
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn upload_avatar(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    mut multipart: Multipart,
) -> Result<Json<serde_json::Value>, StatusCode> {
    while let Some(field) = multipart.next_field().await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
    {
        let filename = field.file_name().unwrap_or("avatar").to_string();
        let ext = filename.rsplit('.').next().unwrap_or("png");
        let stored_name = format!("{}_{}.{}", claims.sub, uuid::Uuid::new_v4(), ext);

        let app_dir = dirs::data_dir()
            .unwrap_or_else(|| std::path::PathBuf::from("."))
            .join("kbook");
        let avatar_dir = app_dir.join("data").join("avatars");
        std::fs::create_dir_all(&avatar_dir).ok();
        let file_path = avatar_dir.join(&stored_name);

        let data = field.bytes().await
            .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
        std::fs::write(&file_path, &data)
            .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

        let avatar_url = format!("/api/user/avatar/{}", stored_name);
        kbook_db::repository::user_repo::update_avatar(&db.pool, claims.sub, &avatar_url)
            .await
            .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

        return Ok(Json(serde_json::json!({
            "code": 0, "message": "success",
            "data": {"avatar": avatar_url}
        })));
    }

    Err(StatusCode::BAD_REQUEST)
}

async fn get_avatar(
    Path(filename): Path<String>,
) -> Result<axum::response::Response, StatusCode> {
    let app_dir = dirs::data_dir()
        .unwrap_or_else(|| std::path::PathBuf::from("."))
        .join("kbook");
    let path = app_dir.join("data").join("avatars").join(&filename);

    if !path.exists() {
        return Err(StatusCode::NOT_FOUND);
    }

    let mime = if filename.ends_with(".png") { "image/png" }
        else if filename.ends_with(".jpg") || filename.ends_with(".jpeg") { "image/jpeg" }
        else if filename.ends_with(".gif") { "image/gif" }
        else { "application/octet-stream" };

    let data = std::fs::read(&path).map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(axum::response::Response::builder()
        .header("Content-Type", mime)
        .body(axum::body::Body::from(data))
        .unwrap())
}

async fn get_preferences(
    State(_db): State<Arc<Database>>,
    Extension(_claims): Extension<Claims>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": []})))
}

async fn get_exclude_preferences(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let items = sqlx::query_as::<_, kbook_core::entity::UserBookPreference>(
        "SELECT * FROM user_book_preference WHERE user_id = ? AND type = 'EXCLUDE'"
    )
    .bind(claims.sub)
    .fetch_all(&db.pool)
    .await
    .unwrap_or_default();
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": items})))
}

async fn add_exclude_preference(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let category = req.get("category").and_then(|v| v.as_str()).unwrap_or("");
    let value = req.get("value").and_then(|v| v.as_str()).unwrap_or("");
    sqlx::query("INSERT OR IGNORE INTO user_book_preference (user_id, book_id, type, category, value) VALUES (?, 0, 'EXCLUDE', ?, ?)")
        .bind(claims.sub).bind(category).bind(value)
        .execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn remove_exclude_preference(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let category = params.get("category").map(|s| s.as_str()).unwrap_or("");
    let value = params.get("value").map(|s| s.as_str()).unwrap_or("");
    sqlx::query("DELETE FROM user_book_preference WHERE user_id = ? AND type = 'EXCLUDE' AND category = ? AND value = ?")
        .bind(claims.sub).bind(category).bind(value)
        .execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn get_include_preferences(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let items = sqlx::query_as::<_, kbook_core::entity::UserBookPreference>(
        "SELECT * FROM user_book_preference WHERE user_id = ? AND type = 'INCLUDE'"
    )
    .bind(claims.sub)
    .fetch_all(&db.pool)
    .await
    .unwrap_or_default();
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": items})))
}

async fn add_include_preference(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let category = req.get("category").and_then(|v| v.as_str()).unwrap_or("");
    let value = req.get("value").and_then(|v| v.as_str()).unwrap_or("");
    sqlx::query("INSERT OR IGNORE INTO user_book_preference (user_id, book_id, type, category, value) VALUES (?, 0, 'INCLUDE', ?, ?)")
        .bind(claims.sub).bind(category).bind(value)
        .execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn remove_include_preference(
    State(db): State<Arc<Database>>,
    Extension(claims): Extension<Claims>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let category = params.get("category").map(|s| s.as_str()).unwrap_or("");
    let value = params.get("value").map(|s| s.as_str()).unwrap_or("");
    sqlx::query("DELETE FROM user_book_preference WHERE user_id = ? AND type = 'INCLUDE' AND category = ? AND value = ?")
        .bind(claims.sub).bind(category).bind(value)
        .execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}
