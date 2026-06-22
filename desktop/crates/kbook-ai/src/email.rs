use lettre::{transport::smtp::authentication::Credentials, Message, SmtpTransport, Transport};

pub struct EmailService {
    enabled: bool,
    smtp_host: String,
    smtp_port: u16,
    username: String,
    password: String,
    from: String,
}

impl EmailService {
    pub fn new(enabled: bool, smtp_host: &str, smtp_port: u16, username: &str, password: &str) -> Self {
        Self {
            enabled,
            smtp_host: smtp_host.to_string(),
            smtp_port,
            username: username.to_string(),
            password: password.to_string(),
            from: format!("KBook <{}>", username),
        }
    }

    pub async fn send_verification_code(&self, to: &str, code: &str) -> anyhow::Result<()> {
        if !self.enabled {
            tracing::info!("Email disabled, verification code for {}: {}", to, code);
            return Ok(());
        }

        let email = Message::builder()
            .from(self.from.parse()?)
            .to(format!("User <{}>", to).parse()?)
            .subject("KBook 验证码")
            .body(format!("您的验证码是：{}\n有效期 5 分钟，请勿泄露给他人。", code))?;

        let creds = Credentials::new(self.username.clone(), self.password.clone());
        let mailer = SmtpTransport::relay(&self.smtp_host)?
            .port(self.smtp_port)
            .credentials(creds)
            .build();

        mailer.send(&email)?;
        Ok(())
    }
}
