use crate::llm::{Tool, ToolFunction};
use crate::recommend::RecommendService;
use kbook_core::entity::{Book, UserBookPreference};
use serde_json::{json, Value};
use sqlx::SqlitePool;
use std::sync::Arc;

// ── Tool Context ───────────────────────────────────────────────────────

#[derive(Clone, Default)]
pub struct ToolContext {
    pub embedding_client: Option<Arc<kbook_vector::embedding::EmbeddingClient>>,
    pub recommend_service: Option<Arc<RecommendService>>,
}

// ── Tool Definitions ───────────────────────────────────────────────────

pub fn get_tools() -> Vec<Tool> {
    vec![
        Tool {
            tool_type: "function".into(),
            function: ToolFunction {
                name: "search_books".into(),
                description: "搜索图书，支持关键词、格式、标签筛选。返回图书列表。".into(),
                parameters: json!({
                    "type": "object",
                    "properties": {
                        "keyword": { "type": "string", "description": "搜索关键词（书名/作者）" },
                        "format": { "type": "string", "description": "格式筛选：TXT/EPUB/PDF" },
                        "tag": { "type": "string", "description": "标签筛选" },
                        "page": { "type": "integer", "description": "页码，默认1" },
                        "size": { "type": "integer", "description": "每页数量，默认10" }
                    }
                }),
            },
        },
        Tool {
            tool_type: "function".into(),
            function: ToolFunction {
                name: "get_book_detail".into(),
                description: "获取指定图书的详细信息，包括标题、作者、简介、标签、评分等。".into(),
                parameters: json!({
                    "type": "object",
                    "properties": {
                        "book_id": { "type": "integer", "description": "图书ID" }
                    },
                    "required": ["book_id"]
                }),
            },
        },
        Tool {
            tool_type: "function".into(),
            function: ToolFunction {
                name: "get_read_rank".into(),
                description: "获取阅读排行榜，按阅读量排序。".into(),
                parameters: json!({
                    "type": "object",
                    "properties": {
                        "page": { "type": "integer", "description": "页码，默认1" },
                        "size": { "type": "integer", "description": "每页数量，默认10" }
                    }
                }),
            },
        },
        Tool {
            tool_type: "function".into(),
            function: ToolFunction {
                name: "get_rating_rank".into(),
                description: "获取评分排行榜，按评分排序。".into(),
                parameters: json!({
                    "type": "object",
                    "properties": {
                        "page": { "type": "integer", "description": "页码，默认1" },
                        "size": { "type": "integer", "description": "每页数量，默认10" }
                    }
                }),
            },
        },
        Tool {
            tool_type: "function".into(),
            function: ToolFunction {
                name: "get_user_profile".into(),
                description: "获取当前用户的个人资料，包括兴趣偏好、阅读历史等。".into(),
                parameters: json!({
                    "type": "object",
                    "properties": {},
                    "required": []
                }),
            },
        },
        Tool {
            tool_type: "function".into(),
            function: ToolFunction {
                name: "get_bookshelf".into(),
                description: "获取用户书架上的图书列表，包含阅读进度。".into(),
                parameters: json!({
                    "type": "object",
                    "properties": {}
                }),
            },
        },
        Tool {
            tool_type: "function".into(),
            function: ToolFunction {
                name: "search_book_content".into(),
                description: "在指定书籍的内容中搜索相关片段。当用户询问某本书的具体内容、人物关系、情节细节时使用此工具。返回与查询最相关的原文片段。".into(),
                parameters: json!({
                    "type": "object",
                    "properties": {
                        "book_id": { "type": "integer", "description": "图书ID" },
                        "query": { "type": "string", "description": "搜索关键词或问题，如'主角的成长经历'" }
                    },
                    "required": ["book_id", "query"]
                }),
            },
        },
        Tool {
            tool_type: "function".into(),
            function: ToolFunction {
                name: "recommend_related_books".into(),
                description: "根据指定书籍推荐相关书籍。通过分析该书的标签、评分维度、作者等，找出相似的书籍。".into(),
                parameters: json!({
                    "type": "object",
                    "properties": {
                        "book_id": { "type": "integer", "description": "源图书ID" },
                        "count": { "type": "integer", "description": "推荐数量，默认5" }
                    },
                    "required": ["book_id"]
                }),
            },
        },
        Tool {
            tool_type: "function".into(),
            function: ToolFunction {
                name: "add_exclude_preference".into(),
                description: "记录用户不想看的书籍类型偏好。之后的推荐中将不再推荐该类书籍。支持标签(TAG)、作者(AUTHOR)、格式(FORMAT)三种类别。".into(),
                parameters: json!({
                    "type": "object",
                    "properties": {
                        "category": { "type": "string", "description": "偏好类别：TAG(标签)、AUTHOR(作者)、FORMAT(格式)" },
                        "value": { "type": "string", "description": "偏好值，如'科幻'、'金庸'、'TXT'" }
                    },
                    "required": ["category", "value"]
                }),
            },
        },
        Tool {
            tool_type: "function".into(),
            function: ToolFunction {
                name: "get_preferences".into(),
                description: "查询用户的所有书籍偏好（包括不想看的标签、作者、格式）。".into(),
                parameters: json!({
                    "type": "object",
                    "properties": {}
                }),
            },
        },
        Tool {
            tool_type: "function".into(),
            function: ToolFunction {
                name: "add_include_preference".into(),
                description: "记录用户喜欢/想看的书籍类型偏好。之后的推荐中会优先推荐该类书籍。支持标签(TAG)、作者(AUTHOR)、格式(FORMAT)三种类别。".into(),
                parameters: json!({
                    "type": "object",
                    "properties": {
                        "category": { "type": "string", "description": "偏好类别：TAG(标签)、AUTHOR(作者)、FORMAT(格式)" },
                        "value": { "type": "string", "description": "偏好值，如'科幻'、'金庸'、'EPUB'" }
                    },
                    "required": ["category", "value"]
                }),
            },
        },
        Tool {
            tool_type: "function".into(),
            function: ToolFunction {
                name: "personalize_recommend".into(),
                description: "根据用户画像（年龄、性别、MBTI、阅读偏好等）进行个性化推荐。当用户说'推荐适合我的书'、'猜我喜欢'时使用。".into(),
                parameters: json!({
                    "type": "object",
                    "properties": {
                        "count": { "type": "integer", "description": "推荐数量，默认5" }
                    }
                }),
            },
        },
    ]
}

