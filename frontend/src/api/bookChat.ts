import request from '@/utils/request'
import { createSsePostConnection } from '@/utils/sse-request'
import type { AiSessionItem } from '@/types/ai'

export function streamBookChat(
  bookId: number,
  data: { message: string; sessionId?: string },
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (error: Error) => void,
  onThinking?: (status: string) => void,
  onThinkingContent?: (chunk: string) => void,
  onSessionId?: (sessionId: string) => void,
  onFollowUpQuestions?: (questionsJson: string) => void,
): AbortController {
  return createSsePostConnection(
    `/books/${bookId}/chat/stream`,
    data,
    { onChunk, onDone, onError, onThinking, onThinkingContent, onSessionId, onFollowUpQuestions },
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
