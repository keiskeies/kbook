use crate::llm::{LlmClient, ChatMessage, StreamChunk};
use crate::prompt;
use kbook_core::entity::{Book, RoundTableMessage};
use kbook_db::repository::round_table_repo;
use sqlx::SqlitePool;
use tokio::sync::mpsc;
use std::collections::HashMap;
use std::path::PathBuf;

pub struct RoundTableService {
    pub llm: LlmClient,
    pub model: String,
}

impl RoundTableService {
    pub fn new(llm: LlmClient, model: String) -> Self {
        Self { llm, model }
    }

    // ==================== 角色推荐 ====================

    /// 根据使用次数或 LLM 推荐角色列表
    /// 优先查本地 JSON 文件中各角色的历史使用次数，取 top 4-6 标记为 selected。
    /// 数据不足 4 个时降级到 LLM（冷启动），LLM 结果也写入文件 score+1。
    /// refresh=true 时强制走 LLM，结果同样递增。
    pub async fn get_recommended_roles(
        &self,
        pool: &SqlitePool,
        book_id: i64,
        refresh: bool,
    ) -> anyhow::Result<Vec<RoleRecommendation>> {
        let book = self.load_book(pool, book_id).await?;
        let book = match book {
            Some(b) => b,
            None => return Ok(self.default_roles()),
        };

        let score_path = self.role_scores_path(book_id);

        // 非刷新时优先从文件取 top 角色
        if !refresh {
            if let Ok(scores) = self.read_role_scores(&score_path) {
                let top_roles: Vec<RoleRecommendation> = scores.iter()
                    .filter(|(k, _)| *k != "HOST")
                    .take(6)
                    .map(|(k, s)| RoleRecommendation {
                        key: k.clone(),
                        selected: true,
                        usage_count: *s,
                        domain_relevance: 0,
                        language_style: String::new(),
                    })
                    .collect();

                if top_roles.len() >= 4 {
                    tracing::debug!("文件角色推荐命中: bookId={}", book_id);
                    return Ok(top_roles);
                }
            }
        }

        // 冷启动 / 强制刷新 → LLM
        let book_info = self.build_book_info_for_role_selection(&book);
        let messages = vec![
            ChatMessage::system(prompt::ROUND_TABLE_ROLE_SELECTION_SYSTEM_PROMPT),
            ChatMessage::user(&book_info),
        ];

        match self.llm.chat(&self.model, &messages, None).await {
            Ok(response) => {
                let content = response.choices.first()
                    .and_then(|c| c.message.content.clone())
                    .unwrap_or_default();
                let content = strip_code_fence(&content);

                if let Ok(roles) = serde_json::from_str::<Vec<LlmRoleSelection>>(&content) {
                    if roles.len() >= 3 {
                        // 记录 LLM 选择到文件
                        let mut scores = self.read_role_scores(&score_path).unwrap_or_default();
                        for role in &roles {
                            *scores.entry(role.key.clone()).or_insert(0) += 1;
                        }
                        let _ = self.write_role_scores(&score_path, &scores);

                        let result: Vec<RoleRecommendation> = roles.iter()
                            .filter(|r| r.key != "HOST")
                            .take(6)
                            .map(|r| RoleRecommendation {
                                key: r.key.clone(),
                                selected: true,
                                usage_count: scores.get(&r.key).copied().unwrap_or(0),
                                domain_relevance: r.domain_relevance,
                                language_style: r.language_style.clone(),
                            })
                            .collect();

                        if result.len() >= 3 {
                            return Ok(result);
                        }
                    }
                }
            }
            Err(e) => {
                tracing::warn!("LLM 角色推荐失败，回退到默认: bookId={} - {}", book_id, e);
            }
        }

        // 回退到默认角色
        Ok(self.default_roles())
    }

    // ==================== 单角色发言 ====================

