use serde::Deserialize;
use std::path::Path;

#[derive(Debug, Clone, Deserialize)]
pub struct AppConfig {
    pub server: ServerConfig,
    pub database: DatabaseConfig,
    pub jwt: JwtConfig,
    pub mail: MailConfig,
    pub storage: StorageConfig,
    pub vector: VectorConfig,
    pub ai: AiSettings,
    pub admin: AdminConfig,
    #[serde(default)]
    pub book_paths: BookPathsConfig,
    #[serde(default)]
    pub upload: UploadConfig,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ServerConfig {
    #[serde(default = "default_port")]
    pub port: u16,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DatabaseConfig {
    #[serde(default = "default_db_path")]
    pub path: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct JwtConfig {
    pub secret: String,
    #[serde(default = "default_access_exp")]
    pub access_expiration: i64,
    #[serde(default = "default_refresh_exp")]
    pub refresh_expiration: i64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct MailConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default = "default_smtp_host")]
    pub smtp_host: String,
    #[serde(default = "default_smtp_port")]
    pub smtp_port: u16,
    #[serde(default)]
    pub username: String,
    #[serde(default)]
    pub password: String,
}

#[derive(Debug, Clone, Deserialize, Default)]
pub struct BookPathsConfig {
    #[serde(default = "default_books_dir")]
    pub epub: String,
    #[serde(default = "default_books_dir2")]
    pub pdf: String,
    #[serde(default = "default_books_dir3")]
    pub txt: String,
}

#[derive(Debug, Clone, Deserialize, Default)]
pub struct UploadConfig {
    #[serde(default = "default_avatar_dir")]
    pub avatar_dir: String,
    #[serde(default)]
    pub chat_dir: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct StorageConfig {
    #[serde(default = "default_books_dir")]
    pub books_dir: String,
    #[serde(default = "default_covers_dir")]
    pub covers_dir: String,
    #[serde(default = "default_avatar_dir")]
    pub avatar_dir: String,
    #[serde(default = "default_tts_cache_dir")]
    pub tts_cache_dir: String,
    #[serde(default)]
    pub ai_config_path: String,
    #[serde(default)]
    pub ffmpeg_path: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct VectorConfig {
    #[serde(default = "default_vector_path")]
    pub db_path: String,
    #[serde(default = "default_chunk_size")]
    pub chunk_size: usize,
    #[serde(default = "default_chunk_overlap")]
    pub chunk_overlap: usize,
    #[serde(default = "default_rag_top_k")]
    pub rag_top_k: usize,
}

#[derive(Debug, Clone, Deserialize)]
pub struct AiSettings {
    #[serde(default)]
    pub vision_model: String,
    #[serde(default = "default_vision_timeout")]
    pub vision_timeout: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct AdminConfig {
    #[serde(default = "default_admin_email")]
    pub email: String,
    #[serde(default = "default_admin_password")]
    pub password: String,
}

fn default_port() -> u16 { 8282 }
fn default_db_path() -> String { "kbook.db".into() }
fn default_access_exp() -> i64 { 7200 }
fn default_refresh_exp() -> i64 { 604800 }
fn default_smtp_host() -> String { "smtp.qq.com".into() }
fn default_smtp_port() -> u16 { 465 }
fn default_books_dir() -> String { "data/books/epub".into() }
fn default_books_dir2() -> String { "data/books/pdf".into() }
fn default_books_dir3() -> String { "data/books/txt".into() }
fn default_covers_dir() -> String { "data/covers".into() }
fn default_avatar_dir() -> String { "data/avatars".into() }
fn default_tts_cache_dir() -> String { "data/tts_cache".into() }
fn default_vector_path() -> String { "data/vectors".into() }
fn default_chunk_size() -> usize { 800 }
fn default_chunk_overlap() -> usize { 200 }
fn default_rag_top_k() -> usize { 100 }
fn default_vision_timeout() -> u64 { 600 }
fn default_admin_email() -> String { "admin@kbook.com".into() }
fn default_admin_password() -> String { "admin123456".into() }

const DEFAULT_CONFIG: &str = r#"[server]
port = 8282

[database]
path = "kbook.db"

[jwt]
secret = "kbook-default-secret-key-must-be-at-least-256-bits-long-for-hs256"
access_expiration = 7200
refresh_expiration = 604800

[mail]
enabled = false
smtp_host = "smtp.qq.com"
smtp_port = 465
username = ""
password = ""

[storage]
books_dir = "data/books"
covers_dir = "data/covers"
avatar_dir = "data/avatars"
tts_cache_dir = "data/tts_cache"
ai_config_path = ""
ffmpeg_path = ""

[book_paths]
epub = "data/books/epub"
pdf = "data/books/pdf"
txt = "data/books/txt"

[upload]
avatar_dir = "data/avatars"
chat_dir = "data/chat"

[vector]
db_path = "data/vectors"
chunk_size = 800
chunk_overlap = 200
rag_top_k = 100

[ai]
vision_model = ""
vision_timeout = 600

[admin]
email = "admin@kbook.com"
password = "admin123456"
"#;

impl AppConfig {
    pub fn load(app_dir: &Path) -> anyhow::Result<Self> {
        let config_path = app_dir.join("config.toml");
        if !config_path.exists() {
            std::fs::create_dir_all(app_dir)?;
            std::fs::write(&config_path, DEFAULT_CONFIG)?;
        }
        let config = config::Config::builder()
            .add_source(config::File::from(config_path.as_path()).format(config::FileFormat::Toml))
            .add_source(
                config::Environment::with_prefix("KBOOK").separator("__"),
            )
            .build()?
            .try_deserialize()?;
        Ok(config)
    }
}
