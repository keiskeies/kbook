import request from '@/utils/request'
import { createSsePostConnection } from '@/utils/sse-request'
import type { AiCreateSessionResponse, AiChatResponse, AiChatRequest } from '@/types/ai'
import type { AiConversation } from '@/types/ai'

/** 创建新会话 */
export function createSession() {
  return request.post<AiCreateSessionResponse>('/ai/sessions')
}

/** 非流式对话 */
export function chat(data: AiChatRequest) {
  return request.post<AiChatResponse>('/ai/chat', data)
}

/** 获取对话历史 */
export function getHistory(sessionId: string) {
  return request.get<AiConversation[]>('/ai/history', { params: { sessionId } })
}

/** 获取会话列表 */
export function getSessions() {
  return request.get<string[]>('/ai/sessions')
}

/** 删除会话 */
export function deleteSession(sessionId: string) {
  return request.delete(`/ai/sessions/${sessionId}`)
}

/** 获取热门提问（基于全站用户提问统计） */
export function getHotPrompts(count = 4) {
  return request.get<string[]>('/ai/hot-prompts', { params: { count } })
}

/**
 * 流式对话 — SSE
 * 支持 401/403 自动刷新 Token 后重试
 * @returns 返回 AbortController，用于中断连接
 */
export function streamChat(
  data: AiChatRequest,
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (error: Error) => void,
  onThinking?: (status: string) => void,
  onThinkingContent?: (chunk: string) => void,
  onBookMap?: (bookMap: Record<string, number>) => void,
): AbortController {
  return createSsePostConnection(
    '/ai/chat/stream',
    data,
    { onChunk, onDone, onError, onThinking, onThinkingContent, onBookMap },
  )
}
