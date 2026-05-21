import request from '@/utils/request'
import { createSsePostConnection } from '@/utils/sse-request'
import type { AiCreateSessionResponse, AiChatResponse, AiChatRequest } from '@/types/ai'
import type { AiConversation, AiSessionItem } from '@/types/ai'

export function createSession() {
  return request.post<AiCreateSessionResponse>('/ai/sessions')
}

export function chat(data: AiChatRequest) {
  return request.post<AiChatResponse>('/ai/chat', data)
}

export function getHistory(sessionId: string) {
  return request.get<AiConversation[]>('/ai/history', { params: { sessionId } })
}

export function getSessions() {
  return request.get<AiSessionItem[]>('/ai/sessions')
}

export function deleteSession(sessionId: string) {
  return request.delete(`/ai/sessions/${sessionId}`)
}

export function getHotPrompts(count = 4) {
  return request.get<string[]>('/ai/hot-prompts', { params: { count } })
}

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
