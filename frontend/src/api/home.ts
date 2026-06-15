import request from '@/utils/request'

/** 阅读统计 */
export interface ReadingStatsVO {
  totalBooks: number
  completedBooks: number
  readingBooks: number
}

/** 最近阅读 */
export interface RecentBookVO {
  bookId: number
  title: string
  author: string | null
  coverUrl: string | null
  format: string
  progress: number
  lastReadAt: string | null
}

/** 推荐图书（含匹配度） */
export interface RecommendedBook {
  id: number
  title: string
  author: string | null
  coverUrl: string | null
  format: string
  rating: number
  description: string | null
  formatTags: string | null
  matchScore: number
  readCount: number
}

/** 热门标签 */
export interface TagStat {
  name: string
  count: number
}

/** 获取阅读统计 */
export function getHomeStats() {
  return request.get<ReadingStatsVO>('/home/stats')
}

/** 获取最近阅读 */
export function getHomeRecent() {
  return request.get<RecentBookVO[]>('/home/recent')
}

/** 获取猜你喜欢 */
export function getHomePersonalized() {
  return request.get<RecommendedBook[]>('/home/personalized')
}

/** 获取热门标签 */
export function getHomeCategories() {
  return request.get<TagStat[]>('/home/categories')
}

/** 获取筛选标签（更多） */
export function getHomeTags() {
  return request.get<TagStat[]>('/home/tags')
}
