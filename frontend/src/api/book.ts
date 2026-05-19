import request from '@/utils/request'
import { createSseConnection } from '@/utils/sse-request'
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

/** 重新解析图书（管理员） */
export function reparseBook(id: number) {
  return request.post<Book>(`/books/admin/${id}/reparse`)
}

/** 用户评分 */
export function rateBook(id: number, rating: number) {
  return request.post<Book>(`/books/${id}/rate`, { rating })
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
  ruleScore: number
  vectorScore: number
  collabScore: number
  recommendedAt: string
}

/** 获取个性化推荐 */
export function getRecommendations(count = 10) {
  return request.get<RecommendedItem[]>('/recommend', { params: { count } })
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

// ==================== RAG 向量命中统计 ====================

/** RAG 命中统计 */
export interface RagStats {
  bookId: number
  hits: number
  misses: number
  totalQueries: number
  hitRate: number
}

/** 低命中率图书 */
export interface LowHitBook {
  bookId: number
  hits: number
  misses: number
  totalQueries: number
  missRate: number
  firstMissAt?: string
}

/** 获取单本书的向量命中统计 */
export function getRagStats(bookId: number) {
  return request.get<RagStats>(`/books/admin/rag-stats/${bookId}`)
}

/** 获取未命中率最高的书籍列表 */
export function getLowHitBooks(topN = 20) {
  return request.get<LowHitBook[]>('/books/admin/rag-stats/low-hit', { params: { topN } })
}

/** 清除某本书的命中统计 */
export function clearRagStats(bookId: number) {
  return request.post<null>(`/books/admin/rag-stats/${bookId}/clear`)
}

/** 手动触发单本图书全文重新向量化 */
export function reEmbedBook(bookId: number) {
  return request.post<{ bookId: number; chunks: number; status: string }>(`/books/admin/rag-stats/${bookId}/re-embed`)
}