    /// 嘉宾发言 — 使用 ROUND_TABLE_CHARACTER_PROMPT
    pub async fn stream_speech(
        &self,
        _pool: &SqlitePool,
        _session_id: &str,
        role_key: &str,
        role_name: &str,
        context: &str,
        system_prompt: &str,
    ) -> anyhow::Result<mpsc::Receiver<anyhow::Result<String>>> {
        let is_host = role_key == "HOST";
        let sys_prompt = if system_prompt.is_empty() {
            if is_host {
                prompt::ROUND_TABLE_HOST_PROMPT
            } else {
                prompt::ROUND_TABLE_CHARACTER_PROMPT
            }
        } else {
            system_prompt
        };

        let messages = vec![
            ChatMessage::system(sys_prompt),
            ChatMessage::user(&format!(
                "讨论话题和上下文：\n{}\n\n请以{}的身份发表你的看法。",
                context, role_name
            )),
        ];

        self.stream_speech_internal(messages).await
    }

    /// 嘉宾发言 — 使用 ROUND_TABLE_CHARACTER_PROMPT + ROUND_TABLE_ROLE_SETTING_GUEST
    pub async fn stream_guest_speech(
        &self,
        pool: &SqlitePool,
        session_id: &str,
        _role_key: &str,
        role_name: &str,
        role_prompt: &str,
        catchphrase: &str,
        language_style: &str,
        challenge: i32,
        empathy: i32,
        opinionated: i32,
        verbosity: i32,
        humor: i32,
        domain_relevance: i32,
        context: &str,
    ) -> anyhow::Result<mpsc::Receiver<anyhow::Result<String>>> {
        let system_prompt = prompt::ROUND_TABLE_CHARACTER_PROMPT;

        let catchphrase_or_default = if catchphrase.is_empty() { "用你自己的方式表达，保持自然" } else { catchphrase };
        let style_or_default = if language_style.is_empty() { "自然流畅，符合你的专业身份" } else { language_style };

        let role_setting = prompt::ROUND_TABLE_ROLE_SETTING_GUEST
            .replacen("%s", role_prompt, 1)
            .replacen("%s", catchphrase_or_default, 1)
            .replacen("%s", style_or_default, 1)
            .replacen("%d", &challenge.to_string(), 1)
            .replacen("%s", describe_challenge(challenge), 1)
            .replacen("%d", &empathy.to_string(), 1)
            .replacen("%s", describe_empathy(empathy), 1)
            .replacen("%d", &opinionated.to_string(), 1)
            .replacen("%s", describe_opinionated(opinionated), 1)
            .replacen("%d", &verbosity.to_string(), 1)
            .replacen("%s", describe_verbosity(verbosity), 1)
            .replacen("%d", &humor.to_string(), 1)
            .replacen("%s", describe_humor(humor), 1)
            .replacen("%d", &domain_relevance.to_string(), 1)
            .replacen("%s", describe_domain_relevance(domain_relevance), 1);

        let speak_instruction = format!(
            "请以{}的身份发言。直接接话，绝对不要以「刚才大家...」「前面几位...」「听了各位...」开头，直接说你的观点。",
            role_name
        );

        let mut messages = vec![
            ChatMessage::system(system_prompt),
        ];

        // 加载历史
        let history = round_table_repo::get_messages(pool, session_id).await.unwrap_or_default();
        if !history.is_empty() {
            let mut history_builder = String::from("【之前的讨论内容】\n");
            for msg in &history {
                let name = msg.role_name.as_deref().unwrap_or("未知");
                let content = msg.compressed_content.as_deref()
                    .or(msg.content.as_deref())
                    .unwrap_or("");
                history_builder.push_str(&format!("{}：{}\n\n", name, content));
            }
            messages.push(ChatMessage::user(&history_builder));
        }

        if !context.is_empty() {
            messages.push(ChatMessage::user(&format!("【讨论上下文】\n{}", context)));
        }

        messages.push(ChatMessage::user(&role_setting));
        messages.push(ChatMessage::user(&speak_instruction));

        self.stream_speech_internal(messages).await
    }

