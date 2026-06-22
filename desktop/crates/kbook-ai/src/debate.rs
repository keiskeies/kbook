use crate::llm::{LlmClient, ChatMessage, StreamChunk};
use crate::prompt;
use kbook_core::entity::Book;
use kbook_db::repository::debate_repo;
use sqlx::SqlitePool;
use tokio::sync::mpsc;
use std::collections::HashMap;
use std::path::PathBuf;

pub struct DebateService {
    pub llm: LlmClient,
    pub model: String,
}

impl DebateService {
    pub fn new(llm: LlmClient, model: String) -> Self {
        Self { llm, model }
    }

    // ==================== 辩题生成 ====================

    /// 从书籍内容生成争议辩题（LLM 驱动），结果缓存到本地文件，24h TTL
    pub async fn generate_topics(
        &self,
        pool: &SqlitePool,
        book_id: i64,
        force_refresh: bool,
    ) -> anyhow::Result<Vec<DebateTopic>> {
        let cache_path = self.topic_cache_path(book_id);

        // 非强制刷新时尝试从缓存读取
        if !force_refresh {
            if let Ok(cached) = self.read_topic_cache(&cache_path) {
                if !cached.is_empty() {
                    tracing::debug!("辩题缓存命中: bookId={}", book_id);
                    return Ok(cached);
                }
            }
        } else {
            tracing::info!("强制刷新辩题，跳过缓存: bookId={}", book_id);
        }

        // 加载书籍信息
        let book = self.load_book(pool, book_id).await?;
        let book = match book {
            Some(b) => b,
            None => return Ok(self.fallback_topics()),
        };

        // 调用 LLM 生成辩题
        let book_info = self.build_book_info_for_topic(&book);
        let messages = vec![
            ChatMessage::system(prompt::DEBATE_TOPIC_GENERATION_SYSTEM_PROMPT),
            ChatMessage::user(&book_info),
        ];

        let topics = match self.llm.chat(&self.model, &messages, None).await {
            Ok(response) => {
                let content = response.choices.first()
                    .and_then(|c| c.message.content.clone())
                    .unwrap_or_default();
                let content = strip_code_fence(&content);
                match serde_json::from_str::<Vec<DebateTopic>>(&content) {
                    Ok(t) if !t.is_empty() => t,
                    _ => self.fallback_topics(),
                }
            }
            Err(e) => {
                tracing::warn!("LLM 辩题生成失败，使用兜底话题: bookId={} - {}", book_id, e);
                self.fallback_topics()
            }
        };

        // 写入缓存
        if let Err(e) = self.write_topic_cache(&cache_path, &topics) {
            tracing::debug!("辩题缓存写入失败: {}", e);
        }

        Ok(topics)
    }

    /// 使用 LLM 优化用户自定义辩题
    pub async fn optimize_topic(
        &self,
        pool: &SqlitePool,
        book_id: i64,
        topic: &str,
        pro_argument: &str,
        con_argument: &str,
    ) -> anyhow::Result<DebateTopic> {
        let book = self.load_book(pool, book_id).await?;

        let book_info = match &book {
            Some(b) => self.build_book_info_for_topic(b),
            None => {
                return Ok(DebateTopic {
                    topic: topic.to_string(),
                    source: "USER".into(),
                    pro_argument: pro_argument.to_string(),
                    con_argument: con_argument.to_string(),
                });
            }
        };

        let user_msg = format!(
            "【书籍信息】\n{}\n\n【用户输入】\n辩题：{}\n正方观点：{}\n反方观点：{}",
            book_info, topic,
            pro_argument,
            con_argument
        );

        let messages = vec![
            ChatMessage::system(prompt::DEBATE_OPTIMIZE_TOPIC_SYSTEM_PROMPT),
            ChatMessage::user(&user_msg),
        ];

        match self.llm.chat(&self.model, &messages, None).await {
            Ok(response) => {
                let content = response.choices.first()
                    .and_then(|c| c.message.content.clone())
                    .unwrap_or_default();
                let content = strip_code_fence(&content);
                if let Ok(node) = serde_json::from_str::<serde_json::Value>(&content) {
                    let optimized_topic = node.get("topic")
                        .and_then(|v| v.as_str())
                        .unwrap_or(topic)
                        .to_string();
                    let optimized_pro = node.get("proArgument")
                        .and_then(|v| v.as_str())
                        .unwrap_or(pro_argument)
                        .to_string();
                    let optimized_con = node.get("conArgument")
                        .and_then(|v| v.as_str())
                        .unwrap_or(con_argument)
                        .to_string();
                    return Ok(DebateTopic {
                        topic: optimized_topic,
                        source: "LLM".into(),
                        pro_argument: optimized_pro,
                        con_argument: optimized_con,
                    });
                }
            }
            Err(e) => {
                tracing::warn!("LLM 辩题优化失败，返回原始输入: {}", e);
            }
        }

        Ok(DebateTopic {
            topic: topic.to_string(),
            source: "USER".into(),
            pro_argument: pro_argument.to_string(),
            con_argument: con_argument.to_string(),
        })
    }

