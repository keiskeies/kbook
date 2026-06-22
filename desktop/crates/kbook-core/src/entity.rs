use serde::{Deserialize, Serialize};

fn default_role() -> String { "USER".into() }
fn default_status() -> String { "PENDING".into() }
fn default_book_chat_style() -> Option<String> { Some("DEEP".into()) }

macro_rules! camel_case {
    ($($item:item)*) => {
        $(
            #[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
            #[serde(rename_all = "camelCase")]
            $item
        )*
    };
}

camel_case! {
    pub struct User {
        pub id: i64,
        pub email: String,
        #[serde(skip_serializing)]
        pub password: Option<String>,
        pub nickname: Option<String>,
        pub avatar: Option<String>,
        #[serde(default = "default_role")]
        pub role: String,
        #[serde(default = "default_status")]
        pub status: String,
        #[serde(default)]
        pub email_bound: Option<bool>,
        pub birthday: Option<String>,
        pub gender: Option<String>,
        pub is_married: Option<bool>,
        pub has_children: Option<bool>,
        pub children_age_ranges: Option<String>,
        pub mbti: Option<String>,
        pub occupation: Option<String>,
        pub education: Option<String>,
        pub entrepreneurship: Option<String>,
        pub annual_income: Option<String>,
        pub mood: Option<String>,
        #[serde(default = "default_book_chat_style")]
        pub book_chat_style: Option<String>,
        pub bio: Option<String>,
        #[serde(default)]
        pub follower_count: Option<i32>,
        #[serde(default)]
        pub following_count: Option<i32>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
    }

    pub struct Book {
        pub id: i64,
        pub title: String,
        pub author: Option<String>,
        pub cover_url: Option<String>,
        pub description: Option<String>,
        pub format: Option<String>,
        pub file_url: Option<String>,
        pub file_size: Option<i64>,
        pub format_tags: Option<String>,
        pub concept_tags: Option<String>,
        pub reader_need_tags: Option<String>,
        pub target_reader_tags: Option<String>,
        pub toc: Option<String>,
        pub chapter_summary: Option<String>,
        pub compressed_summary: Option<String>,
        pub relevance_scores: Option<String>,
        pub total_units: Option<i64>,
        #[serde(default)]
        pub read_count: Option<i64>,
        #[serde(default)]
        pub rating: Option<f64>,
        #[serde(default)]
        pub rating_count: Option<i64>,
        #[serde(default)]
        pub dimension_rating_count: Option<i32>,
        #[serde(default)]
        pub content_embedded: Option<bool>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
    }

    pub struct AiSession {
        pub id: i64,
        pub user_id: i64,
        #[serde(rename = "type")]
        pub session_type: Option<String>,
        pub book_id: Option<i64>,
        pub session_id: String,
        pub title: Option<String>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
    }

    pub struct AiConversation {
        pub id: i64,
        pub user_id: i64,
        pub session_id: String,
        #[serde(rename = "type")]
        pub conv_type: Option<String>,
        pub book_id: Option<i64>,
        pub role: Option<String>,
        pub content: Option<String>,
        pub compressed_content: Option<String>,
        pub thinking_content: Option<String>,
        pub follow_up_questions: Option<String>,
        pub tool_call_id: Option<String>,
        pub tool_name: Option<String>,
        pub token_count: Option<i32>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
    }

    pub struct ReadingProgress {
        pub id: i64,
        pub user_id: i64,
        pub book_id: i64,
        pub progress: Option<f64>,
        pub current_position: Option<String>,
        pub updated_at: Option<String>,
        pub user_rating: Option<i32>,
        pub created_at: Option<String>,
    }

    pub struct Comment {
        pub id: i64,
        pub user_id: i64,
        pub book_id: i64,
        pub chapter_id: Option<String>,
        pub parent_id: Option<i64>,
        pub content: Option<String>,
        pub like_count: Option<i32>,
        pub reply_count: Option<i32>,
        pub favorite_count: Option<i32>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
    }

    pub struct Bookshelf {
        pub id: i64,
        pub user_id: i64,
        pub book_id: i64,
        pub added_at: Option<String>,
        pub sort_order: Option<i32>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
    }

    pub struct BookTrash {
        pub id: i64,
        pub user_id: i64,
        pub book_id: i64,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
    }

    pub struct Notification {
        pub id: i64,
        pub receiver_id: i64,
        #[serde(rename = "type")]
        pub notification_type: Option<String>,
        pub title: Option<String>,
        pub content: Option<String>,
        pub related_id: Option<String>,
        pub is_read: Option<bool>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
        pub book_id: Option<i64>,
        pub comment_id: Option<i64>,
        pub trigger_user_id: Option<i64>,
        pub session_id: Option<String>,
    }

    pub struct UploadedFile {
        pub id: i64,
        pub uploader_id: i64,
        pub original_filename: Option<String>,
        pub filename: Option<String>,
        pub file_path: Option<String>,
        pub file_size: Option<i64>,
        pub content_type: Option<String>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
    }

    pub struct UserFollow {
        pub id: i64,
        pub follower_id: i64,
        pub following_id: i64,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
    }

    pub struct UserBookPreference {
        pub id: i64,
        pub user_id: i64,
        pub book_id: i64,
        #[serde(rename = "type")]
        pub pref_type: Option<String>,
        pub category: Option<String>,
        pub value: Option<String>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
    }

    pub struct UserReadHistory {
        pub id: i64,
        pub user_id: i64,
        pub book_id: i64,
        pub action: Option<String>,
        pub action_detail: Option<String>,
        pub weight: Option<f64>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
    }

    pub struct TtsConfig {
        pub id: i64,
        pub name: Option<String>,
        #[serde(rename = "ttsType")]
        pub tts_type: Option<String>,
        pub provider: Option<String>,
        pub base_url: Option<String>,
        pub api_key: Option<String>,
        pub api_secret: Option<String>,
        pub voice: Option<String>,
        pub enabled: Option<bool>,
        pub is_default: Option<bool>,
        pub pitch: Option<i32>,
        pub speed: Option<i32>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
        pub app_id: Option<String>,
        pub language: Option<String>,
        pub model_name: Option<String>,
        pub streaming: Option<bool>,
        pub voice_preset_id: Option<String>,
    }

    pub struct AiProviderConfig {
        pub id: i64,
        pub provider: Option<String>,
        pub name: Option<String>,
        pub purpose: Option<String>,
        pub base_url: Option<String>,
        pub api_key: Option<String>,
        pub model_name: Option<String>,
        pub enabled: Option<bool>,
        pub max_tokens: Option<i32>,
        pub temperature: Option<f64>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
        pub timeout: Option<i32>,
        pub tools_enabled: Option<bool>,
        pub is_default: Option<bool>,
        pub rag_topk: Option<i32>,
        pub compression_threshold_chars: Option<i32>,
        pub embedding_dimension: Option<i32>,
        pub roles: Option<String>,
    }

    pub struct DebateSession {
        pub id: i64,
        pub user_id: i64,
        pub book_id: i64,
        pub session_id: String,
        pub topic: String,
        pub topic_source: Option<String>,
        pub pro_role_keys: Option<String>,
        pub con_role_keys: Option<String>,
        pub current_phase: Option<String>,
        pub current_round: Option<i32>,
        pub status: String,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
        pub book_context: Option<String>,
        pub visibility: Option<String>,
    }

    pub struct DebateMessage {
        pub id: i64,
        pub session_id: String,
        pub role_key: Option<String>,
        pub role_name: Option<String>,
        pub side: Option<String>,
        pub round_type: Option<String>,
        pub round_number: Option<i32>,
        pub content: Option<String>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
        pub book_id: Option<i64>,
        pub user_id: Option<i64>,
        pub phase_order: Option<i32>,
        pub position_key: Option<String>,
        pub exam_role: Option<String>,
    }

    pub struct DebateScore {
        pub id: i64,
        pub session_id: String,
        pub role_key: Option<String>,
        pub round_number: Option<i32>,
        pub round_type: Option<String>,
        pub side: Option<String>,
        pub message_id: Option<i64>,
        pub user_id: Option<i64>,
        pub position_key: Option<String>,
        pub average_score: Option<f64>,
        pub clarity_score: Option<f64>,
        pub evidence_score: Option<f64>,
        pub humor_score: Option<f64>,
        pub impact_score: Option<f64>,
        pub logic_score: Option<f64>,
        pub novelty_score: Option<f64>,
        pub rebuttal_score: Option<f64>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
    }

    pub struct DebateReport {
        pub id: i64,
        pub session_id: String,
        pub content: Option<String>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
        pub book_id: Option<i64>,
        pub error_message: Option<String>,
        pub status: Option<String>,
        pub summary_json: Option<String>,
        pub topic: Option<String>,
        pub user_id: Option<i64>,
        pub best_debater: Option<String>,
        pub best_debater_position: Option<String>,
    }

    pub struct RoundTableSession {
        pub id: i64,
        pub user_id: i64,
        pub book_id: i64,
        pub session_id: String,
        pub role_keys: Option<String>,
        pub role_configs: Option<String>,
        pub status: String,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
        pub title: Option<String>,
        pub visibility: Option<String>,
    }

    pub struct RoundTableMessage {
        pub id: i64,
        pub session_id: String,
        pub role_key: Option<String>,
        pub role_name: Option<String>,
        pub content: Option<String>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
        pub book_id: Option<i64>,
        pub compressed_content: Option<String>,
        pub round: Option<i32>,
        pub user_id: Option<i64>,
    }

    pub struct RoundTableCoverage {
        pub id: i64,
        pub session_id: String,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
        pub book_id: Option<i64>,
        pub overall_score: Option<f64>,
        pub blocks_json: Option<String>,
        pub total_blocks: Option<i32>,
        pub covered_blocks: Option<i32>,
        pub total_chunks: Option<i32>,
        pub total_concepts: Option<i32>,
        pub covered_concepts_count: Option<i32>,
        pub covered_concepts_json: Option<String>,
        pub missed_concepts_json: Option<String>,
        pub deep_blocks: Option<i32>,
        pub grade: Option<String>,
        pub block_coverage_score: Option<f64>,
        pub concept_coverage_score: Option<f64>,
        pub block_details_json: Option<String>,
        pub processed_message_count: Option<i32>,
        pub llm_assessment_score: Option<f64>,
        pub llm_dimensions_json: Option<String>,
        pub llm_strengths_json: Option<String>,
        pub llm_weaknesses_json: Option<String>,
        pub llm_suggestions_json: Option<String>,
        pub llm_message_count: Option<i32>,
    }

    pub struct RoundTableReport {
        pub id: i64,
        pub session_id: String,
        pub content: Option<String>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
        pub book_id: Option<i64>,
        pub error_message: Option<String>,
        pub status: Option<String>,
        pub user_id: Option<i64>,
    }

    pub struct RecommendCoefficient {
        pub id: i64,
        pub category: Option<String>,
        pub coeff_key: Option<String>,
        pub coeff_value: Option<f64>,
        pub default_value: Option<f64>,
        pub description: Option<String>,
        pub locked: Option<bool>,
        pub max_value: Option<f64>,
        pub min_value: Option<f64>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
    }

    pub struct RecommendFeedbackEvent {
        pub id: i64,
        pub user_id: i64,
        pub book_id: i64,
        pub feedback_type: Option<String>,
        pub feedback_detail: Option<String>,
        pub quality_factor: Option<f64>,
        pub strength: Option<f64>,
        pub recommend_score: Option<f64>,
        pub recall_paths: Option<String>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
    }

    pub struct BookSuggestedQuestion {
        pub id: i64,
        pub book_id: i64,
        pub question: Option<String>,
        pub created_at: Option<String>,
        pub updated_at: Option<String>,
    }
}