    /// 主持人发言 — 使用 ROUND_TABLE_HOST_PROMPT + ROUND_TABLE_ROLE_SETTING_HOST
    pub async fn stream_host_speech(
        &self,
        pool: &SqlitePool,
        session_id: &str,
        language_style: &str,
        challenge: i32,
        empathy: i32,
        opinionated: i32,
        verbosity: i32,
        context: &str,
    ) -> anyhow::Result<mpsc::Receiver<anyhow::Result<String>>> {
        let system_prompt = prompt::ROUND_TABLE_HOST_PROMPT;

        let style_or_default = if language_style.is_empty() { "沉稳大方，善于引导和总结" } else { language_style };

        let role_setting = prompt::ROUND_TABLE_ROLE_SETTING_HOST
            .replacen("%s", style_or_default, 1)
            .replacen("%d", &challenge.to_string(), 1)
            .replacen("%s", describe_challenge(challenge), 1)
            .replacen("%d", &empathy.to_string(), 1)
            .replacen("%s", describe_empathy(empathy), 1)
            .replacen("%d", &opinionated.to_string(), 1)
            .replacen("%s", describe_opinionated(opinionated), 1)
            .replacen("%d", &verbosity.to_string(), 1)
            .replacen("%s", describe_verbosity(verbosity), 1);

        let speak_instruction = "请以主持人的身份发言。直接说你的观点或抛出问题，绝对不要以「刚才大家...」「前面几位...」「听了各位...」开头。";

        let mut messages = vec![
            ChatMessage::system(system_prompt),
        ];

        // 加载历史
        let history = round_table_repo::get_messages(pool, session_id).await.unwrap_or_default();
        if !history.is_empty() {
            let mut history_builder = String::from("【之前的讨论内容】\n");
            for msg in &history {
                let name = msg.role_name.as_deref().unwrap_or("未知");
                let content = msg.compressed_content.as_deref()
                    .or(msg.content.as_deref())
                    .unwrap_or("");
                history_builder.push_str(&format!("{}：{}\n\n", name, content));
            }
            messages.push(ChatMessage::user(&history_builder));
        }

        if !context.is_empty() {
            messages.push(ChatMessage::user(&format!("【讨论上下文】\n{}", context)));
        }

        messages.push(ChatMessage::user(&role_setting));
        messages.push(ChatMessage::user(speak_instruction));

        self.stream_speech_internal(messages).await
    }

    // ==================== 下一发言人选择 ====================

    /// LLM 驱动发言人选择
    pub async fn select_next_speaker(
        &self,
        pool: &SqlitePool,
        session_id: &str,
        role_keys: &[&str],
        role_configs: &str,
    ) -> anyhow::Result<String> {
        let all_messages = round_table_repo::get_messages(pool, session_id).await?;
        if all_messages.is_empty() {
            return Ok("HOST".to_string());
        }

        // 统计发言次数
        let mut speak_counts: HashMap<String, u64> = HashMap::new();
        for msg in &all_messages {
            if let Some(key) = &msg.role_key {
                *speak_counts.entry(key.clone()).or_insert(0) += 1;
            }
        }

        let last_speaker = all_messages.last()
            .and_then(|m| m.role_key.clone())
            .unwrap_or_default();

        // 构建角色信息
        let roles_info = self.build_roles_info_for_speaker_selection(role_keys, role_configs, &speak_counts);

        // 构建最近发言记录
        let recent_messages: Vec<&RoundTableMessage> = all_messages.iter().rev().take(5).collect();
        let mut recent_text = String::new();
        for msg in recent_messages.iter().rev() {
            let name = msg.role_name.as_deref().unwrap_or("未知");
            let content = msg.compressed_content.as_deref()
                .or(msg.content.as_deref())
                .unwrap_or("");
            recent_text.push_str(&format!("{}：{}\n", name, content));
        }

        // 构建公平性约束
        let fairness = self.build_fairness_constraints(role_keys, &speak_counts, &all_messages);

        let user_msg = format!(
            "【当前在场角色】\n{}\n\n【最近发言记录】\n{}\n\n{}\n\n只返回JSON：{{\"nextSpeaker\": \"角色KEY\"}}",
            roles_info, recent_text, fairness
        );

        let messages = vec![
            ChatMessage::system(prompt::ROUND_TABLE_NEXT_SPEAKER_SYSTEM),
            ChatMessage::user(&user_msg),
        ];

        // 尝试 LLM 选择
        match self.llm.chat(&self.model, &messages, None).await {
            Ok(response) => {
                let content = response.choices.first()
                    .and_then(|c| c.message.content.clone())
                    .unwrap_or_default();
                let content = strip_code_fence(&content).trim().to_string();

                if let Some(key) = self.parse_speaker_response(&content, role_keys) {
                    // 禁止连续发言
                    if key != last_speaker {
                        tracing::info!("LLM选择发言人: sessionId={}, selected={}", session_id, key);
                        return Ok(key);
                    }
                }
            }
            Err(e) => {
                tracing::warn!("LLM发言人选择失败，回退到简单模式: {}", e);
            }
        }

        // 回退：轮询 + 公平性
        let valid_keys: Vec<&str> = role_keys.iter()
            .map(|k| k.trim())
            .filter(|k| *k != last_speaker)
            .collect();

        let result = valid_keys.iter()
            .min_by_key(|k| speak_counts.get(**k).copied().unwrap_or(0))
            .map(|k| k.to_string())
            .unwrap_or_else(|| "HOST".to_string());

        Ok(result)
    }