    // ==================== 各环节发言 ====================

    /// Generic stream speech — dispatches to the correct speak method based on speak_type.
    /// speak_type: "OPENING", "CROSS_EXAM", "REBUTTAL", "FREE", "CLOSING"
    pub async fn stream_speech(
        &self,
        pool: &SqlitePool,
        session_id: &str,
        role_key: &str,
        _role_name: &str,
        side: &str,
        speak_type: &str,
        _round: i32,
        context: &str,
        _system_prompt: &str,
    ) -> anyhow::Result<mpsc::Receiver<anyhow::Result<String>>> {
        let session = debate_repo::find_session(pool, session_id).await?;
        let topic = session.as_ref().map(|s| s.topic.as_str()).unwrap_or("");
        let book_context = session.as_ref().and_then(|s| s.book_context.as_deref()).unwrap_or("");

        match speak_type.to_uppercase().as_str() {
            "OPENING" => self.speak_opening(pool, session_id, role_key, side, topic, book_context).await,
            "CROSS_EXAM" | "CROSS_EXAM_QUESTION" => {
                self.speak_cross_exam_question(pool, session_id, role_key, side, topic, context).await
            }
            "CROSS_EXAM_ANSWER" => {
                // context here is the question content; we also need defender_opening
                self.speak_cross_exam_answer(pool, session_id, role_key, side, topic, context, context).await
            }
            "REBUTTAL" => self.speak_rebuttal(pool, session_id, role_key, side, topic, context).await,
            "FREE" => self.speak_free(pool, session_id, role_key, side, topic, context).await,
            "CLOSING" => self.speak_closing(pool, session_id, role_key, side, topic, context).await,
            _ => {
                // Default: use opening prompt
                self.speak_opening(pool, session_id, role_key, side, topic, book_context).await
            }
        }
    }

    /// 开篇立论 — 第1轮：PRO_1 → CON_1
    pub async fn speak_opening(
        &self,
        pool: &SqlitePool,
        session_id: &str,
        role_key: &str,
        side: &str,
        topic: &str,
        book_context: &str,
    ) -> anyhow::Result<mpsc::Receiver<anyhow::Result<String>>> {
        let messages = self.build_speech_messages(
            pool, session_id, role_key, side, topic, book_context,
            prompt::DEBATE_OPENING_PROMPT,
            prompt::DEBATE_OPENING_OUTPUT,
            None,
        ).await?;

        self.stream_speech_internal(messages).await
    }

    /// 交叉质询 — 质询方提问
    pub async fn speak_cross_exam_question(
        &self,
        pool: &SqlitePool,
        session_id: &str,
        role_key: &str,
        side: &str,
        topic: &str,
        defender_opening: &str,
    ) -> anyhow::Result<mpsc::Receiver<anyhow::Result<String>>> {
        let extra = format!(
            "【对方一辩立论全文 — 请针对以下内容提出质询问题】\n{}",
            defender_opening
        );

        let messages = self.build_speech_messages(
            pool, session_id, role_key, side, topic, "",
            prompt::DEBATE_CROSS_EXAM_QUESTIONER_PROMPT,
            prompt::DEBATE_CROSS_EXAM_QUESTIONER_OUTPUT,
            Some(&extra),
        ).await?;

        self.stream_speech_internal(messages).await
    }

