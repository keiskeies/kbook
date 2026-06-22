use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize)]
pub struct ApiResult<T: Serialize> {
    pub code: i32,
    pub message: String,
    pub data: Option<T>,
}

impl<T: Serialize> ApiResult<T> {
    pub fn success(data: T) -> Self {
        Self { code: 0, message: "success".into(), data: Some(data) }
    }
    pub fn error(code: i32, message: impl Into<String>) -> Self {
        Self { code, message: message.into(), data: None }
    }
}

#[derive(Debug, Deserialize)]
pub struct PageParams {
    pub page: Option<i32>,
    pub size: Option<i32>,
}

#[derive(Debug, Serialize)]
pub struct PageResult<T: Serialize> {
    pub list: Vec<T>,
    pub total: i64,
    pub page: i32,
    pub size: i32,
}

#[derive(Debug, Deserialize)]
pub struct LoginRequest {
    pub email: String,
    pub password: Option<String>,
    pub code: Option<String>,
    pub captcha_id: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct LoginResponse {
    pub token: String,
    pub refresh_token: String,
    pub user_info: UserInfo,
}

#[derive(Debug, Serialize)]
pub struct UserInfo {
    pub id: i64,
    pub email: String,
    pub nickname: Option<String>,
    pub avatar: Option<String>,
    pub role: String,
    pub status: String,
    pub email_bound: bool,
}

#[derive(Debug, Deserialize)]
pub struct RegisterRequest {
    pub email: String,
    pub code: String,
    pub password: String,
    pub birthday: Option<String>,
    pub gender: Option<String>,
    pub married: Option<bool>,
    pub has_children: Option<bool>,
    pub mbti: Option<String>,
    pub occupation: Option<String>,
    pub education: Option<String>,
    pub entrepreneurship: Option<String>,
    pub annual_income: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct AiChatRequest {
    pub message: String,
    pub session_id: Option<String>,
    pub regenerate: Option<bool>,
}

#[derive(Debug, Deserialize)]
pub struct BookChatRequest {
    pub message: String,
    pub session_id: Option<String>,
    pub regenerate: Option<bool>,
}

#[derive(Debug, Deserialize)]
pub struct CreateDebateSessionRequest {
    pub topic: String,
    pub topic_source: Option<String>,
    pub book_context: Option<String>,
    pub pro_role_keys: Option<String>,
    pub con_role_keys: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct CreateRoundTableSessionRequest {
    pub role_keys: Vec<String>,
    pub role_configs: String,
}

#[derive(Debug, Deserialize)]
pub struct ReportProgressRequest {
    pub book_id: i64,
    pub progress: f64,
    pub current_position: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct RateBookRequest {
    pub rating: f64,
}

#[derive(Debug, Deserialize)]
pub struct UpdateProfileRequest {
    pub nickname: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct SendCodeRequest {
    pub email: String,
    pub scene: Option<String>,
    pub captcha_id: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct ChangePasswordRequest {
    pub old_password: String,
    pub new_password: String,
}

#[derive(Debug, Deserialize)]
pub struct ResetPasswordRequest {
    pub email: String,
    pub code: String,
    pub new_password: String,
}
