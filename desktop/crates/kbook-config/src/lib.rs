pub mod app_config;
pub mod ai_config;
pub mod providers;
pub mod paths;

pub use app_config::AppConfig;
pub use ai_config::AiConfig;
pub use providers::{AiProviders, Provider, Model};

use std::path::Path;

pub fn load_ai_config(app_dir: &Path) -> anyhow::Result<AiConfig> {
    let path = app_dir.join("ai-config.json");
    if !path.exists() {
        std::fs::write(&path, include_str!("defaults/ai-config.json"))?;
    }
    let content = std::fs::read_to_string(&path)?;
    Ok(serde_json::from_str(&content)?)
}

pub fn load_providers(app_dir: &Path) -> anyhow::Result<AiProviders> {
    let path = app_dir.join("ai-providers.yml");
    if !path.exists() {
        std::fs::write(&path, include_str!("defaults/ai-providers.yml"))?;
    }
    let content = std::fs::read_to_string(&path)?;
    Ok(serde_yaml::from_str(&content)?)
}