    /// 交叉质询 — 被质询方回答
    pub async fn speak_cross_exam_answer(
        &self,
        pool: &SqlitePool,
        session_id: &str,
        role_key: &str,
        side: &str,
        topic: &str,
        defender_opening: &str,
        question_content: &str,
    ) -> anyhow::Result<mpsc::Receiver<anyhow::Result<String>>> {
        let extra = format!(
            "【你的立论全文 — 请坚守以下立场回答】\n{}\n\n【对方的问题】\n{}",
            defender_opening, question_content
        );

        let messages = self.build_speech_messages(
            pool, session_id, role_key, side, topic, "",
            prompt::DEBATE_CROSS_EXAM_ANSWERER_PROMPT,
            prompt::DEBATE_CROSS_EXAM_ANSWERER_OUTPUT,
            Some(&extra),
        ).await?;

        self.stream_speech_internal(messages).await
    }

    /// 驳论 — 第3轮
    pub async fn speak_rebuttal(
        &self,
        pool: &SqlitePool,
        session_id: &str,
        role_key: &str,
        side: &str,
        topic: &str,
        opponent_speech: &str,
    ) -> anyhow::Result<mpsc::Receiver<anyhow::Result<String>>> {
        let extra = format!(
            "【对方一辩立论 — 请集中火力反驳以下论证】\n{}",
            opponent_speech
        );

        let messages = self.build_speech_messages(
            pool, session_id, role_key, side, topic, "",
            prompt::DEBATE_REBUTTAL_PROMPT,
            prompt::DEBATE_REBUTTAL_OUTPUT,
            Some(&extra),
        ).await?;

        self.stream_speech_internal(messages).await
    }

    /// 自由辩论 — 第4轮
    pub async fn speak_free(
        &self,
        pool: &SqlitePool,
        session_id: &str,
        role_key: &str,
        side: &str,
        topic: &str,
        last_speech: &str,
    ) -> anyhow::Result<mpsc::Receiver<anyhow::Result<String>>> {
        let messages = self.build_speech_messages(
            pool, session_id, role_key, side, topic, "",
            prompt::DEBATE_FREE_PROMPT,
            prompt::DEBATE_FREE_OUTPUT,
            Some(last_speech),
        ).await?;

        self.stream_speech_internal(messages).await
    }

    /// 总结陈词 — 第5轮：CON_4 → PRO_4
    pub async fn speak_closing(
        &self,
        pool: &SqlitePool,
        session_id: &str,
        role_key: &str,
        side: &str,
        topic: &str,
        debate_summary: &str,
    ) -> anyhow::Result<mpsc::Receiver<anyhow::Result<String>>> {
        let messages = self.build_speech_messages(
            pool, session_id, role_key, side, topic, "",
            prompt::DEBATE_CLOSING_PROMPT,
            prompt::DEBATE_CLOSING_OUTPUT,
            Some(debate_summary),
        ).await?;

        self.stream_speech_internal(messages).await
    }

    // ==================== 自由辩论发言人选择 ====================