// ── Tool Execution ─────────────────────────────────────────────────────

pub async fn execute_tool(
    pool: &SqlitePool,
    tool_name: &str,
    arguments: &str,
    user_id: i64,
    ctx: &ToolContext,
) -> anyhow::Result<String> {
    let args: Value = serde_json::from_str(arguments).unwrap_or(json!({}));

    match tool_name {
        "search_books" => {
            let keyword = args.get("keyword").and_then(|v| v.as_str());
            let format = args.get("format").and_then(|v| v.as_str());
            let tag = args.get("tag").and_then(|v| v.as_str());
            let page = args.get("page").and_then(|v| v.as_i64()).unwrap_or(1) as i32;
            let size = args.get("size").and_then(|v| v.as_i64()).unwrap_or(10) as i32;

            let (books, total) = if let Some(kw) = keyword {
                if !kw.trim().is_empty() {
                    if let Some(ref ec) = ctx.embedding_client {
                        match kbook_db::hybrid_search::hybrid_search(pool, ec, kw.trim(), format, tag, page, size).await {
                            Ok(result) => result,
                            Err(e) => {
                                tracing::warn!("Hybrid search failed, falling back: {}", e);
                                kbook_db::repository::book_repo::search(pool, Some(kw.trim()), format, tag, page, size).await?
                            }
                        }
                    } else {
                        kbook_db::repository::book_repo::search(pool, Some(kw.trim()), format, tag, page, size).await?
                    }
                } else {
                    kbook_db::repository::book_repo::search(pool, None, format, tag, page, size).await?
                }
            } else {
                kbook_db::repository::book_repo::search(pool, None, format, tag, page, size).await?
            };

            let results: Vec<Value> = books.iter().map(|b| json!({
                "id": b.id,
                "title": b.title,
                "author": b.author,
                "format": b.format,
                "rating": b.rating,
                "read_count": b.read_count,
                "description": b.description.as_ref().map(|d| truncate(d, 200)),
            })).collect();

            Ok(json!({"total": total, "books": results}).to_string())
        }

        "get_book_detail" => {
            let book_id = args.get("book_id").and_then(|v| v.as_i64())
                .ok_or_else(|| anyhow::anyhow!("book_id required"))?;
            let book = kbook_db::repository::book_repo::find_by_id(pool, book_id).await?
                .ok_or_else(|| anyhow::anyhow!("Book not found"))?;

            Ok(json!({
                "id": book.id,
                "title": book.title,
                "author": book.author,
                "description": book.description,
                "format": book.format,
                "rating": book.rating,
                "read_count": book.read_count,
                "format_tags": book.format_tags,
                "concept_tags": book.concept_tags,
                "reader_need_tags": book.reader_need_tags,
                "target_reader_tags": book.target_reader_tags,
                "chapter_summary": book.chapter_summary.as_ref().map(|s| truncate(s, 500)),
            }).to_string())
        }

        "get_read_rank" => {
            let page = args.get("page").and_then(|v| v.as_i64()).unwrap_or(1) as i32;
            let size = args.get("size").and_then(|v| v.as_i64()).unwrap_or(10) as i32;
            let (books, _) = kbook_db::repository::book_repo::get_read_rank(pool, page, size).await?;
            let results: Vec<Value> = books.iter().map(|b| json!({
                "id": b.id, "title": b.title, "author": b.author, "read_count": b.read_count, "rating": b.rating,
            })).collect();
            Ok(json!({"books": results}).to_string())
        }

        "get_rating_rank" => {
            let page = args.get("page").and_then(|v| v.as_i64()).unwrap_or(1) as i32;
            let size = args.get("size").and_then(|v| v.as_i64()).unwrap_or(10) as i32;
            let (books, _) = kbook_db::repository::book_repo::get_rating_rank(pool, page, size).await?;
            let results: Vec<Value> = books.iter().map(|b| json!({
                "id": b.id, "title": b.title, "author": b.author, "rating": b.rating, "rating_count": b.rating_count,
            })).collect();
            Ok(json!({"books": results}).to_string())
        }

        "get_user_profile" => {
            let user = kbook_db::repository::user_repo::find_by_id(pool, user_id).await?
                .ok_or_else(|| anyhow::anyhow!("User not found"))?;
            Ok(json!({
                "nickname": user.nickname,
                "mbti": user.mbti,
                "occupation": user.occupation,
                "mood": user.mood,
                "book_chat_style": user.book_chat_style,
            }).to_string())
        }

        "get_bookshelf" => {
            let rows: Vec<(i64, i64)> = sqlx::query_as(
                "SELECT book_id, sort_order FROM bookshelf WHERE user_id = ? ORDER BY sort_order, added_at DESC"
            )
            .bind(user_id)
            .fetch_all(pool)
            .await?;

            if rows.is_empty() {
                return Ok(json!({"books": [], "message": "书架为空"}).to_string());
            }

            let book_ids: Vec<i64> = rows.iter().map(|(id, _)| *id).collect();
            let books = kbook_db::repository::book_repo::find_by_ids(pool, &book_ids).await?;

            // Fetch reading progress
            let progress_rows: Vec<(i64, Option<f64>)> = sqlx::query_as(
                "SELECT book_id, progress FROM reading_progress WHERE user_id = ?"
            )
            .bind(user_id)
            .fetch_all(pool)
            .await?;

            let progress_map: std::collections::HashMap<i64, f64> = progress_rows.into_iter()
                .filter_map(|(id, p)| p.map(|v| (id, v)))
                .collect();

            let results: Vec<Value> = books.iter().map(|b| {
                let progress = progress_map.get(&b.id).copied().unwrap_or(0.0);
                json!({
                    "id": b.id,
                    "title": b.title,
                    "author": b.author,
                    "format": b.format,
                    "rating": b.rating,
                    "progress": progress,
                })
            }).collect();

            Ok(json!({"books": results}).to_string())
        }

        "search_book_content" => {
            let book_id = args.get("book_id").and_then(|v| v.as_i64())
                .ok_or_else(|| anyhow::anyhow!("book_id required"))?;
            let query = args.get("query").and_then(|v| v.as_str())
                .ok_or_else(|| anyhow::anyhow!("query required"))?;

            let ec = match ctx.embedding_client {
                Some(ref ec) => ec,
                None => return Ok(json!({"error": "向量检索功能暂不可用"}).to_string()),
            };

            match kbook_vector::store::search_book_content(pool, ec, book_id, query, 5).await {
                Ok(chunks) => {
                    if chunks.is_empty() {
                        return Ok(json!({"message": "未在该书中找到相关内容"}).to_string());
                    }
                    let results: Vec<Value> = chunks.iter().enumerate().map(|(i, (text, score))| json!({
                        "index": i + 1,
                        "text": text,
                        "score": format!("{:.2}", score),
                    })).collect();
                    Ok(json!({"chunks": results}).to_string())
                }
                Err(e) => {
                    tracing::warn!("search_book_content error: {}", e);
                    Ok(json!({"error": "搜索书籍内容时发生错误"}).to_string())
                }
            }
        }

        "recommend_related_books" => {
            let book_id = args.get("book_id").and_then(|v| v.as_i64())
                .ok_or_else(|| anyhow::anyhow!("book_id required"))?;
            let count = args.get("count").and_then(|v| v.as_i64()).unwrap_or(5).min(20).max(1) as usize;

            let source_book = kbook_db::repository::book_repo::find_by_id(pool, book_id).await?
                .ok_or_else(|| anyhow::anyhow!("Book not found"))?;

            let mut recommendations: Vec<Value> = Vec::new();
            let mut seen_ids: std::collections::HashSet<i64> = std::collections::HashSet::new();
            seen_ids.insert(book_id);

            // 1. Vector similarity
            if let Some(ref ec) = ctx.embedding_client {
                let query_text = build_book_search_query(&source_book);
                match ec.embed_single(&query_text).await {
                    Ok(query_vector) => {
                        match kbook_vector::store::search_similar(pool, &query_vector, count * 2, 0.4).await {
                            Ok(results) => {
                                for (related_id, score) in results {
                                    if seen_ids.contains(&related_id) { continue; }
                                    if let Ok(Some(related)) = kbook_db::repository::book_repo::find_by_id(pool, related_id).await {
                                        recommendations.push(json!({
                                            "book_id": related.id,
                                            "title": related.title,
                                            "author": related.author,
                                            "format": related.format,
                                            "rating": related.rating,
                                            "vector_score": format!("{:.2}", score),
                                            "reason": "语义相似",
                                        }));
                                        seen_ids.insert(related_id);
                                    }
                                }
                            }
                            Err(e) => tracing::warn!("Vector similarity error: {}", e),
                        }
                    }
                    Err(e) => tracing::warn!("Embedding error: {}", e),
                }
            }

            // 2. Score similarity (8-dimension relevance_scores)
            if let Some(ref scores_json) = source_book.relevance_scores {
                if !scores_json.is_empty() {
                    if let Ok(source_scores) = serde_json::from_str::<serde_json::Map<String, Value>>(scores_json) {
                        let candidates: Vec<Book> = sqlx::query_as::<_, Book>(
                            "SELECT * FROM books WHERE id != ? AND relevance_scores IS NOT NULL AND relevance_scores != '' LIMIT 200"
                        )
                        .bind(book_id)
                        .fetch_all(pool)
                        .await
                        .unwrap_or_default();

                        let mut scored_candidates: Vec<(Book, f64)> = Vec::new();
                        for candidate in &candidates {
                            if seen_ids.contains(&candidate.id) { continue; }
                            if let Some(ref cs) = candidate.relevance_scores {
                                if let Ok(candidate_scores) = serde_json::from_str::<serde_json::Map<String, Value>>(cs) {
                                    let similarity = calculate_score_similarity(&source_scores, &candidate_scores);
                                    if similarity > 0.5 {
                                        scored_candidates.push((candidate.clone(), similarity));
                                    }
                                }
                            }
                        }
                        scored_candidates.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));

                        for (book, sim) in scored_candidates.into_iter().take(count) {
                            if seen_ids.contains(&book.id) { continue; }
                            recommendations.push(json!({
                                "book_id": book.id,
                                "title": book.title,
                                "author": book.author,
                                "format": book.format,
                                "rating": book.rating,
                                "score_similarity": format!("{:.2}", sim),
                                "reason": "评分维度相似",
                            }));
                            seen_ids.insert(book.id);
                        }
                    }
                }
            }

            // 3. Same author
            if let Some(ref author) = source_book.author {
                if !author.is_empty() {
                    let same_author: Vec<Book> = sqlx::query_as::<_, Book>(
                        "SELECT * FROM books WHERE author = ? AND id != ? LIMIT 5"
                    )
                    .bind(author)
                    .bind(book_id)
                    .fetch_all(pool)
                    .await
                    .unwrap_or_default();

                    for book in same_author {
                        if seen_ids.contains(&book.id) { continue; }
                        recommendations.push(json!({
                            "book_id": book.id,
                            "title": book.title,
                            "author": book.author,
                            "format": book.format,
                            "rating": book.rating,
                            "reason": "同作者",
                        }));
                        seen_ids.insert(book.id);
                    }
                }
            }

            // Truncate to requested count
            recommendations.truncate(count);

            if recommendations.is_empty() {
                return Ok(json!({"message": format!("未找到与《{}》相关的书籍", source_book.title)}).to_string());
            }

            Ok(json!({
                "source_book": source_book.title,
                "recommendations": recommendations,
            }).to_string())
        }

        "add_exclude_preference" => {
            let category = args.get("category").and_then(|v| v.as_str())
                .ok_or_else(|| anyhow::anyhow!("category required"))?;
            let value = args.get("value").and_then(|v| v.as_str())
                .ok_or_else(|| anyhow::anyhow!("value required"))?;

            let cat = category.to_uppercase();
            if cat != "TAG" && cat != "AUTHOR" && cat != "FORMAT" {
                return Ok(json!({"error": "无效的类别，请使用 TAG、AUTHOR 或 FORMAT"}).to_string());
            }

            let _ = sqlx::query(
                "INSERT OR IGNORE INTO user_book_preference (user_id, book_id, type, category, value, created_at, updated_at)
                 VALUES (?, 0, 'EXCLUDE', ?, ?, datetime('now'), datetime('now'))"
            )
            .bind(user_id)
            .bind(&cat)
            .bind(value)
            .execute(pool)
            .await;

            let cat_name = match cat.as_str() {
                "TAG" => "标签",
                "AUTHOR" => "作者",
                "FORMAT" => "格式",
                _ => category,
            };
            Ok(json!({"message": format!("已记录：不想看 {} 类型的 \"{}\"，后续推荐将排除该类型", cat_name, value)}).to_string())
        }

        "get_preferences" => {
            let rows: Vec<UserBookPreference> = sqlx::query_as(
                "SELECT * FROM user_book_preference WHERE user_id = ? AND type IN ('INCLUDE', 'EXCLUDE')"
            )
            .bind(user_id)
            .fetch_all(pool)
            .await?;

            if rows.is_empty() {
                return Ok(json!({"preferences": [], "message": "暂无书籍偏好记录"}).to_string());
            }

            let prefs: Vec<Value> = rows.iter().map(|p| {
                let cat_name = match p.category.as_deref() {
                    Some("TAG") => "标签",
                    Some("AUTHOR") => "作者",
                    Some("FORMAT") => "格式",
                    _ => p.category.as_deref().unwrap_or("未知"),
                };
                let type_name = match p.pref_type.as_deref() {
                    Some("EXCLUDE") => "不想看",
                    Some("INCLUDE") => "想看",
                    _ => p.pref_type.as_deref().unwrap_or("未知"),
                };
                json!({
                    "category": cat_name,
                    "value": p.value,
                    "type": type_name,
                })
            }).collect();

            Ok(json!({"preferences": prefs}).to_string())
        }

        "add_include_preference" => {
            let category = args.get("category").and_then(|v| v.as_str())
                .ok_or_else(|| anyhow::anyhow!("category required"))?;
            let value = args.get("value").and_then(|v| v.as_str())
                .ok_or_else(|| anyhow::anyhow!("value required"))?;

            let cat = category.to_uppercase();
            if cat != "TAG" && cat != "AUTHOR" && cat != "FORMAT" {
                return Ok(json!({"error": "无效的类别，请使用 TAG、AUTHOR 或 FORMAT"}).to_string());
            }

            let _ = sqlx::query(
                "INSERT OR IGNORE INTO user_book_preference (user_id, book_id, type, category, value, created_at, updated_at)
                 VALUES (?, 0, 'INCLUDE', ?, ?, datetime('now'), datetime('now'))"
            )
            .bind(user_id)
            .bind(&cat)
            .bind(value)
            .execute(pool)
            .await;

            let cat_name = match cat.as_str() {
                "TAG" => "标签",
                "AUTHOR" => "作者",
                "FORMAT" => "格式",
                _ => category,
            };
            Ok(json!({"message": format!("已记录：喜欢看 {} 类型的 \"{}\"，后续推荐会优先推荐", cat_name, value)}).to_string())
        }

        "personalize_recommend" => {
            let count = args.get("count").and_then(|v| v.as_i64()).unwrap_or(5).min(20).max(1) as usize;

            let rs = match ctx.recommend_service {
                Some(ref rs) => rs,
                None => return Ok(json!({"error": "推荐服务暂不可用"}).to_string()),
            };

            match rs.get_recommendations(pool, user_id, count).await {
                Ok(books) => {
                    if books.is_empty() {
                        return Ok(json!({"message": "暂无个性化推荐数据，可以尝试搜索或查看排行榜"}).to_string());
                    }
                    let results: Vec<Value> = books.iter().map(|b| json!({
                        "id": b.id,
                        "title": b.title,
                        "author": b.author,
                        "rating": b.rating,
                        "format": b.format,
                    })).collect();
                    Ok(json!({"recommendations": results}).to_string())
                }
                Err(e) => {
                    tracing::warn!("personalize_recommend error: {}", e);
                    Ok(json!({"error": "获取个性化推荐时发生错误"}).to_string())
                }
            }
        }

        _ => Ok(json!({"error": format!("Unknown tool: {}", tool_name)}).to_string()),
    }
}

