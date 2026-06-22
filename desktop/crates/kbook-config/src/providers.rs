use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
pub struct AiProviders {
    pub providers: Vec<Provider>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Provider {
    pub id: String,
    pub name: String,
    #[serde(rename = "provider")]
    pub provider_type: String,
    pub base_url: String,
    #[serde(default)]
    pub region: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub api_key_url: String,
    #[serde(default)]
    pub website_url: String,
    #[serde(default)]
    pub models: Vec<Model>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Model {
    pub name: String,
    #[serde(default)]
    pub label: String,
    #[serde(default)]
    pub free: bool,
    #[serde(default = "default_max_tokens")]
    pub max_tokens: i32,
}

fn default_max_tokens() -> i32 { 131072 }