    /// LLM 决定自由辩论下一发言人
    pub async fn select_free_speaker(
        &self,
        pool: &SqlitePool,
        session_id: &str,
        last_speaker_key: &str,
        pro_keys: &[&str],
        con_keys: &[&str],
    ) -> anyhow::Result<String> {
        // 统计发言次数
        let all_messages = debate_repo::get_messages(pool, session_id).await?;
        let mut speak_counts: HashMap<String, u64> = HashMap::new();
        for msg in &all_messages {
            let key = msg.position_key.as_deref()
                .or(msg.role_key.as_deref())
                .unwrap_or("");
            if key != "HOST" && !key.is_empty() {
                *speak_counts.entry(key.to_string()).or_insert(0) += 1;
            }
        }

        // 构建角色信息
        let mut roles_info = String::new();
        for key in pro_keys.iter().chain(con_keys.iter()) {
            let side = if key.starts_with("PRO") { "正方" } else { "反方" };
            let count = speak_counts.get(*key).copied().unwrap_or(0);
            roles_info.push_str(&format!("{}({}) - 发言{}次\n", key, side, count));
        }

        // 上一位发言者信息
        let last_side = if last_speaker_key.starts_with("PRO") { "PRO" } else { "CON" };

        // 构建发言次数统计
        let mut count_info = String::new();
        for key in pro_keys.iter().chain(con_keys.iter()) {
            let count = speak_counts.get(*key).copied().unwrap_or(0);
            count_info.push_str(&format!("{}: {}次\n", key, count));
        }

        let user_msg = format!(
            "【辩题】\n（从会话获取）\n\n【辩手信息】\n{}\n【上一位发言者】{}\n【上一位发言者立场】{}\n【发言次数统计】\n{}",
            roles_info, last_speaker_key, last_side, count_info
        );

        let messages = vec![
            ChatMessage::system(prompt::DEBATE_NEXT_SPEAKER_FREE_SYSTEM_PROMPT),
            ChatMessage::user(&user_msg),
        ];

        // 尝试 LLM 选择
        match self.llm.chat(&self.model, &messages, None).await {
            Ok(response) => {
                let content = response.choices.first()
                    .and_then(|c| c.message.content.clone())
                    .unwrap_or_default();
                let result = content.trim().to_string();

                // 验证位置键有效性
                if is_valid_position_key(&result) {
                    // 验证交替：不能与上一位同方
                    let result_side = if result.starts_with("PRO") { "PRO" } else { "CON" };
                    if last_side != result_side || last_speaker_key.is_empty() {
                        return Ok(result);
                    }
                    tracing::warn!("LLM 选择了同方辩手 {}，强制交替到对方", result);
                }
            }
            Err(e) => {
                tracing::warn!("LLM 发言人选择失败: {}", e);
            }
        }

        // 回退：选取对方发言次数最少的非 HOST 辩手
        let opposite_side = if last_side == "PRO" { "CON" } else { "PRO" };
        let keys: Vec<&str> = if opposite_side == "PRO" {
            pro_keys.to_vec()
        } else {
            con_keys.to_vec()
        };

        let least = keys.iter()
            .filter(|k| **k != "HOST")
            .min_by_key(|k| speak_counts.get(**k).copied().unwrap_or(0))
            .map(|k| k.to_string())
            .unwrap_or_else(|| "PRO_1".to_string());

        Ok(least)
    }

    // ==================== 主持人即兴点评 ====================

    /// LLM 生成主持人点评（非硬编码模板）
    pub async fn host_commentary(
        &self,
        pool: &SqlitePool,
        session_id: &str,
        commentary_type: &str, // TRANSITION / FREE_MID / WRAPUP
        context: &str,
    ) -> anyhow::Result<mpsc::Receiver<anyhow::Result<String>>> {
        let session = debate_repo::find_session(pool, session_id).await?;
        let topic = session.as_ref().map(|s| s.topic.as_str()).unwrap_or("");

        let mut user_ctx = String::new();
        user_ctx.push_str(&format!("【点评类型】{}\n", commentary_type));
        user_ctx.push_str(&format!("【辩题】{}\n", topic));
        if !context.is_empty() {
            user_ctx.push_str(&format!("【点评上下文】\n{}", context));
        }

        let messages = vec![
            ChatMessage::system(prompt::DEBATE_HOST_COMMENTARY_SYSTEM_PROMPT),
            ChatMessage::user(&user_ctx),
        ];

        self.stream_speech_internal(messages).await
    }

    // ==================== 评分 ====================

    /// 7维度评分
    pub async fn score_speech(
        &self,
        pool: &SqlitePool,
        session_id: &str,
        role_key: &str,
        position_key: &str,
        side: &str,
        content: &str,
        round_number: i32,
        round_type: &str,
    ) -> anyhow::Result<DebateScoreResult> {
        let user_msg = format!(
            "【辩题】\n（从会话获取）\n【发言者】{}\n【立场】{}\n【发言内容】\n{}",
            role_key, side, content
        );

        let messages = vec![
            ChatMessage::system(prompt::DEBATE_SCORING_SYSTEM_PROMPT),
            ChatMessage::user(&user_msg),
        ];

        match self.llm.chat(&self.model, &messages, None).await {
            Ok(response) => {
                let resp_content = response.choices.first()
                    .and_then(|c| c.message.content.clone())
                    .unwrap_or_default();
                let resp_content = strip_code_fence(&resp_content);

                if let Ok(node) = serde_json::from_str::<serde_json::Value>(&resp_content) {
                    let result = DebateScoreResult {
                        logic_score: node.get("logicScore").and_then(|v| v.as_f64()).unwrap_or(5.0),
                        evidence_score: node.get("evidenceScore").and_then(|v| v.as_f64()).unwrap_or(5.0),
                        rebuttal_score: node.get("rebuttalScore").and_then(|v| v.as_f64()).unwrap_or(5.0),
                        impact_score: node.get("impactScore").and_then(|v| v.as_f64()).unwrap_or(5.0),
                        humor_score: node.get("humorScore").and_then(|v| v.as_f64()).unwrap_or(5.0),
                        clarity_score: node.get("clarityScore").and_then(|v| v.as_f64()).unwrap_or(5.0),
                        novelty_score: node.get("noveltyScore").and_then(|v| v.as_f64()).unwrap_or(5.0),
                    };

                    let average = result.average();

                    // 保存到数据库
                    let _ = self.save_score(
                        pool, session_id, role_key, position_key, side,
                        round_number, round_type, &result, average,
                    ).await;

                    return Ok(result);
                }
            }
            Err(e) => {
                tracing::warn!("LLM 评分失败: {}", e);
            }
        }

        // 回退：默认分数
        Ok(DebateScoreResult::default())
    }

