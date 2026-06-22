use axum::{
    extract::{State, Path, Query, Extension},
    http::StatusCode,
    routing::{get, post},
    Json, Router,
};
use std::sync::Arc;
use kbook_db::Database;
use kbook_ai::book_chat::BookChatService;
use kbook_ai::llm::ChatMessage;
use kbook_ai::prompt::SPEED_READ_SYSTEM_PROMPT;
use super::{fix_book_cover, fix_books_cover};

pub type BookState = (Arc<Database>, Arc<BookChatService>);

pub fn book_routes(db: Arc<Database>, book_chat: Arc<BookChatService>) -> Router {
    let state = (db.clone(), book_chat.clone());

    Router::new()
        .route("/search", get(search_books))
        .route("/suggest", get(suggest_books))
        .route("/rank/read", get(get_read_rank))
        .route("/rank/rating", get(get_rating_rank))
        .route("/rank/new", get(get_new_rank))
        .route("/rank/hot", get(get_hot_rank))
        .route("/format/{format}", get(get_books_by_format))
        .route("/tag/{tag}", get(get_books_by_tag))
        .route("/{id}", get(get_book))
        .route("/{id}/rate", post(rate_book))
        .route("/{id}/file", get(get_book_file))
        .route("/{id}/speed-read/stream", post(speed_read_stream))
        .route("/cover/{filename}", get(get_cover))
        .route("/{bookId}/chat/stream", post(stream_book_chat))
        .route("/{bookId}/chat/suggestions", get(get_suggestions))
        .route("/{bookId}/chat/history", get(get_history))
        .route("/{bookId}/chat/sessions", get(get_sessions))
        .route("/{bookId}/chat/follow-up", post(generate_follow_up))
        .with_state(state)
}

async fn get_book(
    State((db, _)): State<BookState>,
    Path(id): Path<i64>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let book = kbook_db::repository::book_repo::find_by_id(&db.pool, id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .ok_or(StatusCode::NOT_FOUND)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": fix_book_cover(serde_json::to_value(&book).unwrap_or_default())})))
}

async fn search_books(
    State((db, _)): State<BookState>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let keyword = params.get("q").or(params.get("keyword")).map(|s| s.as_str());
    let tag = params.get("tag").map(|s| s.as_str());
    let page = params.get("page").and_then(|s| s.parse::<i32>().ok()).unwrap_or(1);
    let size = params.get("size").and_then(|s| s.parse::<i32>().ok()).unwrap_or(20);
    let (books, total) = kbook_db::repository::book_repo::search(&db.pool, keyword, None, tag, page, size)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({
        "code": 0, "message": "success",
        "data": { "list": fix_books_cover(books), "total": total, "page": page, "size": size }
    })))
}

async fn suggest_books(
    State((db, _)): State<BookState>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let keyword = params.get("keyword").map(|s| s.as_str()).unwrap_or("");
    let data = kbook_db::repository::book_repo::suggest(&db.pool, keyword)
        .await
        .unwrap_or_default();
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": data})))
}

async fn get_read_rank(
    State((db, _)): State<BookState>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let page = params.get("page").and_then(|s| s.parse::<i32>().ok()).unwrap_or(1);
    let size = params.get("size").and_then(|s| s.parse::<i32>().ok()).unwrap_or(20);
    let (books, total) = kbook_db::repository::book_repo::get_read_rank(&db.pool, page, size)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({
        "code": 0, "message": "success",
        "data": { "list": fix_books_cover(books), "total": total, "page": page, "size": size }
    })))
}

async fn get_rating_rank(
    State((db, _)): State<BookState>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let page = params.get("page").and_then(|s| s.parse::<i32>().ok()).unwrap_or(1);
    let size = params.get("size").and_then(|s| s.parse::<i32>().ok()).unwrap_or(20);
    let (books, total) = kbook_db::repository::book_repo::get_rating_rank(&db.pool, page, size)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({
        "code": 0, "message": "success",
        "data": { "list": fix_books_cover(books), "total": total, "page": page, "size": size }
    })))
}

async fn get_new_rank(
    State((db, _)): State<BookState>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let page = params.get("page").and_then(|s| s.parse::<i32>().ok()).unwrap_or(1);
    let size = params.get("size").and_then(|s| s.parse::<i32>().ok()).unwrap_or(20);
    let (books, total) = kbook_db::repository::book_repo::search(&db.pool, None, None, None, page, size)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({
        "code": 0, "message": "success",
        "data": { "list": fix_books_cover(books), "total": total, "page": page, "size": size }
    })))
}

