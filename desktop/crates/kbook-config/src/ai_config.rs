use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
pub struct AiConfig {
    #[serde(default)]
    pub book_chat: BookChatConfig,
    #[serde(default)]
    pub round_table: RoundTableConfig,
    #[serde(default)]
    pub debate: DebateConfig,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct BookChatConfig {
    #[serde(default = "default_style")]
    pub default_style: String,
    #[serde(default)]
    pub styles: Vec<BookChatStyle>,
}

fn default_style() -> String { "DEEP".into() }

#[derive(Debug, Clone, Deserialize)]
pub struct BookChatStyle {
    pub key: String,
    pub name: String,
    pub title: String,
    pub prompt: String,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct RoundTableConfig {
    #[serde(default)]
    pub host: RoleConfig,
    #[serde(default)]
    pub settings: RoundTableSettings,
    #[serde(default)]
    pub roles: Vec<RoleConfig>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct RoundTableSettings {
    #[serde(default = "default_max_roles")]
    pub max_roles_per_session: usize,
    #[serde(default)]
    pub default_selected_keys: Vec<String>,
}

fn default_max_roles() -> usize { 20 }

#[derive(Debug, Clone, Default, Deserialize)]
pub struct RoleConfig {
    pub key: String,
    pub name: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub group: String,
    #[serde(default)]
    pub color: String,
    #[serde(default)]
    pub icon: String,
    #[serde(default)]
    pub tts: Option<TtsParams>,
    #[serde(default)]
    pub prompt: String,
    #[serde(default)]
    pub catchphrase: String,
    #[serde(default)]
    pub params: Option<RoleParams>,
    #[serde(default)]
    pub search_keywords: Vec<String>,
    #[serde(default)]
    pub tags: Vec<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct TtsParams {
    #[serde(default = "default_one")]
    pub pitch: f64,
    #[serde(default = "default_one")]
    pub rate: f64,
}

fn default_one() -> f64 { 1.0 }

#[derive(Debug, Clone, Default, Deserialize)]
pub struct RoleParams {
    #[serde(default)]
    pub grab_weight: i32,
    #[serde(default)]
    pub verbosity: i32,
    #[serde(default)]
    pub opinionated: i32,
    #[serde(default)]
    pub challenge: i32,
    #[serde(default)]
    pub empathy: i32,
    #[serde(default)]
    pub humor: i32,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct DebateConfig {
    #[serde(default)]
    pub host: RoleConfig,
    #[serde(default)]
    pub roles: Vec<RoleConfig>,
}
