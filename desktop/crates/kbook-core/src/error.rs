use thiserror::Error;

#[derive(Error, Debug)]
pub enum AppError {
    #[error("Database error: {0}")]
    Database(#[from] sqlx::Error),

    #[error("Not found: {0}")]
    NotFound(String),

    #[error("Unauthorized")]
    Unauthorized,

    #[error("Forbidden")]
    Forbidden,

    #[error("Bad request: {0}")]
    BadRequest(String),

    #[error("Internal error: {0}")]
    Internal(String),

    #[error("AI error: {0}")]
    Ai(String),
}

impl serde::Serialize for AppError {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        let (code, message) = match self {
            AppError::Database(e) => (500, e.to_string()),
            AppError::NotFound(msg) => (404, msg.clone()),
            AppError::Unauthorized => (401, "Unauthorized".into()),
            AppError::Forbidden => (403, "Forbidden".into()),
            AppError::BadRequest(msg) => (400, msg.clone()),
            AppError::Internal(msg) => (500, msg.clone()),
            AppError::Ai(msg) => (500, msg.clone()),
        };
        let obj = serde_json::json!({ "code": code, "message": message });
        obj.serialize(serializer)
    }
}

pub type Result<T> = std::result::Result<T, AppError>;
