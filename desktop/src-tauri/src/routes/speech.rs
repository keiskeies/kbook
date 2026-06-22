use axum::{
    extract::State,
    routing::{get, post},
    http::StatusCode,
    Json, Router,
};
use std::sync::Arc;
use kbook_db::Database;
use hmac::{Hmac, Mac};
use sha2::Sha256;

type HmacSha256 = Hmac<Sha256>;

pub fn speech_routes(db: Arc<Database>) -> Router {
    Router::new()
        .route("/azure/token", get(get_azure_token))
        .route("/xfyun/auth", post(get_xfyun_auth))
        .with_state(db)
}

async fn get_azure_token(
    State(db): State<Arc<Database>>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let config = sqlx::query_as::<_, kbook_core::entity::TtsConfig>(
        "SELECT * FROM tts_config WHERE provider = 'AZURE' AND enabled = 1 LIMIT 1"
    )
    .fetch_optional(&db.pool)
    .await
    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
    .ok_or_else(|| {
        tracing::warn!("Azure Speech 未配置");
        StatusCode::NOT_FOUND
    })?;

    let region = config.base_url.as_deref().unwrap_or("eastus");
    let api_key = config.api_key.as_deref().unwrap_or("");

    let url = format!("https://{}.api.cognitive.microsoft.com/sts/v1.0/issueToken", region);
    let client = reqwest::Client::new();
    let resp = client.post(&url)
        .header("Ocp-Apim-Subscription-Key", api_key)
        .send()
        .await
        .map_err(|e| {
            tracing::error!("Azure token request failed: {e}");
            StatusCode::INTERNAL_SERVER_ERROR
        })?;

    if !resp.status().is_success() {
        tracing::error!("Azure token failed: {}", resp.status());
        return Err(StatusCode::INTERNAL_SERVER_ERROR);
    }

    let token = resp.text().await.unwrap_or_default();

    Ok(Json(serde_json::json!({
        "code": 0, "message": "success",
        "data": {"token": token, "region": region}
    })))
}

async fn get_xfyun_auth(
    State(db): State<Arc<Database>>,
    Json(_req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let config = sqlx::query_as::<_, kbook_core::entity::TtsConfig>(
        "SELECT * FROM tts_config WHERE provider = 'IFLYTEK' AND enabled = 1 LIMIT 1"
    )
    .fetch_optional(&db.pool)
    .await
    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
    .ok_or_else(|| {
        tracing::warn!("讯飞语音未配置");
        StatusCode::NOT_FOUND
    })?;

    let host = "tts-api.xfyun.cn";
    let path = "/v2/tts";
    let api_key = config.api_key.as_deref().unwrap_or("");
    let api_secret = config.api_secret.as_deref().unwrap_or("");
    let app_id = config.app_id.as_deref().unwrap_or("");

    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs();
    let date = {
        let days = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
        let months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
        let secs_per_day = 86400;
        let days_since_epoch = (now / secs_per_day) as i64;
        let day_of_week = ((days_since_epoch + 4) % 7) as usize;
        let mut y = 1970i64;
        let mut remaining = days_since_epoch;
        loop {
            let days_in_year = if (y % 4 == 0 && y % 100 != 0) || y % 400 == 0 { 366 } else { 365 };
            if remaining < days_in_year { break; }
            remaining -= days_in_year;
            y += 1;
        }
        let mut m = 0usize;
        let days_in_months = [31,28,31,30,31,30,31,31,30,31,30,31];
        let leap = if (y % 4 == 0 && y % 100 != 0) || y % 400 == 0 { 1 } else { 0 };
        for (i, &d) in days_in_months.iter().enumerate() {
            let dim = if i == 1 { d + leap } else { d };
            if remaining < dim as i64 { m = i; break; }
            remaining -= dim as i64;
        }
        format!("{}, {:02} {} {} {:02}:{:02}:{:02} GMT",
            days[day_of_week], remaining + 1, months[m], y,
            (now % 86400) / 3600, (now % 3600) / 60, now % 60)
    };
    let signature_origin = format!("host: {}\ndate: {}\nGET {} HTTP/1.1", host, date, path);

    let mut mac = HmacSha256::new_from_slice(api_secret.as_bytes())
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    mac.update(signature_origin.as_bytes());
    let signature = base64::Engine::encode(
        &base64::engine::general_purpose::STANDARD,
        &mac.finalize().into_bytes(),
    );

    let authorization_origin = format!(
        "api_key=\"{}\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"{}\"",
        api_key, signature
    );
    let authorization = base64::Engine::encode(
        &base64::engine::general_purpose::STANDARD,
        authorization_origin.as_bytes(),
    );

    let ws_url = format!(
        "wss://{}{}?host={}&date={}&authorization={}",
        host, path,
        urlencoding::encode(host),
        urlencoding::encode(&date),
        urlencoding::encode(&authorization),
    );

    Ok(Json(serde_json::json!({
        "code": 0, "message": "success",
        "data": {"wsUrl": ws_url, "appId": app_id}
    })))
}