    // ==================== 覆盖度评分 ====================

    /// 计算讨论覆盖度
    pub async fn refresh_coverage(
        &self,
        pool: &SqlitePool,
        session_id: &str,
    ) -> anyhow::Result<f64> {
        let session = round_table_repo::find_session(pool, session_id).await?;
        let session = match session {
            Some(s) => s,
            None => return Ok(0.0),
        };

        let book_id = session.book_id;
        let book = self.load_book(pool, book_id).await?;

        // 从书籍概念标签中提取概念
        let concepts = book.as_ref()
            .and_then(|b| b.concept_tags.as_deref())
            .map(|t| parse_concept_tags(t))
            .unwrap_or_default();

        if concepts.is_empty() {
            return Ok(0.0);
        }

        // 加载所有消息
        let messages = round_table_repo::get_messages(pool, session_id).await?;
        if messages.is_empty() {
            return Ok(0.0);
        }

        // 统计已讨论的概念
        let all_text: String = messages.iter()
            .filter_map(|m| m.compressed_content.as_deref().or(m.content.as_deref()))
            .collect();

        let covered_count = concepts.iter()
            .filter(|c| all_text.contains(c.as_str()))
            .count();

        let coverage_score = if concepts.is_empty() {
            0.0
        } else {
            covered_count as f64 / concepts.len() as f64
        };

        // 保存到数据库
        let _ = sqlx::query(
            "INSERT OR REPLACE INTO round_table_coverages (session_id, book_id, overall_score, total_concepts, covered_concepts_count, covered_concepts_json, missed_concepts_json) \
             VALUES (?, ?, ?, ?, ?, ?, ?)"
        )
        .bind(session_id)
        .bind(book_id)
        .bind(coverage_score)
        .bind(concepts.len() as i32)
        .bind(covered_count as i32)
        .bind(serde_json::to_string(&concepts).unwrap_or_default())
        .bind(serde_json::to_string(&concepts.iter().filter(|c| !all_text.contains(c.as_str())).collect::<Vec<_>>()).unwrap_or_default())
        .execute(pool)
        .await;

        Ok(coverage_score)
    }

    // ==================== 报告生成 ====================