    // ==================== 报告生成 ====================

    /// 生成辩论报告
    pub async fn generate_report(
        &self,
        pool: &SqlitePool,
        session_id: &str,
    ) -> anyhow::Result<String> {
        let messages_list = debate_repo::get_messages(pool, session_id).await?;
        let session = debate_repo::find_session(pool, session_id).await?;

        let topic = session.as_ref().map(|s| s.topic.as_str()).unwrap_or("未知辩题");

        // 构建辩论全文
        let mut debate_text = format!("【辩题】{}\n\n", topic);
        for msg in &messages_list {
            let side_label = match msg.side.as_deref() {
                Some("PRO") => "[正方]",
                Some("CON") => "[反方]",
                _ => "[主持]",
            };
            let name = msg.role_name.as_deref().unwrap_or("未知");
            let content = msg.content.as_deref().unwrap_or("");
            debate_text.push_str(&format!("{}{}：{}\n\n", side_label, name, content));
        }

        let messages = vec![
            ChatMessage::system(prompt::DEBATE_REPORT_SYSTEM_PROMPT),
            ChatMessage::user(&debate_text),
        ];

        let response = self.llm.chat(&self.model, &messages, None).await?;
        let content = response.choices.first()
            .and_then(|c| c.message.content.clone())
            .unwrap_or_default();

        // 保存报告
        let _ = debate_repo::insert_report(pool, session_id, &content).await;

        Ok(content)
    }

    // ==================== 内部辅助方法 ====================

    /// 构建发言消息列表
    async fn build_speech_messages(
        &self,
        pool: &SqlitePool,
        session_id: &str,
        role_key: &str,
        side: &str,
        topic: &str,
        book_context: &str,
        system_prompt: &str,
        output_prompt: &str,
        extra_content: Option<&str>,
    ) -> anyhow::Result<Vec<ChatMessage>> {
        let mut messages = Vec::new();

        // 1. 系统提示词（仅共享规则）
        messages.push(ChatMessage::system(system_prompt));

        // 2. 辩题 + 立场
        let side_name = match side {
            "PRO" => "正方",
            "CON" => "反方",
            _ => "",
        };
        let side_full = if role_key == "HOST" {
            "主持人（中立）".to_string()
        } else if side == "PRO" {
            "正方".to_string()
        } else {
            "反方".to_string()
        };

        let mut topic_info = format!("【当前辩题】\n{}\n", topic);
        topic_info.push_str(&format!("【你的立场】\n{}\n", side_full));
        if !book_context.is_empty() {
            topic_info.push_str(&format!("【书籍上下文】\n{}\n", book_context));
        }
        messages.push(ChatMessage::user(&topic_info));

        // 3. 对话记录
        let history = debate_repo::get_messages(pool, session_id).await.unwrap_or_default();
        let has_history = !history.is_empty();
        let has_extra = extra_content.is_some();

        if has_history || has_extra {
            let mut history_builder = String::from("【对话记录】\n");
            for msg in &history {
                let name = msg.role_name.as_deref().unwrap_or("未知");
                let content = msg.content.as_deref().unwrap_or("");
                history_builder.push_str(&format!("{}：{}\n\n", name, content));
            }
            if let Some(extra) = extra_content {
                history_builder.push_str(&format!("当前上下文：{}", extra));
            }
            messages.push(ChatMessage::user(&history_builder));
        }

        // 4. 角色设定
        let position_label = get_position_label(role_key);
        let is_host = role_key == "HOST";
        let side_label = if is_host { "" } else { side_name };
        let position_name = if is_host { "主持人" } else { position_label.as_str() };
        let personality_prompt = "";
        let role_setting = prompt::DEBATE_ROLE_SETTING
            .replacen("%s", side_label, 1)
            .replacen("%s", position_name, 1)
            .replacen("%s", role_key, 1)
            .replacen("%s", &side_full, 1)
            .replacen("%s", personality_prompt, 1);
        messages.push(ChatMessage::user(&role_setting));

        // 5. 输出要求 + 发言指令
        let speak_instruction = if is_host {
            "请以主持人的身份发言。引导本环节、点评精彩观点、为下一位辩手铺垫。".to_string()
        } else {
            format!(
                "请以{}的身份发言。{}直接说出你的观点，不要复述自己的角色设定。",
                position_label,
                if side == "PRO" { "坚定维护正方立场。" } else if side == "CON" { "坚定维护反方立场。" } else { "" }
            )
        };
        messages.push(ChatMessage::user(&format!("{}\n\n{}", output_prompt, speak_instruction)));

        Ok(messages)
    }

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

