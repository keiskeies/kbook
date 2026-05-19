import request from '@/utils/request'
import { createSsePostConnection } from '@/utils/sse-request'

/**
 * 流式图书问答 — SSE
 * POST /api/books/{bookId}/chat/stream
 *
 * @returns AbortController 用于中断请求
 */
export function streamBookChat(
  bookId: number,
  data: { message: string; sessionId?: string },
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (error: Error) => void,
  onThinking?: (status: string) => void,
  onThinkingContent?: (chunk: string) => void,
): AbortController {
  return createSsePostConnection(
    `/books/${bookId}/chat/stream`,
    data,
    { onChunk, onDone, onError, onThinking, onThinkingContent },
  )
}

/** 获取图书推荐问题 */
export function getBookSuggestedQuestions(bookId: number) {
  return request.get<string[]>(`/books/${bookId}/chat/suggestions`)
}

/** 获取图书问答历史 */
export function getBookChatHistory(bookId: number, sessionId?: string) {
  return request.get(`/books/${bookId}/chat/history`, {
    params: sessionId ? { sessionId } : {},
  })
}

/** 根据 AI 回答生成深入追问问题 */
export function getFollowUpQuestions(bookId: number, data: { question: string; answer: string }) {
  return request.post<string[]>(`/books/${bookId}/chat/follow-up`, data)
}
