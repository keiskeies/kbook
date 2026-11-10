import request from '@/utils/request'

/** 图书问答反馈的后台标注队列（方案 P2-5）。 */
export interface FeedbackQueueItem {
  id: number
  bookId: number
  bookTitle: string
  conversationId: number
  sessionId?: string | null
  rating: 'UP' | 'DOWN'
  reason?: string | null
  comment?: string | null
  contextMode?: string | null
  reviewStatus: string
  reviewNote?: string | null
  createdAt?: string
  question?: string
  answerPreview?: string
}

export interface FeedbackStats {
  total: number
  up: number
  down: number
  queued: number
  annotated: number
  dismissed: number
  downRatio: number
}

export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

export type ReviewStatus = 'QUEUED' | 'ANNOTATED' | 'DISMISSED'

export function getFeedbackQueue(status: string, page = 0, size = 20) {
  return request.get<PageResult<FeedbackQueueItem>>('/admin/book-qa/feedback/queue', {
    params: { status, page, size },
  })
}

export function getFeedbackStats() {
  return request.get<FeedbackStats>('/admin/book-qa/feedback/stats')
}

export function reviewFeedback(id: number, status: ReviewStatus, note?: string) {
  return request.post<void>(`/admin/book-qa/feedback/${id}/review`, { status, note })
}
