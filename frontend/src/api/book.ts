import request from '@/utils/request'
import { createSseConnection } from '@/utils/sse-request'
import { getAccessToken } from '@/utils/token-refresh'
import type { Book } from '@/types/book'
import type { PageResult } from '@/types/common'

/** 扫描错误项 */
export interface ScanError {
  file: string
  reason: string
}

/** 扫描进度 */
export interface ScanProgress {
  current: number
  total: number
  added: number
  updated: number
  skipped: number
  failed: number
  errors: ScanError[]
  currentFile: string
  status: 'scanning' | 'completed'
}

/** 扫描结果 */
export interface ScanResult {
  added: number
  updated: number
  skipped: number
  failed: number
  errors: ScanError[]
  elapsed: number
}

/** 获取图书详情 */
export function getBook(id: number) {
  return request.get<Book>(`/books/${id}`)
}

/** 搜索图书 */
export function searchBooks(params: {
  keyword?: string
  format?: string
  tag?: string
  page?: number
  size?: number
}) {
  return request.get<PageResult<Book>>('/books/search', { params })
}

/** 阅读热门 */
export function getReadRank(page = 1, size = 20) {
  return request.get<PageResult<Book>>('/books/rank/read', { params: { page, size } })
}

/** 高分推荐 */
export function getRatingRank(page = 1, size = 20) {
  return request.get<PageResult<Book>>('/books/rank/rating', { params: { page, size } })
}

/** 新书榜 */
export function getNewBooksRank(page = 1, size = 20) {
  return request.get<PageResult<Book>>('/books/rank/new', { params: { page, size } })
}

/** 搜索建议 */
export function suggestBooks(keyword: string) {
  return request.get<string[]>('/books/suggest', { params: { keyword } })
}

/** 按格式筛选 */
export function getBooksByFormat(format: string, page = 1, size = 20) {
  return request.get<PageResult<Book>>(`/books/format/${format}`, { params: { page, size } })
}

/** 按标签筛选 */
export function getBooksByTag(tag: string, page = 1, size = 20) {
  return request.get<PageResult<Book>>(`/books/tag/${encodeURIComponent(tag)}`, { params: { page, size } })
}

/** 图书入库（管理员） */
export function createBook(data: {
  title: string
  author?: string
  coverUrl?: string
  description?: string
  format: string
  fileUrl?: string
  fileSize?: number
  formatTags?: string
  totalUnits?: number
}) {
  return request.post<Book>('/books', data)
}

/** 更新格式标签（管理员） */
export function updateFormatTags(id: number, tags: string[]) {
  return request.put<Book>(`/books/${id}/tags`, { tags })
}

/** 更新图书书名（管理员） */
export function updateBookTitle(id: number, title: string) {
  return request.put<Book>(`/books/admin/${id}/title`, { title })
}

/** 更新图书作者（管理员） */
export function updateBookAuthor(id: number, author: string | null) {
  return request.put<Book>(`/books/admin/${id}/author`, { author })
}

/** 更新图书简介（管理员） */
export function updateBookDescription(id: number, description: string | null) {
  return request.put<Book>(`/books/admin/${id}/description`, { description })
}

/** 扫描图书（管理员）— SSE 流式返回进度 */
export function scanBooksStream(
  onProgress: (data: ScanProgress) => void,
  onDone: (data: ScanResult) => void,
  onError: (error: Error) => void,
  skipBeforeId?: number,
): AbortController {
  return createSseConnection<ScanProgress, ScanResult>(
    '/books/admin/scan',
    { onProgress, onDone, onError },
    { params: { skipBeforeId } },
  )
}

/** 查询扫描状态及进度 */
export function getScanStatus() {
  return request.get<{
    scanning: boolean
    current: number
    total: number
    added: number
    updated: number
    skipped: number
    failed: number
    errors: ScanError[]
    currentFile: string
  }>('/books/admin/scan/status')
}

/** 重置扫描状态 */
export function resetScanStatus() {
  return request.post('/books/admin/scan/reset')
}

/** 上传图书（管理员） */
export function uploadBook(file: File, title?: string) {
  const formData = new FormData()
  formData.append('file', file)
  if (title) formData.append('title', title)
  return request.post<Book>('/books/admin/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
  })
}


/** 用户评分 */
export function rateBook(id: number, rating: number) {
  return request.post<Book>(`/books/${id}/rate`, { rating })
}

/** 更新图书封面（管理员） */
export function updateBookCover(id: number, file: File) {
  const formData = new FormData()
  formData.append('cover', file)
  return request.post<Book>(`/books/admin/${id}/cover`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 60000,
  })
}

