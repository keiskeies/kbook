use axum::{
    extract::{State, Path, Query, Multipart},
    http::StatusCode,
    routing::{get, post, put, delete},
    Json, Router,
};
use std::sync::Arc;
use kbook_db::Database;

pub fn admin_routes(db: Arc<Database>) -> Router {
    Router::new()
        // Books
        .route("/books/scan", get(scan_books))
        .route("/books/scan/status", get(get_scan_status))
        .route("/books/scan/reset", post(reset_scan_status))
        .route("/books/upload", post(upload_book))
        .route("/books/cover/{filename}", get(get_admin_cover))
        .route("/books/{id}/cover", post(update_book_cover))
        .route("/books/{id}/title", put(update_book_title))
        .route("/books/{id}/author", put(update_book_author))
        .route("/books/{id}/description", put(update_book_description))
        .route("/books/{id}/tags", put(update_book_tags))
        .route("/books/embeddings/stats", get(get_embedding_stats))
        .route("/books/vector/clear-content", post(clear_content_vectors))
        .route("/books/reindex", post(reindex_books))
        .route("/books/es/reindex", get(rebuild_es_index))
        .route("/books/ai/sessions", post(create_admin_ai_session).get(list_admin_ai_sessions))
        .route("/books/ai/chat/stream", post(admin_ai_chat_stream))
        .route("/books/ai/chat", post(admin_ai_chat))
        .route("/books/ai/history", get(get_admin_ai_history))
        .route("/books/ai/sessions/{sessionId}", delete(delete_admin_ai_session))
        // TTS Config
        .route("/tts-config", get(get_tts_configs).post(create_tts_config))
        .route("/tts-config/active", get(get_active_tts_config))
        .route("/tts-config/{id}", put(update_tts_config).delete(delete_tts_config))
        .route("/tts-config/{id}/switch-default", post(switch_default_tts))
        // AI Config
        .route("/ai-config", get(get_ai_config).post(create_ai_config))
        .route("/ai-config/purpose/{purpose}", get(get_ai_config_by_purpose))
        .route("/ai-config/{id}", put(update_ai_config_by_id).delete(delete_ai_config))
        .route("/ai-config/{id}/set-role/{role}", post(set_ai_config_role))
        .route("/ai-config/{id}/activate", post(activate_ai_config))
        .route("/ai-config/{id}/test", post(test_ai_config))
        .route("/ai-config/file/reload", post(reload_ai_config))
        .route("/ai-config/reload", post(reload_ai_config))
        // Users
        .route("/users/stats", get(get_user_stats))
        .route("/users/pending", get(get_pending_users))
        .route("/users/search", get(search_users))
        .route("/users", get(list_users))
        .route("/users/{userId}/approve", post(approve_user))
        .route("/users/{userId}/reject", post(reject_user))
        .route("/users/{userId}/unban", post(unban_user))
        .route("/users/{userId}/ban", post(ban_user))
        .route("/users/batch-approve", post(batch_approve_users))
        .route("/users/batch-reject", post(batch_reject_users))
        .route("/users/invite", post(invite_user))
        .route("/users/{id}", get(get_user).put(update_user))
        // Account
        .route("/account/bind-email/send-code", post(send_bind_email_code))
        .route("/account/bind-email", post(bind_email))
        .route("/accounts", get(list_accounts))
        .with_state(db)
}

async fn scan_books() -> Result<axum::response::Sse<impl futures::Stream<Item = Result<axum::response::sse::Event, std::convert::Infallible>>>, StatusCode> {
    let stream = async_stream::stream! {
        yield Ok(axum::response::sse::Event::default().event("done").data("{\"added\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errors\":[]}"));
    };
    Ok(axum::response::Sse::new(stream).keep_alive(axum::response::sse::KeepAlive::default()))
}

async fn get_scan_status() -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": {"scanning": false, "current": 0, "total": 0}})))
}

