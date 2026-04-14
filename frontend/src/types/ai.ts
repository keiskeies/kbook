/** AI 消息 */
export interface AiMessage {
  id: string
  role: 'user' | 'assistant' | 'system' | 'tool'
  content: string
  timestamp: number
  /** 是否正在流式输出 */
  streaming?: boolean
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

/** AI 提供商类型 */
export type AiProviderType = 'OPENAI' | 'OLLAMA'

/** AI 提供商 Thinking 等级 */
export type ThinkingLevel = 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH'

/** AI 提供商配置（全局配置，管理员管理） */
export interface AiProviderConfig {
  id?: number
  /** 提供商类型: OPENAI / OLLAMA */
  provider: AiProviderType
  /** 配置名称 */
  configName: string
  /** API 端点地址 */
  baseUrl: string
  /** API Key（OpenAI 兼容需要，Ollama 可留空） */
  apiKey?: string
  /** 模型名称 */
  modelName: string
  /** 温度 (0~2) */
  temperature?: number
  /** 最大 Token 数 */
  maxTokens?: number
  /** Thinking 等级: NONE/LOW/MEDIUM/HIGH — thinking 模型需要更长超时 */
  thinkingLevel?: ThinkingLevel
  /** 是否启用 */
  enabled?: boolean
  createdAt?: string
  updatedAt?: string
}

/** AI 提供商预设 */
export interface AiProviderPreset {
  label: string
  provider: AiProviderType
  baseUrl: string
  modelName: string
  requireApiKey: boolean
  thinkingLevel?: ThinkingLevel
}

/** 连接测试结果 */
export interface ConnectionTestResult {
  success: boolean
  message: string
  reply?: string
  modelName?: string
}
