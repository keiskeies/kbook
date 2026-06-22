use crate::llm::{LlmClient, ChatMessage, StreamChunk};

use kbook_vector::embedding::EmbeddingClient;
use sqlx::SqlitePool;
use tokio::sync::mpsc;

pub struct BookChatService {
    pub llm: LlmClient,
    pub model: String,
    pub embedding_client: Option<EmbeddingClient>,
}

impl BookChatService {
    pub fn new(llm: LlmClient, model: String) -> Self {
        Self {
            llm,
            model,
            embedding_client: None,
        }
    }

    pub fn with_embedding_client(mut self, client: EmbeddingClient) -> Self {
        self.embedding_client = Some(client);
        self
    }

    /// Build the book info prompt (static part, reusable across sessions)
    fn build_book_info_prompt(book: &kbook_core::entity::Book) -> String {
        let mut sb = String::new();
        sb.push_str("【当前讨论的书籍】\n");
        sb.push_str(&format!("书名：《{}》\n", book.title));
        if let Some(author) = &book.author {
            if !author.is_empty() {
                sb.push_str(&format!("作者：{}\n", author));
            }
        }
        if let Some(tags) = &book.format_tags {
            if !tags.is_empty() {
                let clean = tags.replace('[', "").replace(']', "").replace('"', "").replace(',', "、");
                sb.push_str(&format!("标签：{}\n", clean));
            }
        }
        if let Some(tags) = &book.concept_tags {
            if !tags.is_empty() {
                let clean = tags.replace('[', "").replace(']', "").replace('"', "").replace(',', "、");
                sb.push_str(&format!("核心概念：{}\n", clean));
            }
        }
        if let Some(tags) = &book.reader_need_tags {
            if !tags.is_empty() {
                let clean = tags.replace('[', "").replace(']', "").replace('"', "").replace(',', "、");
                sb.push_str(&format!("读者需求：{}\n", clean));
            }
        }
        if let Some(tags) = &book.target_reader_tags {
            if !tags.is_empty() {
                let clean = tags.replace('[', "").replace(']', "").replace('"', "").replace(',', "、");
                sb.push_str(&format!("目标读者：{}\n", clean));
            }
        }
        if let Some(rating) = book.rating {
            sb.push_str(&format!("评分：{:.1}", rating));
            if let Some(count) = book.rating_count {
                if count > 0 {
                    sb.push_str(&format!("（{}人评分）", count));
                }
            }
            sb.push('\n');
        }
        if let Some(count) = book.read_count {
            if count > 0 {
                sb.push_str(&format!("阅读量：{}次\n", count));
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

        // Summary: prefer compressedSummary → chapterSummary
        if let Some(summary) = &book.compressed_summary {
            if !summary.is_empty() {
                sb.push_str(&format!("\n【图书精炼摘要】\n{}\n", summary));
            }
        } else if let Some(summary) = &book.chapter_summary {
            if !summary.is_empty() {
                sb.push_str(&format!("\n【章节摘要】（每章核心内容概述）\n{}\n", summary));
            }
        }

        sb
    }

    /// Build the RAG + question prompt (dynamic part, changes per query)
    fn build_rag_prompt(question: &str, rag_context: &str) -> String {
        let mut sb = String::new();

        if !rag_context.is_empty() {
            sb.push_str("【书籍参考内容】（以下是从原著中检索到的与问题相关的片段）\n");
            sb.push_str(rag_context);
        } else {
            sb.push_str("【注意】未从原著中检索到直接相关的内容片段，请根据书籍基本信息谨慎回答。\n");
        }

        sb.push_str(&format!("\n【读者的问题】\n{}", question));
        sb.push_str("\n\n【重要提醒】请用中文直接回答上述问题，不要翻译、分类或解释参考片段。绝对不要直接引用或复述书中的原文内容，而是用自己的语言概括和转述书中的观点、情节和信息。");

        sb
    }

    /// Perform RAG retrieval: embed query, search book content vectors, build context string
    async fn do_rag_retrieval(
        pool: &SqlitePool,
        embedding_client: &EmbeddingClient,
        book_id: i64,
        query: &str,
        rag_top_k: usize,
        rag_max_chars: usize,
    ) -> anyhow::Result<String> {
        let results = kbook_vector::store::search_book_content(
            pool, embedding_client, book_id, query, rag_top_k,
        )
        .await?;

        if results.is_empty() {
            return Ok(String::new());
        }

        let mut sb = String::new();
        let mut total_len = 0;
        for (i, (chunk_text, _score)) in results.iter().enumerate() {
            if total_len + chunk_text.len() > rag_max_chars {
                break;
            }
            sb.push_str(&format!("【参考片段{}】\n{}\n\n", i + 1, chunk_text));
            total_len += chunk_text.len();
        }

        Ok(sb)
    }

    pub async fn stream_book_chat(
        &self,
        pool: &SqlitePool,
        user_id: i64,
        book_id: i64,
        message: &str,
        session_id: &str,
    ) -> anyhow::Result<mpsc::Receiver<anyhow::Result<String>>> {
        let book = sqlx::query_as::<_, kbook_core::entity::Book>(
            "SELECT * FROM books WHERE id = ?"
        )
        .bind(book_id)
        .fetch_optional(pool)
        .await?
        .ok_or_else(|| anyhow::anyhow!("Book not found"))?;

        // Build book info prompt (static)
        let book_info_prompt = Self::build_book_info_prompt(&book);

        // RAG retrieval if embedding client is available
        let rag_context = if let Some(ref ec) = self.embedding_client {
            match Self::do_rag_retrieval(pool, ec, book_id, message, 10, 30000).await {
                Ok(ctx) => ctx,
                Err(e) => {
                    tracing::warn!("RAG retrieval failed for book {}: {}", book_id, e);
                    String::new()
                }
            }
        } else {
            String::new()
        };

        // Build the current prompt (RAG + question)
        let current_prompt = Self::build_rag_prompt(message, &rag_context);

        // Get user's chat style
        let style = sqlx::query_as::<_, kbook_core::entity::User>(
            "SELECT * FROM users WHERE id = ?"
        )
        .bind(user_id)
        .fetch_optional(pool)
        .await
        .ok()
        .flatten()
        .and_then(|u| u.book_chat_style)
        .unwrap_or_else(|| "DEEP".to_string());

        let system_prompt = match style.to_uppercase().as_str() {
            "CASUAL" => crate::prompt::BOOK_CHAT_STYLE_CASUAL,
            "CONCISE" => crate::prompt::BOOK_CHAT_STYLE_CONCISE,
            "WITTY" => crate::prompt::BOOK_CHAT_STYLE_WITTY,
            _ => crate::prompt::BOOK_CHAT_STYLE_DEEP,
        };

        let mut messages = vec![ChatMessage::system(system_prompt)];

        // Book info as a separate user message (static prefix for KV Cache reuse)
        messages.push(ChatMessage::user(&book_info_prompt));

        // Load conversation history
        let history = crate::chat::get_history(pool, user_id, session_id).await?;
        for conv in &history {
            let content = conv
                .compressed_content
                .as_deref()
                .or(conv.content.as_deref())
                .unwrap_or("");
            if content.is_empty() {
                continue;
            }
            messages.push(ChatMessage {
                role: conv.role.clone().unwrap_or_default(),
                content: content.to_string(),
                tool_calls: None,
                tool_call_id: conv.tool_call_id.clone(),
                name: conv.tool_name.clone(),
            });
        }

        // Current prompt (RAG + question)
        messages.push(ChatMessage::user(&current_prompt));

        let (tx, rx) = mpsc::channel(200);
        let llm = self.llm.clone();
        let model = self.model.clone();
        let pool_clone = pool.clone();
        let user_id_c = user_id;
        let session_id_c = session_id.to_string();
        let book_id_c = book_id;
        let user_msg = message.to_string();

        tokio::spawn(async move {
            let mut full_response = String::new();
            match llm.stream_chat(&model, &messages, None).await {
                Ok(mut stream) => {
                    while let Some(chunk) = stream.recv().await {
                        match chunk {
                            Ok(StreamChunk { choices }) if choices.is_empty() => break,
                            Ok(StreamChunk { choices }) => {
                                for choice in choices {
                                    if let Some(content) = &choice.delta.content {
                                        full_response.push_str(content);
                                        let _ = tx.send(Ok(content.clone())).await;
                                    }
                                }
                            }
                            Err(e) => {
                                let _ = tx.send(Err(e)).await;
                                return;
                            }
                        }
                    }
                }
                Err(e) => {
                    let _ = tx.send(Err(e)).await;
                    return;
                }
            }

            // Save conversation after streaming completes
            let answer = full_response.trim().to_string();
            let _ = crate::chat::save_conversation(
                &pool_clone,
                user_id_c,
                &session_id_c,
                Some("book_chat"),
                Some(book_id_c),
                "user",
                &user_msg,
                None,
            )
            .await;
            let _ = crate::chat::save_conversation(
                &pool_clone,
                user_id_c,
                &session_id_c,
                Some("book_chat"),
                Some(book_id_c),
                "assistant",
                &answer,
                None,
            )
            .await;
        });

        Ok(rx)
    }

    /// Generate follow-up questions based on the conversation
    pub async fn generate_follow_up(
        &self,
        pool: &SqlitePool,
        _user_id: i64,
        book_id: i64,
        question: &str,
        answer: &str,
    ) -> anyhow::Result<Vec<String>> {
        if answer.is_empty() || question.is_empty() {
            return Ok(vec![]);
        }

        let book = sqlx::query_as::<_, kbook_core::entity::Book>("SELECT * FROM books WHERE id = ?")
            .bind(book_id)
            .fetch_optional(pool)
            .await?
            .ok_or_else(|| anyhow::anyhow!("Book not found"))?;

        let book_info = Self::build_book_info_prompt(&book);

        let system_prompt = "你正在和读者讨论一本书。根据图书基本信息和上轮问答，审视你刚才的回答，找出其中3个最可能引发这位读者追问的逻辑缝隙，将其转化为问题。逻辑缝隙包括但不限于：\n\
            - 你说了一个结论，但没有给出这个结论成立的条件或前提\n\
            - 你使用了一个关键概念，但它的含义在语境中可能被误解\n\
            - 你的论证存在一个隐含的预设，这个预设本身是可以被质疑的\n\
            - 你提出了一个判断，但没有说明它适用的边界或反例\n\n\
            要求：\n\
            - 每个问题直接指向回答中的具体逻辑点，不是泛泛的延伸讨论\n\
            - 问题要与本书内容紧密相关，不要偏离书籍主题\n\
            - 问题的提问对象是你这个AI，问题本身你必须能回答\n\
            - 每行一个，不超25字，无序号";

        let messages = vec![
            ChatMessage::system(system_prompt),
            ChatMessage::user(&format!("【图书信息】\n{}", book_info)),
            ChatMessage::user(&format!("读者问：{}\n你回答：{}", question, answer)),
        ];

        let response = self.llm.chat(&self.model, &messages, None).await?;
        let content = response
            .choices
            .first()
            .and_then(|c| c.message.content.clone())
            .unwrap_or_default();

        Ok(parse_questions(&content, 3))
    }

    /// Get suggested questions for a book.
    /// If DB has questions, return them. Otherwise, use LLM to generate.
    pub async fn get_suggested_questions(
        &self,
        pool: &SqlitePool,
        book_id: i64,
    ) -> anyhow::Result<Vec<String>> {
        // Check DB first
        let questions: Vec<String> = sqlx::query_as::<_, (String,)>(
            "SELECT question FROM book_suggested_questions WHERE book_id = ? LIMIT 10"
        )
        .bind(book_id)
        .fetch_all(pool)
        .await?
        .into_iter()
        .map(|(q,)| q)
        .collect();

        if !questions.is_empty() {
            let intro = "这本书主要讲了什么？".to_string();
            let filtered: Vec<String> = questions
                .into_iter()
                .filter(|q| q != &intro)
                .take(5)
                .collect();
            let mut result = vec![intro];
            result.extend(filtered);
            return Ok(result);
        }

        // No DB questions — try LLM generation
        match self.generate_questions_via_llm(pool, book_id).await {
            Ok(qs) if !qs.is_empty() => Ok(qs),
            _ => Ok(vec![
                "这本书主要讲了什么？".into(),
                "这本书的核心主题是什么？".into(),
                "作者想表达什么观点？".into(),
                "这本书适合什么人读？".into(),
                "有什么印象深刻的情节？".into(),
            ]),
        }
    }

    /// Generate suggested questions via LLM and save to DB
    async fn generate_questions_via_llm(
        &self,
        pool: &SqlitePool,
        book_id: i64,
    ) -> anyhow::Result<Vec<String>> {
        let book = sqlx::query_as::<_, kbook_core::entity::Book>("SELECT * FROM books WHERE id = ?")
            .bind(book_id)
            .fetch_optional(pool)
            .await?
            .ok_or_else(|| anyhow::anyhow!("Book not found"))?;

        let summary = book
            .compressed_summary
            .as_deref()
            .or(book.chapter_summary.as_deref())
            .or(book.description.as_deref())
            .unwrap_or("暂无摘要");

        let user_prompt = format!(
            "书名：《{}》\n作者：{}\n标签：{}\n简介：{}\n目录：{}\n摘要：{}",
            book.title,
            book.author.as_deref().unwrap_or("未知"),
            book.format_tags.as_deref().unwrap_or("暂无标签"),
            book.description.as_deref().unwrap_or("暂无简介"),
            book.toc.as_deref().unwrap_or("暂无目录"),
            summary,
        );

        let system_prompt = "你是一位资深阅读引导专家。请根据提供的图书信息，生成20个可以向AI深入探讨本书的问题。\n\n\
            要求：\n\
            1. 问题角度多样化且均衡，必须覆盖以下五个维度，每个维度至少3个问题：\n\
               - 核心命题与深层思辨：书籍究竟想回答什么问题？其背后的思想根基或理论野心何在？\n\
               - 关键节点与论证骨架：若是虚构叙事，指向情节冲突、转折与决定性场景；若是非虚构，指向核心观点、关键论据、实验或逻辑转折点。\n\
               - 核心行动元与视角演变：虚构作品中主要人物的欲望、选择与成长；非虚构中则指研究对象的特质、作者立场的变化，或书中关键思想家、案例主体的行动逻辑。\n\
               - 表达技艺与结构设计：叙事视角、语言质感、章节编排的用意；对非虚构而言，还包括论证策略、跨学科方法、数据呈现方式等。\n\
               - 现实投射与个人映照：书中的洞见如何照进我们的日常生活、社会议题或个体决策，能与读者产生何种私人联结。\n\
            2. 仔细阅读提供的图书信息，每个问题必须明确指向某个章节标题、简介中的具体概念，或摘要里出现的专有名词/事件，避免空泛笼统。\n\
            3. 问题采用读者在深度阅读后自然发生的口吻，带有探索、质疑或联想意味，仿佛在参加一场高质量的对谈。即便讨论理论，也保持对话感和好奇心，拒绝考试式发问。\n\
            4. 问题应体现标签所暗示的领域气质（如哲学则重概念辨析，科技则重机制与影响，历史则重史料与叙事），但严禁直接堆砌标签词语。\n\
            5. 严格遵守输出格式：\n\
               - 只输出20个问题本身\n\
               - 每个问题不超过50个汉字\n\
               - 一行一个问题\n\
               - 不加任何序号、项目符号、空行或解释性文字\n\
               - 不要出现\"问题1\"\"以下是……问题\"等前缀";

        let messages = vec![
            ChatMessage::system(system_prompt),
            ChatMessage::user(&user_prompt),
        ];

        let response = self.llm.chat(&self.model, &messages, None).await?;
        let content = response
            .choices
            .first()
            .and_then(|c| c.message.content.clone())
            .unwrap_or_default();

        let generated = parse_questions(&content, 20);

        // Save to DB
        for q in &generated {
            let _ = sqlx::query(
                "INSERT INTO book_suggested_questions (book_id, question) VALUES (?, ?)"
            )
            .bind(book_id)
            .bind(q)
            .execute(pool)
            .await;
        }

        // Return first 5 with intro question
        let intro = "这本书主要讲了什么？".to_string();
        let mut result: Vec<String> = generated.into_iter().take(5).collect();
        result.insert(0, intro);
        Ok(result)
    }
}

/// Parse AI-generated text into a list of questions.
/// Strips line numbers, empty lines, and short lines.
fn parse_questions(text: &str, limit: usize) -> Vec<String> {
    let mut seen = std::collections::HashSet::new();
    let mut result = Vec::new();
    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        // Strip leading numbers like "1. ", "1、", "1) "
        let cleaned = trimmed
            .trim_start_matches(|c: char| c.is_ascii_digit())
            .trim_start_matches(|c: char| c == '.' || c == '、' || c == ')' || c == ' ')
            .trim()
            .to_string();
        if cleaned.len() <= 2 {
            continue;
        }
        if seen.insert(cleaned.clone()) {
            result.push(cleaned);
            if result.len() >= limit {
                break;
            }
        }
    }
    result
}
