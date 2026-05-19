/** AI 消息 */
export interface AiMessage {
  id: string
  role: 'user' | 'assistant' | 'system' | 'tool'
  content: string
  timestamp: number
  /** 是否正在流式输出 */
  streaming?: boolean
  /** thinking 状态文本（如"正在检索书籍内容..."） */
  thinkingStatus?: string
  /** AI 思考/推理过程内容（可折叠展示） */
  thinkingContent?: string
  /** 对应的用户问题（用于生成深入追问） */
  userQuestion?: string
  /** AI 回答后的深入追问问题列表 */
  followUpQuestions?: string[]
  /** 是否正在加载深入追问问题 */
  loadingFollowUps?: boolean
}

/** AI 会话 */
export interface AiSession {
  sessionId: string
  /** 最后一条消息摘要 */
  lastMessage: string
  /** 更新时间 */
  updatedAt: number
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
  role: string
  content: string
  toolCallId: string | null
  toolName: string | null
  tokenCount: number | null
  createdAt: string
}