async fn get_hot_rank(
    State((db, _)): State<BookState>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let page = params.get("page").and_then(|s| s.parse::<i32>().ok()).unwrap_or(1);
    let size = params.get("size").and_then(|s| s.parse::<i32>().ok()).unwrap_or(20);
    let (books, total) = kbook_db::repository::book_repo::get_read_rank(&db.pool, page, size)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({
        "code": 0, "message": "success",
        "data": { "list": fix_books_cover(books), "total": total, "page": page, "size": size }
    })))
}

async fn get_books_by_format(
    State((db, _)): State<BookState>,
    Path(format): Path<String>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let page = params.get("page").and_then(|s| s.parse::<i32>().ok()).unwrap_or(1);
    let size = params.get("size").and_then(|s| s.parse::<i32>().ok()).unwrap_or(20);
    let (books, total) = kbook_db::repository::book_repo::search(&db.pool, None, Some(&format), None, page, size)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({
        "code": 0, "message": "success",
        "data": { "list": fix_books_cover(books), "total": total, "page": page, "size": size }
    })))
}

async fn get_books_by_tag(
    State((db, _)): State<BookState>,
    Path(tag): Path<String>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let page = params.get("page").and_then(|s| s.parse::<i32>().ok()).unwrap_or(1);
    let size = params.get("size").and_then(|s| s.parse::<i32>().ok()).unwrap_or(20);
    let (books, total) = kbook_db::repository::book_repo::search(&db.pool, None, None, Some(&tag), page, size)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({
        "code": 0, "message": "success",
        "data": { "list": fix_books_cover(books), "total": total, "page": page, "size": size }
    })))
}

async fn rate_book(
    State((db, _)): State<BookState>,
    Path(id): Path<i64>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let rating = req.get("rating").and_then(|v| v.as_f64()).ok_or(StatusCode::BAD_REQUEST)?;
    if rating < 1.0 || rating > 5.0 { return Err(StatusCode::BAD_REQUEST); }
    let rating = (rating * 10.0).round() / 10.0;
    kbook_db::repository::book_repo::rate(&db.pool, id, rating, 0)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}

async fn get_cover(
    State(_db): State<BookState>,
    Path(filename): Path<String>,
) -> Result<axum::response::Response, StatusCode> {
    let paths_to_try = vec![
        std::path::PathBuf::from("G:/图书/covers").join(&*filename),
        std::path::PathBuf::from("G:/图书/covers"),
        dirs::data_dir().unwrap_or_default().join("kbook").join("data").join("covers").join(&*filename),
        dirs::data_dir().unwrap_or_default().join("kbook").join("covers").join(&*filename),
        std::env::current_exe().ok().and_then(|p| p.parent().map(|p| p.to_path_buf())).unwrap_or_default().join("data/covers").join(&*filename),
    ];

    for path in &paths_to_try {
        if path.is_file() {
            let data = tokio::fs::read(path).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
            let mime = if filename.ends_with(".png") { "image/png" }
                else if filename.ends_with(".jpg") || filename.ends_with(".jpeg") { "image/jpeg" }
                else if filename.ends_with(".svg") { "image/svg+xml" }
                else if filename.ends_with(".webp") { "image/webp" }
                else { "application/octet-stream" };
            return Ok(axum::response::Response::builder()
                .header("Content-Type", mime)
                .header("Cache-Control", "public, max-age=86400")
                .body(axum::body::Body::from(data)).unwrap());
        }
    }

    Err(StatusCode::NOT_FOUND)
}

// === Book Chat routes ===

