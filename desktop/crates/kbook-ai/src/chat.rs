use crate::llm::{LlmClient, ChatMessage, StreamChunk};
use crate::tools;
use kbook_core::entity::AiConversation;
use sqlx::SqlitePool;
use tokio::sync::mpsc;

const MAX_TOOL_ROUNDS: usize = 10;

pub struct AiChatService {
    pub llm: LlmClient,
    pub model: String,
    pub tool_context: tools::ToolContext,
}

impl AiChatService {
    pub fn new(llm: LlmClient, model: String) -> Self {
        Self { llm, model, tool_context: tools::ToolContext::default() }
    }

    pub fn with_tool_context(mut self, ctx: tools::ToolContext) -> Self {
        self.tool_context = ctx;
        self
    }

    pub async fn chat(
        &self,
        pool: &SqlitePool,
        user_id: i64,
        session_id: &str,
        message: &str,
        system_prompt: &str,
    ) -> anyhow::Result<String> {
        let mut messages = vec![ChatMessage::system(system_prompt)];

        let history = get_history(pool, user_id, session_id).await?;
        for conv in &history {
            messages.push(ChatMessage {
                role: conv.role.clone().unwrap_or_default(),
                content: conv.compressed_content.clone().or(conv.content.clone()).unwrap_or_default(),
                tool_calls: None,
                tool_call_id: conv.tool_call_id.clone(),
                name: conv.tool_name.clone(),
            });
        }

        messages.push(ChatMessage::user(message));

        for _ in 0..MAX_TOOL_ROUNDS {
            let response = self.llm.chat(&self.model, &messages, Some(tools::get_tools())).await?;
            let choice = response.choices.first().ok_or_else(|| anyhow::anyhow!("No response"))?;

            if let Some(tool_calls) = &choice.message.tool_calls {
                messages.push(ChatMessage {
                    role: "assistant".into(),
                    content: choice.message.content.clone().unwrap_or_default(),
                    tool_calls: Some(tool_calls.clone()),
                    tool_call_id: None,
                    name: None,
                });

                for tc in tool_calls {
                    let result = tools::execute_tool(pool, &tc.function.name, &tc.function.arguments, user_id, &self.tool_context).await
                        .unwrap_or_else(|e| format!("{{\"error\": \"{}\"}}", e));
                    messages.push(ChatMessage::tool_result(&tc.id, &tc.function.name, &result));
                }
                continue;
            }

            return Ok(choice.message.content.clone().unwrap_or_default());
        }

        Ok("达到最大工具调用轮数".into())
    }

    pub async fn stream_chat(
        &self,
        pool: &SqlitePool,
        user_id: i64,
        session_id: &str,
        message: &str,
        system_prompt: &str,
    ) -> anyhow::Result<mpsc::Receiver<anyhow::Result<String>>> {
        let mut messages = vec![ChatMessage::system(system_prompt)];

        let history = get_history(pool, user_id, session_id).await?;
        for conv in &history {
            messages.push(ChatMessage {
                role: conv.role.clone().unwrap_or_default(),
                content: conv.compressed_content.clone().or(conv.content.clone()).unwrap_or_default(),
                tool_calls: None,
                tool_call_id: conv.tool_call_id.clone(),
                name: conv.tool_name.clone(),
            });
        }

        messages.push(ChatMessage::user(message));

        let (tx, rx) = mpsc::channel(200);
        let llm = self.llm.clone();
        let model = self.model.clone();
        let pool = pool.clone();
        let tool_context = self.tool_context.clone();

        tokio::spawn(async move {
            let mut current_messages = messages;

            for _ in 0..MAX_TOOL_ROUNDS {
                match llm.stream_chat(&model, &current_messages, Some(tools::get_tools())).await {
                    Ok(mut stream) => {
                        let mut content_buf = String::new();
                        let mut tool_calls_buf: std::collections::HashMap<usize, (Option<String>, Option<String>, String)> = std::collections::HashMap::new();
                        let mut has_tool_calls = false;

                        while let Some(chunk) = stream.recv().await {
                            match chunk {
                                Ok(StreamChunk { choices }) if choices.is_empty() => break,
                                Ok(StreamChunk { choices }) => {
                                    for choice in choices {
                                        if let Some(content) = &choice.delta.content {
                                            content_buf.push_str(content);
                                            let _ = tx.send(Ok(content.clone())).await;
                                        }
                                        if let Some(tcs) = &choice.delta.tool_calls {
                                            has_tool_calls = true;
                                            for tc in tcs {
                        let entry = tool_calls_buf.entry(tc.index).or_insert_with(|| (tc.id.clone(), None, String::new()));
                        if let Some(id) = &tc.id { entry.0 = Some(id.clone()); }
                        if let Some(func) = &tc.function {
                            if let Some(name) = &func.name { entry.1 = Some(name.clone()); }
                            if let Some(args) = &func.arguments { entry.2.push_str(args); }
                        }
                                            }
                                        }
                                    }
                                }
                                Err(e) => { let _ = tx.send(Err(e)).await; return; }
                            }
                        }

                        if has_tool_calls {
                            let mut tool_call_msgs = Vec::new();
                            for (_, (id, name, args)) in &tool_calls_buf {
                                if let (Some(id), Some(name)) = (id, name) {
                                    tool_call_msgs.push(crate::llm::ToolCall {
                                        id: id.clone(),
                                        call_type: "function".into(),
                                        function: crate::llm::FunctionCall { name: name.clone(), arguments: args.clone() },
                                    });
                                }
                            }
                            current_messages.push(ChatMessage {
                                role: "assistant".into(),
                                content: content_buf,
                                tool_calls: Some(tool_call_msgs.clone()),
                                tool_call_id: None,
                                name: None,
                            });

                            for tc in &tool_call_msgs {
                                let result = tools::execute_tool(&pool, &tc.function.name, &tc.function.arguments, user_id, &tool_context).await
                                    .unwrap_or_else(|e| format!("{{\"error\": \"{}\"}}", e));
                                current_messages.push(ChatMessage::tool_result(&tc.id, &tc.function.name, &result));
                            }
                            continue;
                        }

                        break;
                    }
                    Err(e) => { let _ = tx.send(Err(e)).await; return; }
                }
            }
        });

        Ok(rx)
    }
}

pub async fn get_history(
    pool: &SqlitePool,
    user_id: i64,
    session_id: &str,
) -> anyhow::Result<Vec<AiConversation>> {
    let convs = sqlx::query_as::<_, AiConversation>(
        "SELECT * FROM ai_conversations WHERE user_id = ? AND session_id = ? ORDER BY id ASC"
    )
    .bind(user_id)
    .bind(session_id)
    .fetch_all(pool)
    .await?;
    Ok(convs)
}

pub async fn save_conversation(
    pool: &SqlitePool,
    user_id: i64,
    session_id: &str,
    conv_type: Option<&str>,
    book_id: Option<i64>,
    role: &str,
    content: &str,
    thinking_content: Option<&str>,
) -> anyhow::Result<()> {
    kbook_db::repository::ai_repo::insert_conversation(
        pool, user_id, session_id, conv_type, book_id, role, content, thinking_content, None,
    ).await?;
    Ok(())
}
