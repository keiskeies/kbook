use std::path::PathBuf;

pub fn app_data_dir() -> PathBuf {
    dirs::data_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("kbook")
}

pub fn default_db_path(app_dir: &std::path::Path) -> PathBuf {
    app_dir.join("kbook.db")
}

pub fn default_vector_path(app_dir: &std::path::Path) -> PathBuf {
    app_dir.join("data").join("vectors")
}

pub fn default_books_dir(app_dir: &std::path::Path) -> PathBuf {
    app_dir.join("data").join("books")
}

pub fn default_covers_dir(app_dir: &std::path::Path) -> PathBuf {
    app_dir.join("data").join("covers")
}

pub fn default_avatar_dir(app_dir: &std::path::Path) -> PathBuf {
    app_dir.join("data").join("avatars")
}

pub fn default_tts_cache_dir(app_dir: &std::path::Path) -> PathBuf {
    app_dir.join("data").join("tts_cache")
}
