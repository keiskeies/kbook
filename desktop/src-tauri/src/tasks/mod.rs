pub mod dimension_stats;
pub mod hot_rank;
pub mod coefficient_tune;

use std::sync::Arc;
use kbook_db::Database;

pub fn start_scheduled_tasks(db: Arc<Database>) {
    let db1 = db.clone();
    tauri::async_runtime::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(3600));
        interval.tick().await;
        loop {
            tracing::info!("[SCHEDULED] Refreshing dimension stats...");
            if let Err(e) = dimension_stats::refresh(&db1).await {
                tracing::error!("[SCHEDULED] dimension_stats failed: {e}");
            }
            interval.tick().await;
        }
    });

    let db2 = db.clone();
    tauri::async_runtime::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(7200));
        interval.tick().await;
        loop {
            tracing::info!("[SCHEDULED] Refreshing hot rank + tags...");
            if let Err(e) = hot_rank::refresh(&db2).await {
                tracing::error!("[SCHEDULED] hot_rank failed: {e}");
            }
            interval.tick().await;
        }
    });

    let db3 = db.clone();
    tauri::async_runtime::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(3600));
        interval.tick().await;
        loop {
            tracing::info!("[SCHEDULED] Auto-tuning recommendation coefficients...");
            if let Err(e) = coefficient_tune::auto_tune(&db3).await {
                tracing::error!("[SCHEDULED] coefficient_tune failed: {e}");
            }
            interval.tick().await;
        }
    });

    tracing::info!("Scheduled tasks started (stats: 1h, hot_rank: 2h, tune: 1h)");
}
