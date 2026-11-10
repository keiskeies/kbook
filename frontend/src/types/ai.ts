/** AI 消息 */
export interface AiMessage {
  id: string
  role: 'user' | 'assistant' | 'system' | 'tool'
  content: string
  timestamp: number
  streaming?: boolean
  thinkingStatus?: string
  thinkingContent?: string
  userQuestion?: string
  followUpQuestions?: string[]
  loadingFollowUps?: boolean
  /** 后端 ai_conversations 行 id；流式回答由 conversation_id SSE 事件补全，历史回答直接取 r.id。
   *  反馈（P2-5）与引用依据（P2-7）都依赖它。 */
  conversationId?: number
}

/** AI 会话（后端 AiSession 实体） */
export interface AiSessionItem {
  id: number
  userId: number
  type: 'assistant' | 'book_chat' | 'admin'
  bookId: number | null
  sessionId: string
  title: string
  createdAt: string
  updatedAt: string
}

/** AI 对话请求 */
export interface AiChatRequest {
  sessionId?: string
  message: string
}

/** AI 对话响应（非流式） */
export interface AiChatResponse {
  sessionId: string
  response: string
}

/** 创建会话响应 */
export interface AiCreateSessionResponse {
  sessionId: string
}

/** AI 对话记录（后端持久化结构） */
export interface AiConversation {
  id: number
  userId: number
  sessionId: string
  type: string | null
  bookId: number | null
  role: string
  content: string
  thinkingContent: string | null
  followUpQuestions: string | null
  toolCallId: string | null
  toolName: string | null
  tokenCount: number | null
  createdAt: string
}