    /// 构建辩论摘要（用于总结陈词）
    pub async fn build_debate_summary(
        &self,
        pool: &SqlitePool,
        session_id: &str,
    ) -> anyhow::Result<String> {
        let messages = debate_repo::get_messages(pool, session_id).await?;
        if messages.is_empty() {
            return Ok(String::new());
        }

        let mut sb = String::new();
        for msg in &messages {
            let side_label = match msg.side.as_deref() {
                Some("PRO") => "[正方]",
                Some("CON") => "[反方]",
                _ => "[主持]",
            };
            let name = msg.role_name.as_deref().unwrap_or("未知");
            let content = msg.content.as_deref().unwrap_or("");
            sb.push_str(&format!("{}{}：{}\n\n", side_label, name, content));
        }

        const SUMMARY_MAX_LENGTH: usize = 3000;
        if sb.len() > SUMMARY_MAX_LENGTH {
            sb.truncate(SUMMARY_MAX_LENGTH);
            sb.push_str("...");
        }

        Ok(sb)
    }

    /// 保存评分到数据库
    async fn save_score(
        &self,
        pool: &SqlitePool,
        session_id: &str,
        role_key: &str,
        position_key: &str,
        side: &str,
        round_number: i32,
        round_type: &str,
        result: &DebateScoreResult,
        average: f64,
    ) -> anyhow::Result<()> {
        sqlx::query(
            "INSERT INTO debate_scores (session_id, role_key, position_key, side, round_number, round_type, \
             logic_score, evidence_score, rebuttal_score, impact_score, humor_score, clarity_score, novelty_score, average_score) \
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )
        .bind(session_id)
        .bind(role_key)
        .bind(position_key)
        .bind(side)
        .bind(round_number)
        .bind(round_type)
        .bind(result.logic_score)
        .bind(result.evidence_score)
        .bind(result.rebuttal_score)
        .bind(result.impact_score)
        .bind(result.humor_score)
        .bind(result.clarity_score)
        .bind(result.novelty_score)
        .bind(average)
        .execute(pool)
        .await?;

        Ok(())
    }

    /// 加载书籍
    async fn load_book(&self, pool: &SqlitePool, book_id: i64) -> anyhow::Result<Option<Book>> {
        let book = sqlx::query_as::<_, Book>("SELECT * FROM books WHERE id = ?")
            .bind(book_id)
            .fetch_optional(pool)
            .await?;
        Ok(book)
    }

    /// 构建辩题生成用的书籍信息
    fn build_book_info_for_topic(&self, book: &Book) -> String {
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
                sb.push_str(&format!("读者需求：{}\n", cleaned));
            }
        }
        if let Some(tags) = &book.target_reader_tags {
            if !tags.is_empty() {
                let cleaned = clean_tags(tags);
                sb.push_str(&format!("目标读者：{}\n", cleaned));
            }
        }
        if let Some(desc) = &book.description {
            if !desc.is_empty() {
                sb.push_str(&format!("简介：{}\n", desc));
            }
        }
        if let Some(toc) = &book.toc {
            if !toc.is_empty() {
                sb.push_str(&format!("目录：\n{}\n", toc));
            }
        }
        if let Some(summary) = &book.compressed_summary {
            if !summary.is_empty() {
                sb.push_str(&format!("\n【图书精炼摘要】\n{}\n", summary));
            }
        } else if let Some(summary) = &book.chapter_summary {
            if !summary.is_empty() {
                sb.push_str(&format!("\n【章节摘要】\n{}\n", summary));
            }
        }
        sb
    }

    fn topic_cache_path(&self, book_id: i64) -> PathBuf {
        let dir = std::env::temp_dir().join("kbook").join("debate_topics");
        dir.join(format!("debate_topics_{}.json", book_id))
    }

    fn read_topic_cache(&self, path: &PathBuf) -> anyhow::Result<Vec<DebateTopic>> {
        let metadata = std::fs::metadata(path)?;
        let modified = metadata.modified()?;
        let elapsed = modified.elapsed()?;
        // 24h TTL
        if elapsed.as_secs() > 86400 {
            return Ok(Vec::new());
        }
        let content = std::fs::read_to_string(path)?;
        let topics: Vec<DebateTopic> = serde_json::from_str(&content)?;
        Ok(topics)
    }

    fn write_topic_cache(&self, path: &PathBuf, topics: &[DebateTopic]) -> anyhow::Result<()> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let content = serde_json::to_string(topics)?;
        std::fs::write(path, content)?;
        Ok(())
    }

    fn fallback_topics(&self) -> Vec<DebateTopic> {
        vec![
            DebateTopic {
                topic: "技术进步是否必然带来幸福？".into(),
                source: "SYSTEM".into(),
                pro_argument: "技术进步提高了生活效率、医疗水平和信息获取能力，是人类幸福的基石".into(),
                con_argument: "技术进步导致隐私丧失、社会焦虑加剧、人际关系疏离".into(),
            },
            DebateTopic {
                topic: "成功更多依赖天赋还是努力？".into(),
                source: "SYSTEM".into(),
                pro_argument: "天赋决定上限，没有天赋再努力也难以达到顶尖水平".into(),
                con_argument: "努力可以弥补天赋的不足，持续的努力才是成功的关键".into(),
            },
            DebateTopic {
                topic: "人工智能的发展对人类利大于弊吗？".into(),
                source: "SYSTEM".into(),
                pro_argument: "AI 提升了生产效率、医疗诊断准确率，解决了很多人类难以解决的问题".into(),
                con_argument: "AI 会导致大规模失业、伦理困境，甚至可能威胁人类生存".into(),
            },
        ]
    }
}

