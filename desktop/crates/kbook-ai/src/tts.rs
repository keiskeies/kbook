use reqwest::Client;

pub struct TtsService;

impl TtsService {
    pub fn new() -> Self {
        Self
    }

    pub async fn get_azure_token(
        &self,
        base_url: &str,
        api_key: &str,
    ) -> anyhow::Result<(String, String)> {
        let client = Client::new();
        let url = format!("{}/sts/v1.0/issueToken", base_url);
        let resp = client.post(&url)
            .header("Ocp-Apim-Subscription-Key", api_key)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .send()
            .await?;
        let token = resp.text().await?;
        Ok((token, "eastus".to_string()))
    }

    pub async fn get_xfyun_auth(
        &self,
        base_url: &str,
        api_key: &str,
        api_secret: &str,
    ) -> anyhow::Result<String> {
        use sha2::Sha256;
        use hmac::{Hmac, Mac};
        use base64::Engine;

        type HmacSha256 = Hmac<Sha256>;

        let host = url::Url::parse(base_url)?;
        let path = host.path();
        let date = chrono::Utc::now().format("%a, %d %b %Y %H:%M:%S GMT").to_string();

        let signature_string = format!("host: {}\ndate: {}\nGET {} HTTP/1.1", host.host_str().unwrap_or(""), date, path);

        let mut mac = HmacSha256::new_from_slice(api_secret.as_bytes())?;
        mac.update(signature_string.as_bytes());
        let signature = base64::engine::general_purpose::STANDARD.encode(mac.finalize().into_bytes());

        let authorization = format!(
            "api_key=\"{}\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"{}\"",
            api_key, signature
        );

        let auth_url = format!("{}?host={}&date={}&authorization={}", base_url, host.host_str().unwrap_or(""), urlencoding::encode(&date), urlencoding::encode(&authorization));
        Ok(auth_url)
    }
}
