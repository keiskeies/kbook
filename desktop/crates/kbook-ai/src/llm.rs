use reqwest::Client;
use serde::{Deserialize, Serialize};
use futures::StreamExt;
use tokio::sync::mpsc;
use std::collections::HashMap;
use sqlx::SqlitePool;

#[derive(Debug, Serialize, Clone)]
pub struct ChatMessage {
    pub role: String,
    pub content: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_calls: Option<Vec<ToolCall>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_call_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
}

impl ChatMessage {
    pub fn system(content: &str) -> Self {
        Self { role: "system".into(), content: content.to_string(), tool_calls: None, tool_call_id: None, name: None }
    }
    pub fn user(content: &str) -> Self {
        Self { role: "user".into(), content: content.to_string(), tool_calls: None, tool_call_id: None, name: None }
    }
    pub fn assistant(content: &str) -> Self {
        Self { role: "assistant".into(), content: content.to_string(), tool_calls: None, tool_call_id: None, name: None }
    }
    pub fn tool_result(tool_call_id: &str, name: &str, content: &str) -> Self {
        Self { role: "tool".into(), content: content.to_string(), tool_calls: None, tool_call_id: Some(tool_call_id.to_string()), name: Some(name.to_string()) }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ToolCall {
    pub id: String,
    #[serde(rename = "type")]
    pub call_type: String,
    pub function: FunctionCall,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct FunctionCall {
    pub name: String,
    pub arguments: String,
}

#[derive(Debug, Serialize, Clone)]
pub struct Tool {
    #[serde(rename = "type")]
    pub tool_type: String,
    pub function: ToolFunction,
}

#[derive(Debug, Serialize, Clone)]
pub struct ToolFunction {
    pub name: String,
    pub description: String,
    pub parameters: serde_json::Value,
}

#[derive(Debug, Serialize)]
pub struct ChatRequest {
    pub model: String,
    pub messages: Vec<ChatMessage>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tools: Option<Vec<Tool>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stream: Option<bool>,
}

#[derive(Debug, Deserialize)]
pub struct ChatResponse {
    pub choices: Vec<ChatChoice>,
}

#[derive(Debug, Deserialize)]
pub struct ChatChoice {
    pub message: ChatResponseMessage,
}

#[derive(Debug, Deserialize)]
pub struct ChatResponseMessage {
    pub role: String,
    pub content: Option<String>,
    pub tool_calls: Option<Vec<ToolCall>>,
}

#[derive(Debug, Deserialize)]
pub struct StreamChunk {
    pub choices: Vec<StreamChoice>,
}

#[derive(Debug, Deserialize)]
pub struct StreamChoice {
    pub delta: StreamDelta,
    pub finish_reason: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct StreamDelta {
    pub role: Option<String>,
    pub content: Option<String>,
    pub tool_calls: Option<Vec<ToolCallDelta>>,
}

#[derive(Debug, Deserialize, Clone)]
pub struct ToolCallDelta {
    pub index: usize,
    pub id: Option<String>,
    #[serde(rename = "type")]
    pub call_type: Option<String>,
    pub function: Option<FunctionCallDelta>,
}

#[derive(Debug, Deserialize, Clone)]
pub struct FunctionCallDelta {
    pub name: Option<String>,
    pub arguments: Option<String>,
}

/// Provider info stored alongside the client for model lookup
struct ProviderInfo {
    client: LlmClient,
    model_name: String,
}

#[derive(Clone)]
pub struct LlmClient {
    client: Client,
    base_url: String,
    api_key: Option<String>,
    /// Default model name for this client (used when no specific model is requested)
    default_model: Option<String>,
}

impl LlmClient {
    pub fn new(base_url: &str, api_key: Option<&str>) -> Self {
        Self {
            client: Client::builder().timeout(std::time::Duration::from_secs(300)).build().unwrap_or_default(),
            base_url: base_url.to_string(),
            api_key: api_key.map(|s| s.to_string()),
            default_model: None,
        }
    }

    pub fn with_model(mut self, model: impl Into<String>) -> Self {
        self.default_model = Some(model.into());
        self
    }

    pub async fn chat(
        &self,
        model: &str,
        messages: &[ChatMessage],
        tools: Option<Vec<Tool>>,
    ) -> anyhow::Result<ChatResponse> {
        let url = format!("{}/chat/completions", self.base_url);
        let mut request = self.client.post(&url)
            .json(&ChatRequest {
                model: model.to_string(),
                messages: messages.to_vec(),
                tools,
                stream: Some(false),
            });
        if let Some(key) = &self.api_key {
            request = request.bearer_auth(key);
        }
        let resp = request.send().await?.json::<ChatResponse>().await?;
        Ok(resp)
    }

    pub async fn stream_chat(
        &self,
        model: &str,
        messages: &[ChatMessage],
        tools: Option<Vec<Tool>>,
    ) -> anyhow::Result<mpsc::Receiver<anyhow::Result<StreamChunk>>> {
        let url = format!("{}/chat/completions", self.base_url);
        let mut request = self.client.post(&url)
            .json(&ChatRequest {
                model: model.to_string(),
                messages: messages.to_vec(),
                tools,
                stream: Some(true),
            });
        if let Some(key) = &self.api_key {
            request = request.bearer_auth(key);
        }

        let response = request.send().await?;
        let byte_stream = response.bytes_stream();
        let (tx, rx) = mpsc::channel(200);

        tokio::spawn(async move {
            let mut buffer = String::new();
            let mut stream = byte_stream;
            while let Some(chunk) = stream.next().await {
                match chunk {
                    Ok(bytes) => {
                        buffer.push_str(&String::from_utf8_lossy(&bytes));
                        while let Some(line_end) = buffer.find('\n') {
                            let line = buffer[..line_end].trim().to_string();
                            buffer = buffer[line_end + 1..].to_string();
                            if line.is_empty() || !line.starts_with("data: ") {
                                continue;
                            }
                            let data = &line[6..];
                            if data == "[DONE]" {
                                let _ = tx.send(Ok(StreamChunk { choices: vec![] })).await;
                                break;
                            }
                            match serde_json::from_str::<StreamChunk>(data) {
                                Ok(chunk) => { let _ = tx.send(Ok(chunk)).await; }
                                Err(e) => { tracing::warn!("SSE parse error: {}", e); }
                            }
                        }
                    }
                    Err(e) => {
                        let _ = tx.send(Err(anyhow::anyhow!("Stream error: {}", e))).await;
                        break;
                    }
                }
            }
        });

        Ok(rx)
    }

    /// Get the default model name for this client
    pub fn get_default_model(&self) -> Option<&str> {
        self.default_model.as_deref()
    }
}

// ── ChatModelFactory: Multi-provider management ───────────────────────

/// Factory that manages multiple LLM clients keyed by provider name.
/// Supports the same providers as Spring Boot's ChatModelManager:
/// openai, deepseek, zhipu, moonshot, qwen, baichuan, minimax, yi.
///
/// Usage:
/// ```ignore
/// let factory = ChatModelFactory::load_from_db(&pool).await?;
/// if let Some(client) = factory.get_client("deepseek") {
///     let model = factory.get_model("deepseek");
///     let response = client.chat(&model, &messages, None).await?;
/// }
/// ```
pub struct ChatModelFactory {
    providers: HashMap<String, ProviderInfo>,
    default_provider: String,
    default_model: String,
}

impl ChatModelFactory {
    /// Load all active AI provider configs from the database and create LLMClient instances.
    ///
    /// Reads from `ai_provider_config` table where `enabled = true`.
    /// The first enabled CHAT provider becomes the default.
    /// Provider names are normalized to lowercase.
    pub async fn load_from_db(pool: &SqlitePool) -> anyhow::Result<Self> {
        let rows = sqlx::query_as::<_, kbook_core::entity::AiProviderConfig>(
            "SELECT * FROM ai_provider_config WHERE enabled = 1 ORDER BY is_default DESC, id ASC"
        )
        .fetch_all(pool)
        .await?;

        let mut providers: HashMap<String, ProviderInfo> = HashMap::new();
        let mut default_provider = "openai".to_string();
        let mut default_model = "gpt-4o-mini".to_string();

        for config in rows {
            let provider_name = config.provider.clone()
                .unwrap_or_else(|| "openai".to_string())
                .to_lowercase();

            let base_url = config.base_url.unwrap_or_else(|| {
                // Fallback to known base URLs per provider
                resolve_base_url(&provider_name)
            });

            // Normalize base URL to end with /v1
            let base = normalize_base_url(&base_url);

            let model_name = config.model_name.unwrap_or_else(|| {
                resolve_default_model(&provider_name)
            });

            let client = LlmClient::new(&base, config.api_key.as_deref())
                .with_model(model_name.clone());

            // Track first enabled CHAT provider as default
            if providers.is_empty() && config.purpose.as_deref() != Some("EMBEDDING") {
                default_provider = provider_name.clone();
                default_model = model_name.clone();
            }

            // If explicitly marked as default, use it
            if config.is_default == Some(true) {
                default_provider = provider_name.clone();
                default_model = model_name.clone();
            }

            tracing::info!(
                "Loaded LLM provider: {} (model={}, base={})",
                provider_name, model_name, base
            );

            providers.insert(provider_name, ProviderInfo {
                client,
                model_name,
            });
        }

        // If no providers found from DB, create a fallback
        if providers.is_empty() {
            tracing::warn!("No active AI providers found in DB, using fallback openai-compatible endpoint");
            let fallback_client = LlmClient::new("http://localhost:11434/v1", None)
                .with_model("gemma4:e2b".to_string());
            providers.insert("openai".into(), ProviderInfo {
                client: fallback_client,
                model_name: "gemma4:e2b".to_string(),
            });
        }

        Ok(Self {
            providers,
            default_provider,
            default_model,
        })
    }

    /// Get an LLM client by provider name (case-insensitive).
    pub fn get_client(&self, provider: &str) -> Option<&LlmClient> {
        self.providers.get(&provider.to_lowercase()).map(|p| &p.client)
    }

    /// Get the default model name for a given provider.
    pub fn get_model(&self, provider: &str) -> String {
        self.providers.get(&provider.to_lowercase())
            .map(|p| p.model_name.clone())
            .unwrap_or_else(|| self.default_model.clone())
    }

    /// Get the default provider name.
    pub fn default_provider(&self) -> &str {
        &self.default_provider
    }

    /// Get the default model name (for the default provider).
    pub fn default_model(&self) -> &str {
        &self.default_model
    }

    /// Get the default client (for the default provider).
    pub fn default_client(&self) -> (&LlmClient, &str) {
        let provider = &self.default_provider;
        let model = &self.default_model;
        (
            self.get_client(provider).unwrap_or_else(|| {
                // Should never happen due to fallback in load_from_db
                tracing::error!("No default client available for provider '{}'", provider);
                std::process::abort()
            }),
            model,
        )
    }

    /// List all loaded provider names.
    pub fn list_providers(&self) -> Vec<&str> {
        self.providers.keys().map(|s| s.as_str()).collect()
    }

    /// Check if a specific provider is available.
    pub fn has_provider(&self, provider: &str) -> bool {
        self.providers.contains_key(&provider.to_lowercase())
    }
}

// ── Helper functions for provider defaults ─────────────────────────────

/// Resolve the base URL for well-known LLM providers.
fn resolve_base_url(provider: &str) -> String {
    match provider {
        "openai" => "https://api.openai.com/v1".to_string(),
        "deepseek" => "https://api.deepseek.com/v1".to_string(),
        "zhipu" | "glm" => "https://open.bigmodel.cn/api/paas/v4".to_string(),
        "moonshot" => "https://api.moonshot.cn/v1".to_string(),
        "qwen" | "dashscope" => "https://dashscope.aliyuncs.com/compatible-mode/v1".to_string(),
        "baichuan" => "https://api.baichuan-ai.com/v1".to_string(),
        "minimax" => "https://api.minimax.chat/v1".to_string(),
        "yi" | "lingyi" => "https://api.lingyiwanwu.com/v1".to_string(),
        _ => "http://localhost:11434/v1".to_string(),
    }
}

/// Resolve the default model name for well-known LLM providers.
fn resolve_default_model(provider: &str) -> String {
    match provider {
        "openai" => "gpt-4o-mini".to_string(),
        "deepseek" => "deepseek-chat".to_string(),
        "zhipu" | "glm" => "glm-4-flash".to_string(),
        "moonshot" => "moonshot-v1-8k".to_string(),
        "qwen" | "dashscope" => "qwen-plus".to_string(),
        "baichuan" => "Baichuan3-Turbo".to_string(),
        "minimax" => "abab6.5s-chat".to_string(),
        "yi" | "lingyi" => "yi-lightning".to_string(),
        _ => "gpt-4o-mini".to_string(),
    }
}

/// Ensure base URL ends with /v1 or /v4 (for zhipu compatibility).
fn normalize_base_url(url: &str) -> String {
    let trimmed = url.trim_end_matches('/');
    if trimmed.ends_with("/v4") || trimmed.ends_with("/v1") || trimmed.contains("/compatible-mode") {
        return trimmed.to_string();
    }
    // Default to /v1 suffix
    format!("{}/v1", trimmed)
}
