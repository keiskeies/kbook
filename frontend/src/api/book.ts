import request from '@/utils/request'
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
  page?: number
  size?: number
}) {
  return request.get<PageResult<Book>>('/books/search', { params })
}

/** 阅读排行 */
export function getReadRank(page = 1, size = 20) {
  return request.get<PageResult<Book>>('/books/rank/read', { params: { page, size } })
}

/** 评分排行 */
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
): AbortController {
  const controller = new AbortController()
  const token = localStorage.getItem(import.meta.env.VITE_TOKEN_KEY || 'kbook_token')
  const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api'

  fetch(`${baseUrl}/books/admin/scan`, {
    method: 'GET',
    headers: {
      'Authorization': token ? `Bearer ${token}` : '',
      'Accept': 'text/event-stream',
    },
    signal: controller.signal,
  })
    .then(async (response) => {
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
            try {
              const parsed = JSON.parse(data)
              if (currentEvent === 'progress') {
                onProgress(parsed)
              } else if (currentEvent === 'done') {
                receivedDone = true
                onDone(parsed)
              } else if (currentEvent === 'error') {
                onError(new Error(parsed.message || parsed || '扫描出错'))
              }
            } catch {
              // 非 JSON 数据忽略
            }
            currentEvent = ''
          }
        }
      }
      // SSE 流结束但未收到 done 事件，通知上层（轮询后备会接管）
      if (!receivedDone) {
        onError(new Error('SSE 流异常结束'))
      }
    })
    .catch((err) => {
      if (err.name !== 'AbortError') {
        onError(err)
      }
    })

  return controller
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

// ==================== 管理员AI图书操作 ====================

/** 按作者删除所有书籍（全链路：DB+缓存+RAG+ES+封面） */
export function deleteBooksByAuthor(author: string) {
  return request.delete<{ deletedCount: number; author: string }>('/books/admin/delete-by-author', { params: { author } })
}

/** 合并同名书籍（以EPUB为主，其他格式数据迁移后删除） */
export function mergeBooksByTitle(title: string) {
  return request.post<{ message: string; title: string }>('/books/admin/merge-by-title', null, { params: { title } })
}