    /// 生成圆桌派解读报告
    pub async fn generate_report(
        &self,
        pool: &SqlitePool,
        session_id: &str,
    ) -> anyhow::Result<String> {
        let messages_list = round_table_repo::get_messages(pool, session_id).await?;
        let session = round_table_repo::find_session(pool, session_id).await?;

        let title = session.as_ref()
            .and_then(|s| s.title.as_deref())
            .unwrap_or("圆桌派讨论");

        // 构建讨论全文
        let mut discussion_text = format!("【讨论主题】{}\n\n", title);
        for msg in &messages_list {
            let name = msg.role_name.as_deref().unwrap_or("未知");
            let content = msg.compressed_content.as_deref()
                .or(msg.content.as_deref())
                .unwrap_or("");
            let round = msg.round.map(|r| r.to_string()).unwrap_or_default();
            discussion_text.push_str(&format!("{}（第{}轮）：{}\n\n", name, round, content));
        }

        let messages = vec![
            ChatMessage::system(prompt::ROUND_TABLE_REPORT_SYSTEM_PROMPT),
            ChatMessage::user(&discussion_text),
        ];

        let response = self.llm.chat(&self.model, &messages, None).await?;
        let content = response.choices.first()
            .and_then(|c| c.message.content.clone())
            .unwrap_or_default();

        // 保存报告
        let _ = round_table_repo::insert_report(pool, session_id, &content).await;

        Ok(content)
    }

    // ==================== 内部辅助方法 ====================

    /// 内部流式发言实现
    async fn stream_speech_internal(
        &self,
        messages: Vec<ChatMessage>,
    ) -> anyhow::Result<mpsc::Receiver<anyhow::Result<String>>> {
        let (tx, rx) = mpsc::channel(200);
        let llm = self.llm.clone();
        let model = self.model.clone();

        tokio::spawn(async move {
            match llm.stream_chat(&model, &messages, None).await {
                Ok(mut stream) => {
                    while let Some(chunk) = stream.recv().await {
                        match chunk {
                            Ok(StreamChunk { choices }) if choices.is_empty() => break,
                            Ok(StreamChunk { choices }) => {
                                for choice in choices {
                                    if let Some(content) = &choice.delta.content {
                                        let _ = tx.send(Ok(content.clone())).await;
                                    }
                                }
                            }
                            Err(e) => { let _ = tx.send(Err(e)).await; return; }
                        }
                    }
                }
                Err(e) => { let _ = tx.send(Err(e)).await; }
            }
        });

        Ok(rx)
    }

    /// 构建角色信息（用于发言人选择）
    fn build_roles_info_for_speaker_selection(
        &self,
        role_keys: &[&str],
        role_configs: &str,
        speak_counts: &HashMap<String, u64>,
    ) -> String {
        let mut sb = String::new();

        // 解析 roleConfigs JSON
        let config_map: HashMap<String, LlmRoleSelection> = if !role_configs.is_empty() {
            serde_json::from_str::<Vec<LlmRoleSelection>>(role_configs)
                .unwrap_or_default()
                .into_iter()
                .map(|r| (r.key.clone(), r))
                .collect()
        } else {
            HashMap::new()
        };

        for key in role_keys {
            let key = key.trim();
            if key.is_empty() { continue; }

            let count = speak_counts.get(key).copied().unwrap_or(0);
            let config = config_map.get(key);

            sb.push_str(&format!(
                "{} - 发言{}次 {}{}\n",
                key,
                count,
                config.map(|c| format!("专业相关度={}", c.domain_relevance)).unwrap_or_default(),
                config.map(|c| if c.language_style.is_empty() { String::new() } else { format!(" 语言风格={}", c.language_style) }).unwrap_or_default(),
            ));
        }

        sb
    }

    /// 构建公平性约束
    fn build_fairness_constraints(
        &self,
        role_keys: &[&str],
        speak_counts: &HashMap<String, u64>,
        all_messages: &[RoundTableMessage],
    ) -> String {
        let mut sb = String::new();

        // 禁止连续发言
        if let Some(last_msg) = all_messages.last() {
            if let Some(last_key) = &last_msg.role_key {
                sb.push_str(&format!("- 绝对禁止：{}刚发过言，不能连续发言\n", last_key));
            }
        }

        // 从未发言的角色（强制优先）
        let never_spoken: Vec<&str> = role_keys.iter()
            .map(|k| k.trim())
            .filter(|k| !speak_counts.contains_key(*k) && *k != "HOST")
            .collect();

        if !never_spoken.is_empty() {
            sb.push_str("- 【强制优先】以下角色至今一次都没发言，必须在下一轮选其中一位：");
            for key in &never_spoken {
                sb.push_str(&format!("{} ", key));
            }
            sb.push('\n');
        }

        // 发言次数统计
        sb.push_str("- 当前发言次数统计（从少到多）：");
        let mut sorted_keys: Vec<&&str> = role_keys.iter().collect();
        sorted_keys.sort_by_key(|k| speak_counts.get(**k).copied().unwrap_or(0));
        for key in sorted_keys {
            let count = speak_counts.get(*key).copied().unwrap_or(0);
            sb.push_str(&format!("{}={} ", key, count));
        }
        sb.push('\n');

        sb
    }