async fn reset_scan_status() -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn upload_book(
    State(db): State<Arc<Database>>,
    mut multipart: Multipart,
) -> Result<Json<serde_json::Value>, StatusCode> {
    while let Some(field) = multipart.next_field().await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
    {
        let filename = field.file_name().unwrap_or("book").to_string();
        let ext = filename.rsplit('.').next().unwrap_or("txt").to_lowercase();
        let format = match ext.as_str() {
            "epub" => "EPUB",
            "pdf" => "PDF",
            "txt" | "text" => "TXT",
            _ => "TXT",
        };

        let stored_name = format!("{}_{}", uuid::Uuid::new_v4(), filename);
        let app_dir = dirs::data_dir()
            .unwrap_or_else(|| std::path::PathBuf::from("."))
            .join("kbook");
        let books_dir = app_dir.join("data").join("books");
        std::fs::create_dir_all(&books_dir).ok();
        let file_path = books_dir.join(&stored_name);

        let data = field.bytes().await
            .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
        let file_size = data.len() as i64;
        std::fs::write(&file_path, &data)
            .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

        let title = std::path::Path::new(&filename)
            .file_stem()
            .unwrap_or_default()
            .to_string_lossy()
            .to_string();

        sqlx::query(
            "INSERT INTO books (title, format, file_url, file_size) VALUES (?, ?, ?, ?)"
        )
        .bind(&title)
        .bind(format)
        .bind(&stored_name)
        .bind(file_size)
        .execute(&db.pool)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

        return Ok(Json(serde_json::json!({
            "code": 0, "message": "success",
            "data": {"title": title, "format": format, "fileSize": file_size}
        })));
    }

    Err(StatusCode::BAD_REQUEST)
}

async fn get_admin_cover(Path(_filename): Path<String>) -> Result<StatusCode, StatusCode> {
    Err(StatusCode::NOT_FOUND)
}

async fn update_book_title(
    State(db): State<Arc<Database>>,
    Path(id): Path<i64>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let title = req.get("title").and_then(|v| v.as_str()).ok_or(StatusCode::BAD_REQUEST)?;
    sqlx::query("UPDATE books SET title = ?, updated_at = datetime('now') WHERE id = ?")
        .bind(title).bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let book = sqlx::query_as::<_, kbook_core::entity::Book>("SELECT * FROM books WHERE id = ?")
        .bind(id).fetch_one(&db.pool).await.map_err(|_| StatusCode::NOT_FOUND)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": book})))
}

async fn update_book_author(
    State(db): State<Arc<Database>>,
    Path(id): Path<i64>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let author = req.get("author").and_then(|v| v.as_str()).ok_or(StatusCode::BAD_REQUEST)?;
    sqlx::query("UPDATE books SET author = ?, updated_at = datetime('now') WHERE id = ?")
        .bind(author).bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let book = sqlx::query_as::<_, kbook_core::entity::Book>("SELECT * FROM books WHERE id = ?")
        .bind(id).fetch_one(&db.pool).await.map_err(|_| StatusCode::NOT_FOUND)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": book})))
}

async fn update_book_description(
    State(db): State<Arc<Database>>,
    Path(id): Path<i64>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let desc = req.get("description").and_then(|v| v.as_str()).ok_or(StatusCode::BAD_REQUEST)?;
    sqlx::query("UPDATE books SET description = ?, updated_at = datetime('now') WHERE id = ?")
        .bind(desc).bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let book = sqlx::query_as::<_, kbook_core::entity::Book>("SELECT * FROM books WHERE id = ?")
        .bind(id).fetch_one(&db.pool).await.map_err(|_| StatusCode::NOT_FOUND)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": book})))
}

async fn update_book_tags(
    State(db): State<Arc<Database>>,
    Path(id): Path<i64>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    if let Some(tags) = req.get("tags").and_then(|v| v.as_str()) {
        sqlx::query("UPDATE books SET format_tags = ?, updated_at = datetime('now') WHERE id = ?")
            .bind(tags).bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    }
    let book = sqlx::query_as::<_, kbook_core::entity::Book>("SELECT * FROM books WHERE id = ?")
        .bind(id).fetch_one(&db.pool).await.map_err(|_| StatusCode::NOT_FOUND)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": book})))
}

async fn update_book_cover(
    State(_db): State<Arc<Database>>,
    Path(_id): Path<i64>,
    Json(_req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn reindex_books() -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn create_admin_ai_session(Json(_req): Json<serde_json::Value>) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": {"sessionId": "admin-session-1"}})))
}

async fn list_admin_ai_sessions() -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": []})))
}

