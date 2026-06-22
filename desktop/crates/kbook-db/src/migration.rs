use sqlx::SqlitePool;

pub async fn run_migrations(pool: &SqlitePool) -> anyhow::Result<()> {
    let admin_exists: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM users WHERE email = 'admin@kbook.com'")
        .fetch_one(pool)
        .await?;
    if admin_exists.0 == 0 {
        let hash = kbook_auth::password::hash_password("admin123456")?;
        sqlx::query(
            "INSERT OR IGNORE INTO users (email, password, nickname, role, status, email_bound) VALUES (?, ?, ?, ?, ?, ?)"
        )
        .bind("admin@kbook.com")
        .bind(&hash)
        .bind("管理员")
        .bind("ADMIN")
        .bind("APPROVED")
        .bind(true)
        .execute(pool)
        .await?;
        tracing::info!("Default admin user created");
    }

    tracing::info!("Database migrations completed");
    Ok(())
}
