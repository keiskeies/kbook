use std::sync::Arc;
use tokio::sync::Mutex;
use tauri::Manager;

pub mod routes;
pub mod tasks;

pub struct AppState {
    pub config: Arc<Mutex<kbook_config::AppConfig>>,
    pub ai_config: Arc<Mutex<kbook_config::AiConfig>>,
    pub db: Arc<kbook_db::Database>,
    pub ai_chat: Arc<kbook_ai::chat::AiChatService>,
    pub book_chat: Arc<kbook_ai::book_chat::BookChatService>,
    pub debate: Arc<kbook_ai::debate::DebateService>,
    pub round_table: Arc<kbook_ai::round_table::RoundTableService>,
    pub recommend: Arc<kbook_ai::recommend::RecommendService>,
    pub model_factory: Arc<kbook_ai::llm::ChatModelFactory>,
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tracing_subscriber::fmt::init();

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .setup(|app| {
            let app_dir = app
                .path()
                .app_data_dir()
                .expect("failed to get app data dir");
            std::fs::create_dir_all(&app_dir).ok();

            let config = kbook_config::AppConfig::load(&app_dir)
                .expect("failed to load config");
            let ai_config = kbook_config::load_ai_config(&app_dir)
                .expect("failed to load ai config");

            let db_path = app_dir.join(&config.database.path);
            let db = tauri::async_runtime::block_on(async {
                let db = kbook_db::Database::new(&db_path).await?;
                db.init().await?;
                Ok::<_, anyhow::Error>(db)
            })
            .expect("failed to init database");

            // Load ChatModelFactory from DB (multi-provider support)
            let model_factory = tauri::async_runtime::block_on(async {
                kbook_ai::llm::ChatModelFactory::load_from_db(&db.pool).await
            })
            .expect("failed to load ChatModelFactory");

            let (llm_base_url, model, model_for_book_chat, model_api_key) = tauri::async_runtime::block_on(async {
                let default_config = sqlx::query_as::<_, kbook_core::entity::AiProviderConfig>(
                    "SELECT * FROM ai_provider_config WHERE purpose = 'CHAT' AND enabled = 1 ORDER BY id LIMIT 1"
                ).fetch_optional(&db.pool).await.unwrap_or(None);
                let qa_config = sqlx::query_as::<_, kbook_core::entity::AiProviderConfig>(
                    "SELECT * FROM ai_provider_config WHERE purpose = 'CHAT' AND roles LIKE '%QA%' AND enabled = 1 LIMIT 1"
                ).fetch_optional(&db.pool).await.unwrap_or(None);
                let tool_config = sqlx::query_as::<_, kbook_core::entity::AiProviderConfig>(
                    "SELECT * FROM ai_provider_config WHERE purpose = 'CHAT' AND roles LIKE '%TOOL%' AND enabled = 1 LIMIT 1"
                ).fetch_optional(&db.pool).await.unwrap_or(None);

                let base_url_raw = qa_config.as_ref().or(tool_config.as_ref())
                    .and_then(|c| c.base_url.clone())
                    .or_else(|| default_config.as_ref().and_then(|c| c.base_url.clone()))
                    .unwrap_or_else(|| "http://localhost:11434".to_string());
                let base = if base_url_raw.ends_with("/v1") || base_url_raw.ends_with("/v1/") { base_url_raw }
                    else if base_url_raw.ends_with("/") { format!("{}v1", base_url_raw) }
                    else { format!("{}/v1", base_url_raw) };

                let _api_key = qa_config.as_ref().or(tool_config.as_ref())
                    .and_then(|c| c.api_key.clone())
                    .or_else(|| default_config.as_ref().and_then(|c| c.api_key.clone()));

                let m = qa_config.as_ref().or(default_config.as_ref())
                    .and_then(|c| c.model_name.clone())
                    .unwrap_or_else(|| "gemma4:e2b".to_string());
                let m2 = tool_config.as_ref().or(qa_config.as_ref()).or(default_config.as_ref())
                    .and_then(|c| c.model_name.clone())
                    .unwrap_or_else(|| "gemma4:e2b".to_string());
                let ak = default_config.as_ref().and_then(|c| c.api_key.clone());
                (base, m, m2, ak)
            });
            let llm = kbook_ai::llm::LlmClient::new(&llm_base_url, model_api_key.as_deref());
            tracing::info!("LLM base_url: {}, model (QA): {}, (BookChat/Tool): {}", llm_base_url, model, model_for_book_chat);

            let ai_chat = Arc::new(kbook_ai::chat::AiChatService::new(llm.clone(), model.clone()));
            let book_chat = Arc::new(kbook_ai::book_chat::BookChatService::new(llm.clone(), model.clone()));
            let debate = Arc::new(kbook_ai::debate::DebateService::new(llm.clone(), model.clone()));
            let round_table = Arc::new(kbook_ai::round_table::RoundTableService::new(llm.clone(), model.clone()));
            let recommend = Arc::new(kbook_ai::recommend::RecommendService::new());

            {
                let (means, stddevs) = crate::tasks::dimension_stats::load_stats();
                recommend.set_stats(means, stddevs);
            }

            let state = AppState {
                config: Arc::new(Mutex::new(config)),
                ai_config: Arc::new(Mutex::new(ai_config)),
                db: Arc::new(db),
                ai_chat,
                book_chat,
                debate,
                round_table,
                recommend,
                model_factory: Arc::new(model_factory),
            };

            let db_clone = state.db.clone();
            let ai_chat_clone = state.ai_chat.clone();
            let book_chat_clone = state.book_chat.clone();
            let debate_clone = state.debate.clone();
            let round_table_clone = state.round_table.clone();
            let recommend_clone = state.recommend.clone();

            let port = state.config.blocking_lock().server.port;

            let router = routes::create_router(
                db_clone, ai_chat_clone, book_chat_clone,
                debate_clone, round_table_clone, recommend_clone,
            );

            tauri::async_runtime::spawn(async move {
                let addr = format!("127.0.0.1:{}", port);
                tracing::info!("Starting HTTP server on {}", addr);
                let listener = tokio::net::TcpListener::bind(&addr).await.unwrap();
                axum::serve(listener, router).await.unwrap();
            });

            let db_for_tasks = state.db.clone();
            app.manage(state);

            tasks::start_scheduled_tasks(db_for_tasks);

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
