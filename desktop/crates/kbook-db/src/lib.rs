pub mod repository;
pub mod fts;
pub mod migration;
pub mod hybrid_search;

use sqlx::sqlite::{SqlitePool, SqlitePoolOptions};
use std::path::Path;

#[derive(Clone)]
pub struct Database {
    pub pool: SqlitePool,
}

impl Database {
    pub async fn new(path: &Path) -> anyhow::Result<Self> {
        let path_str = path.to_string_lossy();
        let url = format!("sqlite:{}?mode=rwc", path_str);

        let pool = SqlitePoolOptions::new()
            .max_connections(5)
            .connect(&url)
            .await?;

        Ok(Self { pool })
    }

    pub async fn init(&self) -> anyhow::Result<()> {
        migration::run_migrations(&self.pool).await?;
        fts::init_fts(&self.pool).await?;
        Ok(())
    }
}