// ── Helper Functions ───────────────────────────────────────────────────

fn truncate(text: &str, max_len: usize) -> String {
    if text.len() <= max_len {
        text.to_string()
    } else {
        format!("{}...", &text[..max_len])
    }
}

fn build_book_search_query(book: &Book) -> String {
    let mut parts = Vec::new();
    parts.push(book.title.clone());
    if let Some(ref author) = book.author {
        if !author.is_empty() {
            parts.push(author.clone());
        }
    }
    if let Some(ref tags) = book.format_tags {
        if !tags.is_empty() {
            parts.push(tags.replace('[', "").replace(']', "").replace('"', "").replace(',', " "));
        }
    }
    parts.join(" ")
}

fn calculate_score_similarity(
    scores_a: &serde_json::Map<String, Value>,
    scores_b: &serde_json::Map<String, Value>,
) -> f64 {
    let mut vec_a = Vec::new();
    let mut vec_b = Vec::new();

    for (key, val_a) in scores_a {
        if let Some(val_b) = scores_b.get(key) {
            if let (Some(a), Some(b)) = (val_a.as_f64(), val_b.as_f64()) {
                vec_a.push(a);
                vec_b.push(b);
            }
        }
    }

    if vec_a.is_empty() {
        return 0.0;
    }

    let dot: f64 = vec_a.iter().zip(vec_b.iter()).map(|(a, b)| a * b).sum();
    let norm_a: f64 = vec_a.iter().map(|x| x * x).sum::<f64>().sqrt();
    let norm_b: f64 = vec_b.iter().map(|x| x * x).sum::<f64>().sqrt();

    if norm_a > 0.0 && norm_b > 0.0 {
        dot / (norm_a * norm_b)
    } else {
        0.0
    }
}
