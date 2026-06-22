use axum::{
    extract::{Request, State},
    http::StatusCode,
    middleware::Next,
    response::Response,
    routing::post,
    Json, Router,
};
use std::sync::Arc;
use kbook_db::Database;
use kbook_auth::jwt;
use kbook_auth::password;

pub fn auth_routes(db: Arc<Database>) -> Router {
    Router::new()
        .route("/send-code", post(send_code))
        .route("/login/code", post(login_by_code))
        .route("/login/password", post(login_by_password))
        .route("/register", post(register))
        .route("/refresh", post(refresh_token))
        .route("/change-password", post(change_password))
        .route("/reset-password", post(reset_password))
        .with_state(db)
}

pub async fn auth_middleware(
    mut request: Request,
    next: Next,
) -> Result<Response, StatusCode> {
    let auth_header = request
        .headers()
        .get("Authorization")
        .and_then(|v| v.to_str().ok());

    let token = match auth_header.and_then(|h| h.strip_prefix("Bearer ")) {
        Some(t) => t,
        None => return Err(StatusCode::UNAUTHORIZED),
    };

    let secret = std::env::var("KBOOK_JWT_SECRET")
        .unwrap_or_else(|_| "kbook-default-secret-key-must-be-at-least-256-bits-long-for-hs256".into());

    match jwt::verify_token(token, &secret) {
        Ok(claims) => {
            request.extensions_mut().insert(claims);
            Ok(next.run(request).await)
        }
        Err(_) => Err(StatusCode::UNAUTHORIZED),
    }
}

fn ok(data: serde_json::Value) -> Json<serde_json::Value> {
    Json(serde_json::json!({"code": 0, "message": "success", "data": data}))
}

fn err(code: i32, msg: &str) -> Json<serde_json::Value> {
    Json(serde_json::json!({"code": code, "message": msg}))
}

fn user_info_json(user: &kbook_core::entity::User) -> serde_json::Value {
    serde_json::json!({
        "id": user.id, "email": user.email, "nickname": user.nickname,
        "avatar": user.avatar, "role": user.role, "status": user.status,
        "emailBound": user.email_bound
    })
}

fn make_tokens(user: &kbook_core::entity::User) -> Result<Json<serde_json::Value>, StatusCode> {
    let secret = std::env::var("KBOOK_JWT_SECRET")
        .unwrap_or_else(|_| "kbook-default-secret-key-must-be-at-least-256-bits-long-for-hs256".into());
    let token = jwt::create_access_token(user.id, &user.role, &secret, 7200)
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let refresh = jwt::create_refresh_token(user.id, &user.role, &secret, 604800)
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    Ok(ok(serde_json::json!({
        "token": token, "refreshToken": refresh, "userInfo": user_info_json(user)
    })))
}

async fn send_code(
    State(_db): State<Arc<Database>>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let email = req.get("email").and_then(|v| v.as_str()).ok_or(StatusCode::BAD_REQUEST)?;
    tracing::info!("Send verification code to {}", email);
    Ok(ok(serde_json::json!(null)))
}

async fn login_by_code(
    State(db): State<Arc<Database>>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let email = req.get("email").and_then(|v| v.as_str()).ok_or(StatusCode::BAD_REQUEST)?;
    let user = kbook_db::repository::user_repo::find_by_email(&db.pool, email)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .ok_or(StatusCode::UNAUTHORIZED)?;
    make_tokens(&user)
}

async fn login_by_password(
    State(db): State<Arc<Database>>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let email = req.get("email").and_then(|v| v.as_str()).ok_or(StatusCode::BAD_REQUEST)?;
    let pw = req.get("password").and_then(|v| v.as_str()).ok_or(StatusCode::BAD_REQUEST)?;

    let user = kbook_db::repository::user_repo::find_by_email(&db.pool, email)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .ok_or_else(|| {
            tracing::warn!("Login failed: user not found: {}", email);
            StatusCode::UNAUTHORIZED
        })?;

    if user.password.as_deref().unwrap_or("").is_empty() {
        tracing::warn!("Login failed: user has no password set: {}", email);
        return Err(StatusCode::UNAUTHORIZED);
    }

    let valid = password::verify_password(pw, user.password.as_deref().unwrap_or(""))
        .unwrap_or(false);
    if !valid {
        tracing::warn!("Login failed: wrong password for {}", email);
        return Err(StatusCode::UNAUTHORIZED);
    }

    make_tokens(&user)
}

async fn register(
    State(db): State<Arc<Database>>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let email = req.get("email").and_then(|v| v.as_str()).ok_or(StatusCode::BAD_REQUEST)?;
    let pw = req.get("password").and_then(|v| v.as_str()).ok_or(StatusCode::BAD_REQUEST)?;

    if kbook_db::repository::user_repo::find_by_email(&db.pool, email)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .is_some()
    {
        return Ok(err(400, "邮箱已注册"));
    }

    let hash = password::hash_password(pw)
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    let nickname = email.split('@').next().unwrap_or("user");
    let user = kbook_db::repository::user_repo::create(&db.pool, email, &hash, nickname)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    make_tokens(&user)
}

async fn refresh_token(
    State(db): State<Arc<Database>>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let refresh = req.get("refreshToken").and_then(|v| v.as_str()).ok_or(StatusCode::BAD_REQUEST)?;
    let secret = std::env::var("KBOOK_JWT_SECRET")
        .unwrap_or_else(|_| "kbook-default-secret-key-must-be-at-least-256-bits-long-for-hs256".into());
    let claims = jwt::verify_token(refresh, &secret).map_err(|_| StatusCode::UNAUTHORIZED)?;
    let user = kbook_db::repository::user_repo::find_by_id(&db.pool, claims.sub)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .ok_or(StatusCode::NOT_FOUND)?;
    make_tokens(&user)
}

async fn change_password(
    State(db): State<Arc<Database>>,
    axum::extract::Extension(claims): axum::extract::Extension<jwt::Claims>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let old_pw = req.get("oldPassword").and_then(|v| v.as_str()).ok_or(StatusCode::BAD_REQUEST)?;
    let new_pw = req.get("newPassword").and_then(|v| v.as_str()).ok_or(StatusCode::BAD_REQUEST)?;

    let user = kbook_db::repository::user_repo::find_by_id(&db.pool, claims.sub)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .ok_or(StatusCode::NOT_FOUND)?;

    if !password::verify_password(old_pw, user.password.as_deref().unwrap_or("")).unwrap_or(false) {
        return Ok(err(400, "原密码错误"));
    }

    let new_hash = password::hash_password(new_pw)
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    kbook_db::repository::user_repo::update_password(&db.pool, claims.sub, &new_hash)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(ok(serde_json::json!(null)))
}

async fn reset_password(
    State(db): State<Arc<Database>>,
    Json(req): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let email = req.get("email").and_then(|v| v.as_str()).ok_or(StatusCode::BAD_REQUEST)?;
    let new_pw = req.get("newPassword").and_then(|v| v.as_str()).ok_or(StatusCode::BAD_REQUEST)?;

    let user = kbook_db::repository::user_repo::find_by_email(&db.pool, email)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .ok_or(StatusCode::NOT_FOUND)?;

    let hash = password::hash_password(new_pw)
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    kbook_db::repository::user_repo::update_password(&db.pool, user.id, &hash)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    Ok(ok(serde_json::json!(null)))
}