    /// 解析 LLM 返回的发言人选择结果
    fn parse_speaker_response(&self, response: &str, valid_keys: &[&str]) -> Option<String> {
        let valid_set: std::collections::HashSet<&str> = valid_keys.iter().map(|k| k.trim()).collect();

        // 尝试解析 JSON {"nextSpeaker": "KEY"}
        if let Ok(node) = serde_json::from_str::<serde_json::Value>(response) {
            if let Some(key) = node.get("nextSpeaker").and_then(|v| v.as_str()) {
                if valid_set.contains(key.trim()) {
                    return Some(key.trim().to_string());
                }
            }
        }

        // 尝试直接匹配角色 key
        let upper_response = response.to_uppercase();
        for key in valid_keys {
            if upper_response.contains(key.trim().to_uppercase().as_str()) {
                return Some(key.trim().to_string());
            }
        }

        None
    }

    /// 加载书籍
    async fn load_book(&self, pool: &SqlitePool, book_id: i64) -> anyhow::Result<Option<Book>> {
        let book = sqlx::query_as::<_, Book>("SELECT * FROM books WHERE id = ?")
            .bind(book_id)
            .fetch_optional(pool)
            .await?;
        Ok(book)
    }

    /// 构建角色推荐用的书籍信息
    fn build_book_info_for_role_selection(&self, book: &Book) -> String {
        let mut sb = String::new();
        sb.push_str(&format!("书名：《{}》\n", book.title));
        if let Some(author) = &book.author {
            if !author.is_empty() {
                sb.push_str(&format!("作者：{}\n", author));
            }
        }
        if let Some(tags) = &book.format_tags {
            if !tags.is_empty() {
                let cleaned = clean_tags(tags);
                sb.push_str(&format!("标签：{}\n", cleaned));
            }
        }
        if let Some(tags) = &book.concept_tags {
            if !tags.is_empty() {
                let cleaned = clean_tags(tags);
                sb.push_str(&format!("核心概念：{}\n", cleaned));
            }
        }
        if let Some(tags) = &book.reader_need_tags {
            if !tags.is_empty() {
                let cleaned = clean_tags(tags);
                sb.push_str(&format!("读者关注：{}\n", cleaned));
            }
        }
        if let Some(desc) = &book.description {
            if !desc.is_empty() {
                let desc = if desc.len() > 500 {
                    format!("{}...", &desc[..500])
                } else {
                    desc.clone()
                };
                sb.push_str(&format!("简介：{}\n", desc));
            }
        }
        if let Some(summary) = &book.compressed_summary {
            if !summary.is_empty() {
                let truncated = if summary.len() > 2000 {
                    format!("{}...", &summary[..2000])
                } else {
                    summary.clone()
                };
                sb.push_str(&format!("摘要：{}\n", truncated));
            }
        } else if let Some(summary) = &book.chapter_summary {
            if !summary.is_empty() {
                let truncated = if summary.len() > 2000 {
                    format!("{}...", &summary[..2000])
                } else {
                    summary.clone()
                };
                sb.push_str(&format!("摘要：{}\n", truncated));
            }
        }
        sb
    }

    fn role_scores_path(&self, book_id: i64) -> PathBuf {
        let dir = std::env::temp_dir().join("kbook").join("role_scores");
        dir.join(format!("role_scores_{}.json", book_id))
    }