// ==================== 数据结构 ====================

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct DebateTopic {
    pub topic: String,
    #[serde(default)]
    pub source: String,
    #[serde(rename = "proArgument")]
    pub pro_argument: String,
    #[serde(rename = "conArgument")]
    pub con_argument: String,
}

#[derive(Debug, Clone)]
pub struct DebateScoreResult {
    pub logic_score: f64,
    pub evidence_score: f64,
    pub rebuttal_score: f64,
    pub impact_score: f64,
    pub humor_score: f64,
    pub clarity_score: f64,
    pub novelty_score: f64,
}

impl Default for DebateScoreResult {
    fn default() -> Self {
        Self {
            logic_score: 5.0,
            evidence_score: 5.0,
            rebuttal_score: 5.0,
            impact_score: 5.0,
            humor_score: 5.0,
            clarity_score: 5.0,
            novelty_score: 5.0,
        }
    }
}

impl DebateScoreResult {
    pub fn average(&self) -> f64 {
        (self.logic_score + self.evidence_score + self.rebuttal_score
            + self.impact_score + self.humor_score + self.clarity_score
            + self.novelty_score) / 7.0
    }
}

// ==================== 工具函数 ====================

fn get_position_label(position_key: &str) -> String {
    if position_key == "HOST" {
        return "主持人".to_string();
    }
    let side_name = if position_key.starts_with("PRO") { "正方" } else { "反方" };
    let slot_labels = ["一辩", "二辩", "三辩", "四辩"];
    let num_char = position_key.chars().last().unwrap_or('1');
    let index = (num_char as usize).wrapping_sub('1' as usize);
    if index < 4 {
        format!("{}{}", side_name, slot_labels[index])
    } else {
        position_key.to_string()
    }
}

fn is_valid_position_key(key: &str) -> bool {
    const VALID_KEYS: &[&str] = &["HOST", "PRO_1", "PRO_2", "PRO_3", "PRO_4", "CON_1", "CON_2", "CON_3", "CON_4"];
    VALID_KEYS.contains(&key)
}

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
