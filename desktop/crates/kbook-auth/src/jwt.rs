use jsonwebtoken::{encode, decode, Header, Validation, EncodingKey, DecodingKey};
use serde::{Deserialize, Serialize};
use chrono::{Utc, Duration};

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct Claims {
    pub sub: i64,
    pub role: String,
    pub exp: usize,
    pub iat: usize,
}

pub fn create_access_token(user_id: i64, role: &str, secret: &str, expires_in_secs: i64) -> anyhow::Result<String> {
    let now = Utc::now();
    let claims = Claims {
        sub: user_id,
        role: role.to_string(),
        exp: (now + Duration::seconds(expires_in_secs)).timestamp() as usize,
        iat: now.timestamp() as usize,
    };
    let token = encode(&Header::default(), &claims, &EncodingKey::from_secret(secret.as_bytes()))?;
    Ok(token)
}

pub fn create_refresh_token(user_id: i64, role: &str, secret: &str, expires_in_secs: i64) -> anyhow::Result<String> {
    create_access_token(user_id, role, secret, expires_in_secs)
}

pub fn verify_token(token: &str, secret: &str) -> anyhow::Result<Claims> {
    let token_data = decode::<Claims>(
        token,
        &DecodingKey::from_secret(secret.as_bytes()),
        &Validation::default(),
    )?;
    Ok(token_data.claims)
}