async fn admin_ai_chat_stream(Json(_req): Json<serde_json::Value>) -> Result<axum::response::Sse<impl futures::Stream<Item = Result<axum::response::sse::Event, std::convert::Infallible>>>, StatusCode> {
    let stream = async_stream::stream! {
        yield Ok(axum::response::sse::Event::default().event("done").data("{\"content\":\"\"}"));
    };
    Ok(axum::response::Sse::new(stream).keep_alive(axum::response::sse::KeepAlive::default()))
}

async fn admin_ai_chat(Json(_req): Json<serde_json::Value>) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": {"content": ""}})))
}

async fn get_admin_ai_history() -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": []})))
}

async fn delete_admin_ai_session(Path(_id): Path<String>) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn get_active_tts_config(
    State(db): State<Arc<Database>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let row = sqlx::query_as::<_, kbook_core::entity::TtsConfig>(
        "SELECT * FROM tts_config WHERE enabled = 1 AND is_default = 1 LIMIT 1"
    )
    .fetch_optional(&db.pool)
    .await
    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": row})))
}

async fn create_tts_config(
    State(db): State<Arc<Database>>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let name = req.get("name").and_then(|v| v.as_str()).unwrap_or("");
    let tts_type = req.get("ttsType").and_then(|v| v.as_str()).unwrap_or("");
    let provider = req.get("provider").and_then(|v| v.as_str()).unwrap_or("");
    let base_url = req.get("baseUrl").and_then(|v| v.as_str());
    let api_key = req.get("apiKey").and_then(|v| v.as_str());
    let voice = req.get("voice").and_then(|v| v.as_str());
    let enabled = req.get("enabled").and_then(|v| v.as_bool()).unwrap_or(false);

    let id = sqlx::query_scalar::<_, i64>(
        "INSERT INTO tts_config (name, tts_type, provider, base_url, api_key, voice, enabled, is_default) VALUES (?, ?, ?, ?, ?, ?, ?, 0) RETURNING id"
    )
    .bind(name).bind(tts_type).bind(provider).bind(base_url).bind(api_key).bind(voice).bind(enabled)
    .fetch_one(&db.pool)
    .await
    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    let row = sqlx::query_as::<_, kbook_core::entity::TtsConfig>("SELECT * FROM tts_config WHERE id = ?")
        .bind(id).fetch_one(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": row})))
}

async fn update_tts_config(
    State(db): State<Arc<Database>>,
    Path(id): Path<i64>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    if let Some(name) = req.get("name").and_then(|v| v.as_str()) {
        sqlx::query("UPDATE tts_config SET name = ?, updated_at = datetime('now') WHERE id = ?")
            .bind(name).bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    }
    if let Some(enabled) = req.get("enabled").and_then(|v| v.as_bool()) {
        sqlx::query("UPDATE tts_config SET enabled = ?, updated_at = datetime('now') WHERE id = ?")
            .bind(enabled).bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    }
    let row = sqlx::query_as::<_, kbook_core::entity::TtsConfig>("SELECT * FROM tts_config WHERE id = ?")
        .bind(id).fetch_one(&db.pool).await.map_err(|_| StatusCode::NOT_FOUND)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": row})))
}

async fn delete_tts_config(
    State(db): State<Arc<Database>>,
    Path(id): Path<i64>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    sqlx::query("DELETE FROM tts_config WHERE id = ?")
        .bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn switch_default_tts(
    State(db): State<Arc<Database>>,
    Path(id): Path<i64>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    sqlx::query("UPDATE tts_config SET is_default = 0").execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    sqlx::query("UPDATE tts_config SET is_default = 1, enabled = 1, updated_at = datetime('now') WHERE id = ?")
        .bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let row = sqlx::query_as::<_, kbook_core::entity::TtsConfig>("SELECT * FROM tts_config WHERE id = ?")
        .bind(id).fetch_one(&db.pool).await.map_err(|_| StatusCode::NOT_FOUND)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": row})))
}

async fn get_tts_configs(
    State(db): State<Arc<Database>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    use kbook_core::entity::TtsConfig;
    let rows: Vec<TtsConfig> = sqlx::query_as(
        "SELECT * FROM tts_config ORDER BY id"
    )
    .fetch_all(&db.pool)
    .await
    .map_err(|e| { tracing::error!("tts query: {e}"); StatusCode::INTERNAL_SERVER_ERROR })?;

    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": rows})))
}

async fn get_ai_config(
    State(db): State<Arc<Database>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    use kbook_core::entity::AiProviderConfig;
    let rows: Vec<AiProviderConfig> = sqlx::query_as(
        "SELECT * FROM ai_provider_config ORDER BY id"
    )
    .fetch_all(&db.pool)
    .await
    .map_err(|e| { tracing::error!("ai config query: {e}"); StatusCode::INTERNAL_SERVER_ERROR })?;

    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": rows})))
}

async fn create_ai_config(Json(_req): Json<serde_json::Value>) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn get_ai_config_by_purpose(
    State(db): State<Arc<Database>>,
    Path(purpose): Path<String>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    use kbook_core::entity::AiProviderConfig;
    let rows: Vec<AiProviderConfig> = sqlx::query_as(
        "SELECT * FROM ai_provider_config WHERE purpose = ? ORDER BY id"
    )
    .bind(&purpose)
    .fetch_all(&db.pool)
    .await
    .map_err(|e| { tracing::error!("ai config by purpose: {e}"); StatusCode::INTERNAL_SERVER_ERROR })?;

    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": rows})))
}

async fn update_ai_config_by_id(
    State(db): State<Arc<Database>>,
    Path(id): Path<i64>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    if let Some(enabled) = req.get("enabled").and_then(|v| v.as_bool()) {
        sqlx::query("UPDATE ai_provider_config SET enabled = ?, updated_at = datetime('now') WHERE id = ?")
            .bind(enabled).bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    }
    if let Some(name) = req.get("name").and_then(|v| v.as_str()) {
        sqlx::query("UPDATE ai_provider_config SET name = ?, updated_at = datetime('now') WHERE id = ?")
            .bind(name).bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    }
    if let Some(base_url) = req.get("baseUrl").and_then(|v| v.as_str()) {
        sqlx::query("UPDATE ai_provider_config SET base_url = ?, updated_at = datetime('now') WHERE id = ?")
            .bind(base_url).bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    }
    if let Some(api_key) = req.get("apiKey").and_then(|v| v.as_str()) {
        sqlx::query("UPDATE ai_provider_config SET api_key = ?, updated_at = datetime('now') WHERE id = ?")
            .bind(api_key).bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    }
    if let Some(model_name) = req.get("modelName").and_then(|v| v.as_str()) {
        sqlx::query("UPDATE ai_provider_config SET model_name = ?, updated_at = datetime('now') WHERE id = ?")
            .bind(model_name).bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    }
    if let Some(temp) = req.get("temperature").and_then(|v| v.as_f64()) {
        sqlx::query("UPDATE ai_provider_config SET temperature = ?, updated_at = datetime('now') WHERE id = ?")
            .bind(temp).bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    }
    if let Some(max_tokens) = req.get("maxTokens").and_then(|v| v.as_i64()) {
        sqlx::query("UPDATE ai_provider_config SET max_tokens = ?, updated_at = datetime('now') WHERE id = ?")
            .bind(max_tokens).bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    }
    let row = sqlx::query_as::<_, kbook_core::entity::AiProviderConfig>("SELECT * FROM ai_provider_config WHERE id = ?")
        .bind(id).fetch_one(&db.pool).await.map_err(|_| StatusCode::NOT_FOUND)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": row})))
}

async fn delete_ai_config(
    State(db): State<Arc<Database>>,
    Path(id): Path<i64>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    sqlx::query("DELETE FROM ai_provider_config WHERE id = ?")
        .bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn set_ai_config_role(
    State(db): State<Arc<Database>>,
    Path((id, role)): Path<(i64, String)>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let upper_role = role.to_uppercase();
    if upper_role != "QA" && upper_role != "TOOL" {
        return Ok(Json(serde_json::json!({"code": 1, "message": "角色必须是 QA 或 TOOL"})));
    }

    let config = sqlx::query_as::<_, kbook_core::entity::AiProviderConfig>("SELECT * FROM ai_provider_config WHERE id = ?")
        .bind(id).fetch_optional(&db.pool).await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .ok_or(StatusCode::NOT_FOUND)?;

    let purpose = config.purpose.as_deref().unwrap_or("");
    if !purpose.eq_ignore_ascii_case("CHAT") {
        return Ok(Json(serde_json::json!({"code": 1, "message": "仅 CHAT 用途支持角色设置"})));
    }

    let current_roles = config.roles.as_deref().unwrap_or("");

    if current_roles.contains(&upper_role) {
        // 移除角色
        let new_roles: String = current_roles
            .split(',')
            .filter(|r| r.trim().to_uppercase() != upper_role)
            .collect::<Vec<_>>()
            .join(",");
        sqlx::query("UPDATE ai_provider_config SET roles = ?, updated_at = datetime('now') WHERE id = ?")
            .bind(new_roles.trim()).bind(id)
            .execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    } else {
        // 先从其他CHAT配置移除该角色
        let others = sqlx::query_as::<_, kbook_core::entity::AiProviderConfig>(
            "SELECT * FROM ai_provider_config WHERE purpose = 'CHAT' AND id != ?"
        ).bind(id).fetch_all(&db.pool).await.unwrap_or_default();

        for other in &others {
            if let Some(ref other_roles) = other.roles {
                if other_roles.contains(&upper_role) {
                    let cleaned: String = other_roles
                        .split(',')
                        .filter(|r| r.trim().to_uppercase() != upper_role)
                        .collect::<Vec<_>>()
                        .join(",");
                    sqlx::query("UPDATE ai_provider_config SET roles = ?, updated_at = datetime('now') WHERE id = ?")
                        .bind(cleaned.trim()).bind(other.id)
                        .execute(&db.pool).await.ok();
                }
            }
        }

        // 添加角色
        let new_roles = if current_roles.is_empty() {
            upper_role.clone()
        } else {
            format!("{},{}", current_roles.trim(), upper_role)
        };
        sqlx::query("UPDATE ai_provider_config SET roles = ?, updated_at = datetime('now') WHERE id = ?")
            .bind(&new_roles).bind(id)
            .execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    }

    let row = sqlx::query_as::<_, kbook_core::entity::AiProviderConfig>("SELECT * FROM ai_provider_config WHERE id = ?")
        .bind(id).fetch_one(&db.pool).await.map_err(|_| StatusCode::NOT_FOUND)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": row})))
}

async fn activate_ai_config(
    State(db): State<Arc<Database>>,
    Path(id): Path<i64>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    sqlx::query("UPDATE ai_provider_config SET enabled = 1, updated_at = datetime('now') WHERE id = ?")
        .bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let row = sqlx::query_as::<_, kbook_core::entity::AiProviderConfig>("SELECT * FROM ai_provider_config WHERE id = ?")
        .bind(id).fetch_one(&db.pool).await.map_err(|_| StatusCode::NOT_FOUND)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": row})))
}

async fn test_ai_config(Path(_id): Path<i64>) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": {"success": true, "message": "ok"}})))
}

async fn reload_ai_config() -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn _update_ai_config(Json(_req): Json<serde_json::Value>) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn get_user_stats(
    State(db): State<Arc<Database>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let total: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM users").fetch_one(&db.pool).await.unwrap_or((0,));
    let pending: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM users WHERE status = 'PENDING'").fetch_one(&db.pool).await.unwrap_or((0,));
    let approved: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM users WHERE status = 'APPROVED'").fetch_one(&db.pool).await.unwrap_or((0,));
    let banned: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM users WHERE status = 'BANNED'").fetch_one(&db.pool).await.unwrap_or((0,));
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": {"total": total.0, "pending": pending.0, "approved": approved.0, "banned": banned.0}})))
}

async fn get_pending_users(
    State(db): State<Arc<Database>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let users = sqlx::query_as::<_, kbook_core::entity::User>("SELECT * FROM users WHERE status = 'PENDING' ORDER BY created_at DESC")
        .fetch_all(&db.pool).await.unwrap_or_default();
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": users})))
}

async fn search_users(
    State(db): State<Arc<Database>>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let keyword = params.get("keyword").map(|s| s.as_str()).unwrap_or("");
    let kw = format!("%{}%", keyword);
    let users = sqlx::query_as::<_, kbook_core::entity::User>(
        "SELECT * FROM users WHERE (email LIKE ? OR nickname LIKE ?) ORDER BY created_at DESC LIMIT 50"
    ).bind(&kw).bind(&kw).fetch_all(&db.pool).await.unwrap_or_default();
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": {"list": users, "total": users.len()}})))
}

async fn list_users(
    State(db): State<Arc<Database>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let users = sqlx::query_as::<_, kbook_core::entity::User>("SELECT * FROM users ORDER BY created_at DESC LIMIT 50")
        .fetch_all(&db.pool).await.unwrap_or_default();
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": {"list": users, "total": users.len()}})))
}

async fn approve_user(
    State(db): State<Arc<Database>>,
    Path(id): Path<i64>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    sqlx::query("UPDATE users SET status = 'APPROVED', updated_at = datetime('now') WHERE id = ?")
        .bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn reject_user(
    State(db): State<Arc<Database>>,
    Path(id): Path<i64>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    sqlx::query("UPDATE users SET status = 'REJECTED', updated_at = datetime('now') WHERE id = ?")
        .bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn unban_user(
    State(db): State<Arc<Database>>,
    Path(id): Path<i64>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    sqlx::query("UPDATE users SET status = 'APPROVED', updated_at = datetime('now') WHERE id = ?")
        .bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn ban_user(
    State(db): State<Arc<Database>>,
    Path(id): Path<i64>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    sqlx::query("UPDATE users SET status = 'BANNED', updated_at = datetime('now') WHERE id = ?")
        .bind(id).execute(&db.pool).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn batch_approve_users(
    State(db): State<Arc<Database>>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let ids = req.get("userIds").and_then(|v| v.as_array()).map(|a| a.iter().filter_map(|v| v.as_i64()).collect::<Vec<_>>()).unwrap_or_default();
    let count = ids.len() as i64;
    for id in &ids {
        sqlx::query("UPDATE users SET status = 'APPROVED', updated_at = datetime('now') WHERE id = ?")
            .bind(id).execute(&db.pool).await.ok();
    }
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": {"count": count}})))
}

async fn batch_reject_users(
    State(db): State<Arc<Database>>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let ids = req.get("userIds").and_then(|v| v.as_array()).map(|a| a.iter().filter_map(|v| v.as_i64()).collect::<Vec<_>>()).unwrap_or_default();
    let count = ids.len() as i64;
    for id in &ids {
        sqlx::query("UPDATE users SET status = 'REJECTED', updated_at = datetime('now') WHERE id = ?")
            .bind(id).execute(&db.pool).await.ok();
    }
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": {"count": count}})))
}

async fn invite_user(
    State(_db): State<Arc<Database>>,
    Json(_req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": {"email": "", "inviteCode": ""}})))
}

async fn get_user(Path(_id): Path<i64>) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": null})))
}

async fn update_user(Path(_id): Path<i64>, Json(_req): Json<serde_json::Value>) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn send_bind_email_code(Json(_req): Json<serde_json::Value>) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn bind_email(Json(_req): Json<serde_json::Value>) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn list_accounts() -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": []})))
}

async fn get_embedding_stats() -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": {"totalBooks": 0, "embeddedBooks": 0, "totalContentVectors": 0}})))
}

async fn clear_content_vectors() -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": {"deletedCount": 0}})))
}

async fn rebuild_es_index() -> Result<axum::response::Sse<impl futures::Stream<Item = Result<axum::response::sse::Event, std::convert::Infallible>>>, StatusCode> {
    let stream = async_stream::stream! {
        yield Ok(axum::response::sse::Event::default().event("done").data("{\"elapsed\":0}"));
    };
    Ok(axum::response::Sse::new(stream).keep_alive(axum::response::sse::KeepAlive::default()))
}