/** 推荐结果项 */
export interface RecommendedItem {
  bookId: number
  title: string
  author: string | null
  coverUrl: string | null
  format: string
  rating: number | null
  description: string | null
  matchScore: number
  readCount: number | null
  formatTags: string | null
  fileSize: number | null
  ruleScore: number
  vectorScore: number
  collabScore: number
  recommendedAt: string
}

/** 获取个性化推荐（从 Redis Sorted Set 取 top N） */
export function getRecommendations(count = 10) {
  return request.get<RecommendedItem[]>('/recommend', { params: { count } })
}

/** 推荐分页结果 */
export interface RecommendPageResult {
  list: RecommendedItem[]
  total: number
  page: number
  size: number
}

/** 分页查询推荐结果（从 Redis Sorted Set） */
export function getRecommendationsPage(page = 1, size = 10) {
  return request.get<RecommendPageResult>('/recommend/page', { params: { page, size } })
}

/** 推荐生成进度 */
export interface RecommendProgress {
  stage: string
  message: string
  progress: number
  current?: number
  total?: number
}

/** SSE 流式生成推荐（带进度报告） */
export function generateRecommendationsStream(
  onProgress: (data: RecommendProgress) => void,
  onDone: (data: RecommendedItem[]) => void,
  onError: (error: Error) => void,
): AbortController {
  const controller = new AbortController()
  const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'
  const url = `${BASE_URL}/recommend/generate`

  async function connect() {
    const token = getAccessToken()

    try {
      const response = await fetch(url, {
        method: 'GET',
        headers: {
          'Authorization': token ? `Bearer ${token}` : '',
          'Accept': 'text/event-stream',
        },
        signal: controller.signal,
      })

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }

      const reader = response.body?.getReader()
      if (!reader) throw new Error('No readable stream')

      const decoder = new TextDecoder()
      let buffer = ''
      let receivedDone = false

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        let currentEvent = ''
        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            const data = line.slice(5).trim()
            if (currentEvent === 'progress') {
              try {
                onProgress(JSON.parse(data))
              } catch { /* ignore */ }
            } else if (currentEvent === 'done') {
              receivedDone = true
              try {
                onDone(JSON.parse(data))
              } catch {
                onDone([])
              }
            } else if (currentEvent === 'error') {
              try {
                const err = JSON.parse(data)
                onError(new Error(err.message || err || '推荐生成失败'))
              } catch {
                onError(new Error(data || '推荐生成失败'))
              }
            }
            currentEvent = ''
          }
        }
      }

      if (!receivedDone) {
        onError(new Error('推荐生成异常结束'))
      }
    } catch (err: unknown) {
      if (err instanceof Error && err.name !== 'AbortError') {
        onError(err)
      }
    }
  }

  connect()
  return controller
}

/** 清除推荐缓存 */
export function clearRecommendCache() {
  return request.delete('/recommend/cache')
}

/** 批量获取规则匹配分（轻量级，基于用户画像） */
export function getMatchScores(bookIds: number[]) {
  return request.get<Record<string, number>>('/recommend/match-scores', {
    params: { bookIds: bookIds.join(',') },
  })
}

// ==================== 管理员AI图书操作 ====================

/** 按作者删除所有书籍（全链路：DB+缓存+RAG+ES+封面） */
export function deleteBooksByAuthor(author: string) {
  return request.delete<{ deletedCount: number; author: string }>('/books/admin/delete-by-author', { params: { author } })
}

/** 合并同名书籍（以EPUB为主，其他格式数据迁移后删除） */
export function mergeBooksByTitle(title: string) {
  return request.post<{ message: string; title: string }>('/books/admin/merge-by-title', null, { params: { title } })
}

// ==================== 内容向量管理 ====================

/** 内容向量统计信息 */
export interface EmbeddingStats {
  totalBooks: number
  embeddedBooks: number
  notEmbeddedBooks: number
  totalContentVectors: number
}

/** 获取内容向量统计 */
export function getEmbeddingStats() {
  return request.get<EmbeddingStats>('/books/admin/embeddings/stats')
}

/** 清空内容向量库（kbook_content） */
export function clearContentVectors() {
  return request.post<{ deletedCount: number; message: string }>('/books/admin/vector/clear-content')
}

/** ES 索引重建进度 */
export interface EsReindexProgress {
  current: number
  total: number
  status: 'scanning' | 'completed'
}

/** ES 索引重建结果 */
export interface EsReindexResult {
  elapsed: number
}

/** 全量重建 ES 索引 — SSE 流式返回进度 */
export function rebuildEsIndexStream(
  onProgress: (data: EsReindexProgress) => void,
  onDone: (data: EsReindexResult) => void,
  onError: (error: Error) => void,
): AbortController {
  return createSseConnection<EsReindexProgress, EsReindexResult>(
    '/books/admin/es/reindex',
    { onProgress, onDone, onError },
  )
}


