use axum::{
    routing::{get, post},
    http::StatusCode,
    Json, Router,
};
use std::sync::Arc;
use kbook_db::Database;
use dashmap::DashMap;
use std::sync::OnceLock;

static CAPTCHA_STORE: OnceLock<DashMap<String, CaptchaData>> = OnceLock::new();

struct CaptchaData {
    targets: Vec<usize>,
    verified: bool,
}

fn get_store() -> &'static DashMap<String, CaptchaData> {
    CAPTCHA_STORE.get_or_init(|| DashMap::new())
}

const COLOR_HEX: &[(&str, &str)] = &[
    ("red", "#EF4444"),
    ("blue", "#3B82F6"),
    ("green", "#22C55E"),
    ("yellow", "#EAB308"),
    ("orange", "#F97316"),
    ("purple", "#A855F7"),
    ("pink", "#EC4899"),
    ("cyan", "#06B6D4"),
];

pub fn captcha_routes(db: Arc<Database>) -> Router {
    Router::new()
        .route("/generate", post(generate_captcha))
        .route("/verify", post(verify_captcha))
        .route("/click/generate", get(click_generate_captcha))
        .route("/click/verify", post(click_verify_captcha))
        .with_state(db)
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct CaptchaItem {
    index: usize,
    shape: String,
    color: String,
    size: String,
    color_hex: String,
    is_target: bool,
}

async fn generate_captcha() -> Result<Json<serde_json::Value>, StatusCode> {
    do_click_captcha().await
}

async fn click_generate_captcha() -> Result<Json<serde_json::Value>, StatusCode> {
    do_click_captcha().await
}

async fn do_click_captcha() -> Result<Json<serde_json::Value>, StatusCode> {
    let shapes = vec!["circle", "triangle", "square", "diamond", "star", "heart"];
    let colors = vec!["red", "blue", "green", "yellow"];
    let sizes = vec!["small", "medium", "large"];

    let mut items = Vec::new();
    let mut target_indices = Vec::new();
    let target_count = 2;
    while target_indices.len() < target_count {
        let idx = fastrand::usize(0..9);
        if !target_indices.contains(&idx) {
            target_indices.push(idx);
        }
    }

    for i in 0..9 {
        let shape = shapes[fastrand::usize(0..shapes.len())];
        let color = colors[fastrand::usize(0..colors.len())];
        let size = sizes[fastrand::usize(0..sizes.len())];
        let color_hex = COLOR_HEX.iter().find(|(c, _)| *c == color).map(|(_, h)| *h).unwrap_or("#999999");
        let is_target = target_indices.contains(&i);

        items.push(CaptchaItem {
            index: i,
            shape: shape.to_string(),
            color: color.to_string(),
            size: size.to_string(),
            color_hex: color_hex.to_string(),
            is_target,
        });
    }

    let captcha_id = uuid::Uuid::new_v4().to_string();
    let hint = target_indices.iter()
        .map(|&i| format!("{}色{}", 
            match items[i].color.as_str() {
                "red" => "红", "blue" => "蓝", "green" => "绿", "yellow" => "黄",
                _ => &items[i].color,
            },
            match items[i].shape.as_str() {
                "circle" => "圆形", "triangle" => "三角形", "square" => "正方形",
                "diamond" => "菱形", "star" => "星形", "heart" => "心形",
                _ => &items[i].shape,
            }
        ))
        .collect::<Vec<_>>()
        .join("、");

    get_store().insert(captcha_id.clone(), CaptchaData {
        targets: target_indices,
        verified: false,
    });

    Ok(Json(serde_json::json!({
        "code": 0, "message": "success",
        "data": {
            "captchaId": captcha_id,
            "hint": hint,
            "items": items
        }
    })))
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct VerifyRequest {
    captcha_id: String,
    indices: Vec<usize>,
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct ClickVerifyRequest {
    captcha_id: String,
    positions: Vec<usize>,
}

async fn verify_captcha(
    Json(req): Json<VerifyRequest>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    do_verify(&req.captcha_id, &req.indices)
}

async fn click_verify_captcha(
    Json(req): Json<ClickVerifyRequest>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    do_verify(&req.captcha_id, &req.positions)
}

fn do_verify(captcha_id: &str, positions: &[usize]) -> Result<Json<serde_json::Value>, StatusCode> {
    let store = get_store();
    let (_id, data) = store.remove(captcha_id)
        .ok_or(StatusCode::BAD_REQUEST)?;

    if data.verified {
        return Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": true})));
    }

    let targets = &data.targets;
    let selected: Vec<usize> = positions.to_vec();
    let correct = targets.iter().all(|t| selected.contains(t)) && selected.len() == targets.len();

    Ok(Json(serde_json::json!({"code": 0, "message": "success", "data": correct})))
}
