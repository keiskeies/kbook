import request from '@/utils/request'
import type { ReadingProgress, ReadingStats, ProgressBatchItem } from '@/types/book'

/** 上报阅读进度 */
export function reportProgress(data: {
  bookId: number
  progress: number
  currentPosition: string | null
}) {
  return request.post<ReadingProgress>('/progress', data)
}

/** 批量上报进度（断网恢复后） */
export function batchReportProgress(items: ProgressBatchItem[]) {
  return request.post('/progress/batch', items)
}

/** 获取某本书的阅读进度 */
export function getProgress(bookId: number) {
  return request.get<ReadingProgress>(`/progress/${bookId}`)
}

/** 批量获取进度 */
export function getProgressBatch(bookIds: number[]) {
  return request.post<Record<number, ReadingProgress>>('/progress/batch-get', { bookIds })
}

/** 获取用户所有阅读进度 */
export function getUserProgresses() {
  return request.get<ReadingProgress[]>('/progress/list')
}

/** 获取最近阅读 */
export function getRecentReading(limit = 10) {
  return request.get<ReadingProgress[]>('/progress/recent', { params: { limit } })
}

/** 获取阅读统计 */
export function getReadingStats() {
  return request.get<ReadingStats>('/progress/stats')
}
