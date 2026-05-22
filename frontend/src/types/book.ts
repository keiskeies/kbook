/** 图书信息 */
export interface Book {
  id: number
  title: string
  author: string | null
  coverUrl: string | null
  description: string | null
  format: 'TXT' | 'EPUB' | 'PDF'
  fileUrl: string | null
  fileSize: number | null
  formatTags: string | null
  totalUnits: number | null
  readCount: number
  rating: number
  contentEmbedded: boolean | null
  createdAt: string
}

/** 书架项（含图书详情+阅读进度） */
export interface BookshelfItem {
  bookshelfId: number
  bookId: number
  title: string
  author: string | null
  coverUrl: string | null
  format: 'TXT' | 'EPUB' | 'PDF'
  formatTags: string | null
  fileSize: number | null
  progress: number
  currentPosition: string | null
  lastReadAt: string | null
  addedAt: string
  rating: number
  matchScore: number
}

/** 阅读进度 */
export interface ReadingProgress {
  id: number
  userId: number
  bookId: number
  progress: number
  currentPosition: string | null
  updatedAt: string
  userRating?: number | null
}

/** 阅读统计 */
export interface ReadingStats {
  totalBooks: number
  completedBooks: number
  readingBooks: number
}

/** 批量进度上报项 */
export interface ProgressBatchItem {
  bookId: number
  progress: number
  currentPosition: string | null
  clientTimestamp: string | null
}

/** 格式标签解析 */
export function parseFormatTags(tags: string | null): string[] {
  if (!tags) return []
  try {
    return JSON.parse(tags)
  } catch {
    return []
  }
}

/** 文件大小格式化 */
export function formatFileSize(bytes: number | null): string {
  if (bytes == null) return ''
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

/** 进度百分比格式化 */
export function formatProgress(progress: number): string {
  return Math.round(progress * 100) + '%'
}
