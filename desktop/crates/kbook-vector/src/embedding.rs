use reqwest::Client;
use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize)]
struct EmbeddingRequest {
    model: String,
    input: Vec<String>,
}

#[derive(Debug, Deserialize)]
struct EmbeddingResponse {
    data: Vec<EmbeddingData>,
}

#[derive(Debug, Deserialize)]
struct EmbeddingData {
    embedding: Vec<f32>,
}

#[derive(Clone)]
pub struct EmbeddingClient {
    client: Client,
    base_url: String,
    model: String,
    api_key: Option<String>,
}

impl EmbeddingClient {
    pub fn new(base_url: &str, model: &str, api_key: Option<&str>) -> Self {
        Self {
            client: Client::new(),
            base_url: base_url.to_string(),
            model: model.to_string(),
            api_key: api_key.map(|s| s.to_string()),
        }
    }

    pub async fn embed(&self, texts: &[String]) -> anyhow::Result<Vec<Vec<f32>>> {
        let url = format!("{}/embeddings", self.base_url);
        let mut request = self.client.post(&url)
            .json(&EmbeddingRequest {
                model: self.model.clone(),
                input: texts.to_vec(),
            });
        if let Some(key) = &self.api_key {
            request = request.bearer_auth(key);
        }
        let resp = request.send().await?.json::<EmbeddingResponse>().await?;
        Ok(resp.data.into_iter().map(|d| d.embedding).collect())
    }

    pub async fn embed_single(&self, text: &str) -> anyhow::Result<Vec<f32>> {
        let mut results = self.embed(&[text.to_string()]).await?;
        results.pop().ok_or_else(|| anyhow::anyhow!("No embedding returned"))
    }
}