    fn read_role_scores(&self, path: &PathBuf) -> anyhow::Result<HashMap<String, u64>> {
        let content = std::fs::read_to_string(path)?;
        let scores: HashMap<String, u64> = serde_json::from_str(&content)?;
        Ok(scores)
    }

    fn write_role_scores(&self, path: &PathBuf, scores: &HashMap<String, u64>) -> anyhow::Result<()> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let content = serde_json::to_string(scores)?;
        std::fs::write(path, content)?;
        Ok(())
    }

    fn default_roles(&self) -> Vec<RoleRecommendation> {
        vec![
            RoleRecommendation { key: "HOST".into(), selected: true, usage_count: 0, domain_relevance: 0, language_style: String::new() },
            RoleRecommendation { key: "PHILOSOPHER".into(), selected: true, usage_count: 0, domain_relevance: 7, language_style: String::new() },
            RoleRecommendation { key: "PSYCHOLOGIST".into(), selected: true, usage_count: 0, domain_relevance: 7, language_style: String::new() },
            RoleRecommendation { key: "SOCIOLOGIST".into(), selected: true, usage_count: 0, domain_relevance: 6, language_style: String::new() },
            RoleRecommendation { key: "EDUCATOR".into(), selected: true, usage_count: 0, domain_relevance: 5, language_style: String::new() },
            RoleRecommendation { key: "COMEDIAN".into(), selected: true, usage_count: 0, domain_relevance: 4, language_style: String::new() },
        ]
    }
}

// ==================== 数据结构 ====================

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct RoleRecommendation {
    pub key: String,
    pub selected: bool,
    #[serde(default)]
    pub usage_count: u64,
    #[serde(default)]
    pub domain_relevance: i32,
    #[serde(default)]
    pub language_style: String,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct LlmRoleSelection {
    key: String,
    #[serde(default)]
    domain_relevance: i32,
    #[serde(default)]
    language_style: String,
}

// ==================== 性格描述函数 ====================

fn describe_challenge(v: i32) -> &'static str {
    if v >= 4 { "喜欢质疑和反驳" }
    else if v >= 3 { "适度挑战" }
    else { "较少质疑" }
}

fn describe_empathy(v: i32) -> &'static str {
    if v >= 4 { "善于理解和共鸣" }
    else if v >= 3 { "适度共情" }
    else { "理性优先" }
}

fn describe_opinionated(v: i32) -> &'static str {
    if v >= 4 { "立场坚定" }
    else if v >= 3 { "有一定主见" }
    else { "立场灵活" }
}

fn describe_verbosity(v: i32) -> &'static str {
    if v >= 4 { "话多" }
    else if v >= 3 { "话量适中" }
    else { "话少精炼" }
}

fn describe_humor(v: i32) -> &'static str {
    if v >= 4 { "善于调侃和活跃气氛" }
    else if v >= 3 { "适度幽默" }
    else { "严肃认真" }
}

fn describe_domain_relevance(v: i32) -> &'static str {
    if v >= 7 { "这是你的专业主场，应该自信发言" }
    else if v >= 4 { "与你的领域有一定关联" }
    else { "超出你的专业领域，但可以从你的视角提供独特见解" }
}

// ==================== 工具函数 ====================

fn clean_tags(tags: &str) -> String {
    tags.replace('[', "")
        .replace(']', "")
        .replace('"', "")
        .replace(',', "、")
}

fn strip_code_fence(s: &str) -> String {
    let s = s.trim();
    if s.starts_with("```json") {
        let s = s.strip_prefix("```json").unwrap_or(s);
        if let Some(s) = s.strip_suffix("```") {
            return s.trim().to_string();
        }
    } else if s.starts_with("```") {
        let s = s.strip_prefix("```").unwrap_or(s);
        if let Some(s) = s.strip_suffix("```") {
            return s.trim().to_string();
        }
    }
    s.to_string()
}

fn parse_concept_tags(tags: &str) -> Vec<String> {
    tags.replace('[', "")
        .replace(']', "")
        .replace('"', "")
        .split(',')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect()
}
