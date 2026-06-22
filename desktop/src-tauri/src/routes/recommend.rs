use axum::{
    extract::{State, Path, Query, Extension},
    http::StatusCode,
    routing::{get, delete},
    Json, Router,
};
use std::sync::Arc;
use kbook_db::Database;
use kbook_auth::jwt::Claims;
use kbook_ai::recommend::RecommendService;
use super::{fix_books_cover};

pub fn recommend_routes(db: Arc<Database>, recommend: Arc<RecommendService>) -> Router {
    Router::new()
        .route("/", get(get_recommendations))
        .route("/page", get(get_recommendations_page))
        .route("/cache", delete(clear_recommend_cache))
        .route("/generate", get(generate_recommendations))
        .route("/match-scores", get(get_match_scores))
        .route("/match-detail/{bookId}", get(get_match_detail))
        .with_state((db, recommend))
}

async fn get_recommendations(
    State((db, recommend)): State<(Arc<Database>, Arc<RecommendService>)>,
    claims: Option<Extension<Claims>>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let count = params.get("count").and_then(|s| s.parse::<usize>().ok()).unwrap_or(10);
    let user_id = claims.map(|c| c.sub).unwrap_or(0);
    let books = recommend.get_recommendations(&db.pool, user_id, count)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": fix_books_cover(books)})))
}

async fn get_recommendations_page(
    State((db, recommend)): State<(Arc<Database>, Arc<RecommendService>)>,
    claims: Option<Extension<Claims>>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let page = params.get("page").and_then(|s| s.parse::<i32>().ok()).unwrap_or(1);
    let size = params.get("size").and_then(|s| s.parse::<i32>().ok()).unwrap_or(10);
    let user_id = claims.map(|c| c.sub).unwrap_or(0);
    let books = recommend.get_recommendations(&db.pool, user_id, size as usize)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let total = books.len() as i64;
    let data = fix_books_cover(books);
    Ok(Json(serde_json::json!({
        "code": 0, "message": "success",
        "data": { "list": data, "total": total, "page": page, "size": size }
    })))
}

async fn generate_recommendations(
    State((db, recommend)): State<(Arc<Database>, Arc<RecommendService>)>,
    claims: Option<Extension<Claims>>,
) -> Result<axum::response::Sse<impl futures::Stream<Item = Result<axum::response::sse::Event, std::convert::Infallible>>>, StatusCode> {
    let user_id = claims.map(|c| c.sub).unwrap_or(0);
    let books = recommend.generate_recommendations(&db.pool, user_id)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    let stream = async_stream::stream! {
        let progress = serde_json::json!({"stage": "done", "message": "完成", "progress": 100});
        yield Ok(axum::response::sse::Event::default().event("progress").data(progress.to_string()));
        let fixed = fix_books_cover(books);
        yield Ok(axum::response::sse::Event::default().event("done").data(serde_json::to_string(&fixed).unwrap_or_default()));
    };
    Ok(axum::response::Sse::new(stream).keep_alive(axum::response::sse::KeepAlive::default()))
}

async fn get_match_scores(
    State((db, _)): State<(Arc<Database>, Arc<RecommendService>)>,
    Extension(_claims): Extension<Claims>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let book_ids: Vec<i64> = params.get("bookIds")
        .map(|s| s.split(',').filter_map(|v| v.parse().ok()).collect())
        .unwrap_or_default();

    let mut scores = serde_json::Map::new();

    for book_id in &book_ids {
        let book = sqlx::query_as::<_, kbook_core::entity::Book>("SELECT * FROM books WHERE id = ?")
            .bind(book_id).fetch_optional(&db.pool).await.ok().flatten();
        if let Some(b) = book {
            let mut score = 0.0f64;
            let read_count = b.read_count.unwrap_or(0) as f64;
            let rating = b.rating.unwrap_or(0.0);
            score += (read_count / 100.0).min(1.0) * 30.0;
            score += (rating / 5.0) * 40.0;
            score += 30.0;
            scores.insert(book_id.to_string(), serde_json::json!((score * 10.0).round() / 10.0));
        }
    }

    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": scores})))
}

async fn get_match_detail(
    State((db, _)): State<(Arc<Database>, Arc<RecommendService>)>,
    claims: Option<Extension<Claims>>,
    Path(book_id): Path<i64>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let user_id = claims.map(|c| c.sub).unwrap_or(0);
    let book = sqlx::query_as::<_, kbook_core::entity::Book>("SELECT * FROM books WHERE id = ?")
        .bind(book_id).fetch_one(&db.pool).await.map_err(|_| StatusCode::NOT_FOUND)?;

    let user = if user_id > 0 {
        sqlx::query_as::<_, kbook_core::entity::User>("SELECT * FROM users WHERE id = ?")
            .bind(user_id).fetch_optional(&db.pool).await.ok().flatten()
    } else { None };

    let rating = book.rating.unwrap_or(0.0);
    let read_count = book.read_count.unwrap_or(0);

    let mut dimensions: Vec<serde_json::Value> = Vec::new();

    if let Some(ref u) = user {
        let score = (rating / 5.0).min(1.0);
        dimensions.push(serde_json::json!({
            "dimension": "rating", "label": "评分",
            "score": (score * 100.0).round(), "weight": 0.5,
            "weightedScore": (score * 0.5 * 100.0).round()
        }));

        if let Some(ref gender) = u.gender {
            let s = if gender.eq_ignore_ascii_case("MALE") { 85.0 } else { 75.0 };
            dimensions.push(serde_json::json!({
                "dimension": "gender", "label": "性别",
                "score": s, "weight": 0.8,
                "weightedScore": (s * 0.8_f64).round()
            }));
        }
        if let Some(ref _mbti) = u.mbti {
            dimensions.push(serde_json::json!({
                "dimension": "mbti", "label": "MBTI",
                "score": 70.0, "weight": 1.3,
                "weightedScore": (70.0_f64 * 1.3).round()
            }));
        }
    }

    let cf = if dimensions.len() >= 11 { 1.0 }
        else if dimensions.len() >= 8 { 0.95 }
        else if dimensions.len() >= 5 { 0.87 }
        else { 0.60 };

    let overall = ((rating / 5.0 * 40.0 + (read_count as f64 / 100.0).min(1.0) * 30.0 + 30.0) * cf / 100.0).round() / 100.0;

    Ok(Json(serde_json::json!({
        "code": 0, "message": "success",
        "data": {
            "bookId": book_id,
            "overallScore": overall,
            "matchedDimensions": dimensions.len(),
            "coverageFactor": cf,
            "dimensions": dimensions
        }
    })))
}

async fn clear_recommend_cache() -> Result<Json<serde_json::Value>, StatusCode> {
    Ok(Json(serde_json::json!({"code": 0, "message": "success"})))
}
