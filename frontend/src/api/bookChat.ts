import request from '@/utils/request'
import { createSsePostConnection } from '@/utils/sse-request'
import type { AiSessionItem } from '@/types/ai'

/** 单条检索依据（后端 AiRetrievalSnapshot） */
export interface RetrievalEvidence {
  id: number
  conversationId: number
  bookId: number
  chunkIndex: number
  chunkEndIndex?: number | null
  score?: number | null
  rerankScore?: number | null
  source?: string | null
  textPreview?: string | null
  promptVersion?: string | null
  createdAt?: string
}

/** 用户对单条回答的反馈状态 */
export interface AnswerFeedbackVO {
  id: number
  conversationId: number
  rating: 'UP' | 'DOWN'
  reason?: string | null
  comment?: string | null
  contextMode?: string | null
  reviewStatus?: string | null
}

export type FeedbackRating = 'UP' | 'DOWN'
export type FeedbackReason = 'WRONG' | 'NOT_GROUNDED' | 'OFF_TOPIC' | 'TOO_LONG' | 'OTHER'

/** 上下文模式标签（后端 scale_mode SSE 事件） */
export type ScaleMode = 'small' | 'medium' | 'large'

export function streamBookChat(
  bookId: number,
  data: { message: string; sessionId?: string; regenerate?: boolean; manual?: boolean },
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (error: Error) => void,
  onThinking?: (status: string) => void,
  onThinkingContent?: (chunk: string) => void,
  onSessionId?: (sessionId: string) => void,
  onFollowUpQuestions?: (questionsJson: string) => void,
  onReplace?: (safeText: string) => void,
  onConversationId?: (conversationId: number) => void,
  onScaleMode?: (mode: string) => void,
): AbortController {
  return createSsePostConnection(
    `/books/${bookId}/chat/stream`,
    // 声明本前端能识别 conversation_id 元数据事件。后端只对声明过的客户端下发，
    // 否则旧版前端会把这串数字当成回答正文渲染出来。
    { ...data, clientCaps: ['conversation_id'] },
    {
      onChunk, onDone, onError, onThinking, onThinkingContent, onSessionId,
      onFollowUpQuestions, onReplace, onConversationId, onScaleMode,
    },
  )
}

export function getBookSuggestedQuestions(bookId: number) {
  return request.get<string[]>(`/books/${bookId}/chat/suggestions`)
}

export function getBookChatHistory(bookId: number, sessionId?: string) {
  return request.get(`/books/${bookId}/chat/history`, {
    params: sessionId ? { sessionId } : {},
  })
}

export function getBookChatSessions(bookId: number) {
  return request.get<AiSessionItem[]>(`/books/${bookId}/chat/sessions`)
}

export function getFollowUpQuestions(bookId: number, data: { question: string; answer: string; sessionId?: string }) {
  return request.post<string[]>(`/books/${bookId}/chat/follow-up`, data)
}

export function exportBookChatHistory(bookId: number, sessionId: string) {
  return request.get<{ title?: string; content?: string; error?: string }>(`/books/${bookId}/chat/export`, {
    params: { sessionId },
  })
}

/** P2-7：取某条回答实际使用的检索依据。full_text / chapter 档返回占位行（chunkIndex=-1）。 */
export function getRetrievalEvidence(bookId: number, conversationId: number) {
  return request.get<RetrievalEvidence[]>(`/books/${bookId}/chat/${conversationId}/evidence`)
}

/** P2-5：提交或改「有用 / 没用」。差评会自动进入待标注队列。 */
export function submitAnswerFeedback(
  bookId: number,
  conversationId: number,
  data: { rating: FeedbackRating; reason?: FeedbackReason; comment?: string },
) {
  return request.post<AnswerFeedbackVO>(`/books/${bookId}/chat/${conversationId}/feedback`, data)
}

/** P2-5：读取当前用户在该回答上的反馈；未反馈为 null。 */
export function getAnswerFeedback(bookId: number, conversationId: number) {
  return request.get<AnswerFeedbackVO | null>(`/books/${bookId}/chat/${conversationId}/feedback`)
}

/** P2-5：打开历史会话时一次性取回多个回答的反馈状态。 */
export function getAnswerFeedbackBatch(bookId: number, conversationIds: number[]) {
  return request.post<Record<string, AnswerFeedbackVO>>(`/books/${bookId}/chat/feedback-batch`, { conversationIds })
}