async fn stream_book_chat(
    State((db, book_chat)): State<BookState>,
    claims: Option<Extension<kbook_auth::jwt::Claims>>,
    Path(book_id): Path<i64>,
    Json(req): Json<serde_json::Value>,
) -> Result<axum::response::Sse<impl futures::Stream<Item = Result<axum::response::sse::Event, std::convert::Infallible>>>, StatusCode> {
    let message = req.get("message").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let session_id = req.get("sessionId").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let user_id = claims.map(|c| c.sub).unwrap_or(0);

    let rx = book_chat.stream_book_chat(&db.pool, user_id, book_id, &message, &session_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    let stream = async_stream::stream! {
        let mut rx = rx;
        while let Some(result) = rx.recv().await {
            match result {
                Ok(text) => {
                    let data = serde_json::json!({"content": text});
                    yield Ok(axum::response::sse::Event::default().data(data.to_string()));
                }
                Err(e) => {
                    let data = serde_json::json!({"error": e.to_string()});
                    yield Ok(axum::response::sse::Event::default().event("error").data(data.to_string()));
                }
            }
        }
        yield Ok(axum::response::sse::Event::default().event("done").data(""));
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
    Path(book_id): Path<i64>,
    Query(params): Query<std::collections::HashMap<String, String>>,
    claims: Option<Extension<kbook_auth::jwt::Claims>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let user_id = claims.map(|c| c.sub).unwrap_or(0);
    let session_id = params.get("sessionId").map(|s| s.as_str());

    let items = if let Some(sid) = session_id {
        sqlx::query_as::<_, kbook_core::entity::AiConversation>(
            "SELECT * FROM ai_conversations WHERE book_id = ? AND session_id = ? ORDER BY id ASC"
        )
        .bind(book_id).bind(sid)
        .fetch_all(&db.pool).await
    } else if user_id > 0 {
        sqlx::query_as::<_, kbook_core::entity::AiConversation>(
            "SELECT * FROM ai_conversations WHERE user_id = ? AND book_id = ? ORDER BY id ASC"
        )
        .bind(user_id).bind(book_id)
        .fetch_all(&db.pool).await
    } else {
        Ok(vec![])
    };

    let items = items.unwrap_or_default();
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": items})))
}

async fn get_sessions(
    State((db, _)): State<BookState>,
    Path(book_id): Path<i64>,
    claims: Option<Extension<kbook_auth::jwt::Claims>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let user_id = claims.map(|c| c.sub).unwrap_or(0);
    let sessions = if user_id > 0 {
        sqlx::query_as::<_, kbook_core::entity::AiSession>(
            "SELECT * FROM ai_sessions WHERE user_id = ? AND book_id = ? AND type = 'BOOK_CHAT' ORDER BY updated_at DESC"
        )
        .bind(user_id).bind(book_id)
        .fetch_all(&db.pool).await.unwrap_or_default()
    } else {
        vec![]
    };
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": sessions})))
}

async fn generate_follow_up(
    State((_db, _)): State<BookState>,
    _claims: Option<Extension<kbook_auth::jwt::Claims>>,
    Path(_book_id): Path<i64>,
    Json(_req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": []})))
}

async fn get_book_file(
    State((_db, _)): State<BookState>,
    Path(id): Path<i64>,
) -> Result<axum::response::Response, StatusCode> {
    let book = kbook_db::repository::book_repo::find_by_id(&(_db.pool), id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .ok_or(StatusCode::NOT_FOUND)?;

    let file_url = book.file_url.ok_or(StatusCode::NOT_FOUND)?;
    let app_dir = dirs::data_dir()
        .unwrap_or_else(|| std::path::PathBuf::from("."))
        .join("kbook");
    let file_path = app_dir.join("data").join("books").join(&file_url);

    if !file_path.exists() {
        return Err(StatusCode::NOT_FOUND);
    }

    let data = tokio::fs::read(&file_path).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let mime = match book.format.as_deref().unwrap_or("") {
        "EPUB" => "application/epub+zip",
        "PDF" => "application/pdf",
        _ => "text/plain; charset=utf-8",
    };

    Ok(axum::response::Response::builder()
        .header("Content-Type", mime)
        .header("Content-Length", data.len())
        .body(axum::body::Body::from(data))
        .unwrap())
}

async fn speed_read_stream(
    State((db, book_chat)): State<BookState>,
    claims: Option<Extension<kbook_auth::jwt::Claims>>,
    Path(id): Path<i64>,
    Json(_body): Json<serde_json::Value>,
) -> Result<axum::response::Sse<impl futures::Stream<Item = Result<axum::response::sse::Event, std::convert::Infallible>>>, StatusCode> {
    // 查询书籍
    let book = kbook_db::repository::book_repo::find_by_id(&db.pool, id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .ok_or(StatusCode::NOT_FOUND)?;

    // 构建书籍内容
    let book_content = build_speed_read_content(&book);

    // 构建用户画像（可选）
    let user_profile = if let Some(Extension(claims)) = claims {
        if let Ok(Some(user)) = kbook_db::repository::user_repo::find_by_id(&db.pool, claims.sub).await {
            build_user_profile_desc(&user)
        } else {
            String::new()
        }
    } else {
        String::new()
    };

    // 构建消息：System(固定指令) → User(书籍信息) → User(用户画像)
    let mut messages = vec![ChatMessage::system(SPEED_READ_SYSTEM_PROMPT)];
    messages.push(ChatMessage::user(&format!("【书籍信息】\n{}", book_content)));
    if !user_profile.is_empty() {
        messages.push(ChatMessage::user(&format!("【读者画像】\n{}", user_profile)));
    }

    let llm = book_chat.llm.clone();
    let model = book_chat.model.clone();

    let (tx, mut rx) = tokio::sync::mpsc::channel::<anyhow::Result<kbook_ai::llm::StreamChunk>>(200);

    // 启动 LLM 流式调用
    let llm_clone = llm.clone();
    let model_clone = model.clone();
    tokio::spawn(async move {
        match llm_clone.stream_chat(&model_clone, &messages, None).await {
            Ok(mut stream_rx) => {
                while let Some(chunk) = stream_rx.recv().await {
                    if tx.send(chunk).await.is_err() { break; }
                }
            }
            Err(e) => { let _ = tx.send(Err(e)).await; }
        }
    });

    // 转换为 SSE 流
    let stream = async_stream::stream! {
        while let Some(result) = rx.recv().await {
            match result {
                Ok(chunk) => {
                    if let Some(choice) = chunk.choices.first() {
                        let text = choice.delta.content.clone().unwrap_or_default();
                        if !text.is_empty() {
                            // 匹配 Spring Boot: event: message, data: 纯文本
                            yield Ok(axum::response::sse::Event::default().event("message").data(text));
                        }
                    }
                    // 流结束（choices 为空表示 [DONE]）
                    if chunk.choices.is_empty() {
                        yield Ok(axum::response::sse::Event::default().event("done").data("[DONE]"));
                        break;
                    }
                }
                Err(_) => {
                    yield Ok(axum::response::sse::Event::default()
                        .event("error")
                        .data("AI 模型未配置，无法生成速读摘要"));
                    break;
                }
            }
        }
    };

    Ok(axum::response::Sse::new(stream).keep_alive(axum::response::sse::KeepAlive::default()))
}

/// 构建速读书籍内容，匹配 Spring Boot ChatModelManager.buildSpeedReadContent
fn build_speed_read_content(book: &kbook_core::entity::Book) -> String {
    let mut content = String::new();
    content.push_str(&format!("书名：《{}》\n", book.title));
    if let Some(author) = &book.author {
        if !author.is_empty() {
            content.push_str(&format!("作者：{}\n", author));
        }
    }
    if let Some(tags) = &book.format_tags {
        if !tags.is_empty() {
            let cleaned = tags.replace('[', "").replace(']', "").replace('"', "").replace(',', "、");
            content.push_str(&format!("标签：{}\n", cleaned));
        }
    }
    if let Some(tags) = &book.concept_tags {
        if !tags.is_empty() {
            let cleaned = tags.replace('[', "").replace(']', "").replace('"', "").replace(',', "、");
            content.push_str(&format!("核心概念：{}\n", cleaned));
        }
    }
    if let Some(tags) = &book.reader_need_tags {
        if !tags.is_empty() {
            let cleaned = tags.replace('[', "").replace(']', "").replace('"', "").replace(',', "、");
            content.push_str(&format!("读者关注：{}\n", cleaned));
        }
    }
    if let Some(desc) = &book.description {
        if !desc.is_empty() {
            let truncated: String = desc.chars().take(2000).collect();
            content.push_str(&format!("简介：{}\n", truncated));
        }
    }
    if let Some(summary) = &book.chapter_summary {
        if !summary.is_empty() {
            let truncated: String = summary.chars().take(6000).collect();
            content.push_str(&format!("章节摘要：\n{}\n", truncated));
        }
    } else if let Some(toc) = &book.toc {
        if !toc.is_empty() {
            let truncated: String = toc.chars().take(1500).collect();
            content.push_str(&format!("目录：\n{}\n", truncated));
        }
    }
    content
}

/// 构建用户画像描述，匹配 Spring Boot ChatModelManager.buildUserProfileDesc
fn build_user_profile_desc(user: &kbook_core::entity::User) -> String {
    let mut profile = String::new();
    if let Some(birthday) = &user.birthday {
        if let Ok(date) = chrono::NaiveDate::parse_from_str(birthday, "%Y-%m-%d") {
            let age = (chrono::Utc::now().date_naive() - date).num_days() / 365;
            profile.push_str(&format!("年龄：{}岁\n", age));
        }
    }
    if let Some(gender) = &user.gender {
        let label = match gender.as_str() {
            "MALE" => "男",
            "FEMALE" => "女",
            _ => "其他",
        };
        profile.push_str(&format!("性别：{}\n", label));
    }
    if let Some(married) = user.is_married {
        profile.push_str(&format!("婚姻：{}\n", if married { "已婚" } else { "未婚" }));
    }
    if let Some(ranges) = &user.children_age_ranges {
        if !ranges.is_empty() {
            let labels: Vec<&str> = ranges.split(',').map(|s| match s.trim() {
                "children_0_2" => "0-2岁",
                "children_3_6" => "3-6岁",
                "children_7_12" => "7-12岁",
                "children_13_17" => "13-17岁",
                "children_18_plus" => "18岁以上",
                other => other,
            }).collect();
            profile.push_str(&format!("子女年龄段：{}\n", labels.join("、")));
        }
    } else if let Some(has) = user.has_children {
        profile.push_str(&format!("子女：{}\n", if has { "有孩子" } else { "无孩子" }));
    }
    if let Some(mbti) = &user.mbti {
        if !mbti.is_empty() {
            profile.push_str(&format!("MBTI：{}\n", mbti));
        }
    }
    if let Some(occ) = &user.occupation {
        if !occ.is_empty() {
            let label = match occ.as_str() {
                "student" => "学生", "tech" => "科技/互联网", "finance" => "金融",
                "education" => "教育", "medical" => "医疗", "arts" => "艺术/文化",
                "management" => "管理", "freelance" => "自由职业", "retired" => "退休",
                _ => occ,
            };
            profile.push_str(&format!("职业：{}\n", label));
        }
    }
    if let Some(edu) = &user.education {
        if !edu.is_empty() {
            let label = match edu.as_str() {
                "high_school" => "高中", "college" => "大专", "bachelor" => "本科",
                "master" => "硕士", "doctorate" => "博士",
                _ => edu,
            };
            profile.push_str(&format!("期望学历：{}\n", label));
        }
    }
    if let Some(ent) = &user.entrepreneurship {
        if !ent.is_empty() {
            let label = match ent.as_str() {
                "yes" => "有创业意向", "no" => "无创业意向", "considering" => "考虑中",
                _ => ent,
            };
            profile.push_str(&format!("创业意向：{}\n", label));
        }
    }
    if let Some(income) = &user.annual_income {
        if !income.is_empty() {
            let label = match income.as_str() {
                "under_50k" => "5万以下", "50k_150k" => "5-15万", "150k_300k" => "15-30万",
                "300k_500k" => "30-50万", "500k_1m" => "50-100万", "over_1m" => "100万以上",
                _ => income,
            };
            profile.push_str(&format!("期望年收入：{}\n", label));
        }
    }
    if let Some(mood) = &user.mood {
        if !mood.is_empty() {
            if let Some(pipe_idx) = mood.find('|') {
                let intent_key = &mood[..pipe_idx];
                let mood_key = &mood[pipe_idx + 1..];
                let intent_label = match intent_key {
                    "growth" => "成长提升", "excite" => "寻求刺激", "escape" => "逃避放松",
                    "comfort" => "寻求安慰", "insight" => "深度思考",
                    _ => intent_key,
                };
                let mood_label = match mood_key {
                    "happy" => "开心", "anxious" => "焦虑", "sad" => "低落",
                    "tired" => "疲惫", "frustrated" => "受挫", "calm" => "平静",
                    _ => mood_key,
                };
                profile.push_str(&format!("阅读意图：{}\n", intent_label));
                profile.push_str(&format!("当前心情：{}\n", mood_label));
            }
        }
    }
    profile
}
