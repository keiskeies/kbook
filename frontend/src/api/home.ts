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
  matchScore: number
  readCount: number
}

/** 简单图书信息 */
export interface SimpleBookVO {
  id: number
  title: string
  author: string | null
  coverUrl: string | null
  format: string
  rating: number
  readCount: number
}

/** 热门标签 */
export interface TagStat {
  name: string
  count: number
}

/** 首页数据 */
export interface HomeData {
  stats: ReadingStatsVO
  recentBooks: RecentBookVO[]
  personalizedBooks: RecommendedBook[]
  topRatedBooks: SimpleBookVO[]
  newBooks: SimpleBookVO[]
  popularBooks: SimpleBookVO[]
  categories: TagStat[]
}

/** 获取首页全部数据（兼容旧接口） */
export function getHomeData() {
  return request.get<HomeData>('/home')
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

/** 获取高分佳作 */
export function getHomeTopRated() {
  return request.get<SimpleBookVO[]>('/home/top-rated')
}

/** 获取新书速递 */
export function getHomeNewBooks() {
  return request.get<SimpleBookVO[]>('/home/new-books')
}

/** 获取热门榜单 */
export function getHomePopular() {
  return request.get<SimpleBookVO[]>('/home/popular')
}

/** 获取热门标签 */
export function getHomeCategories() {
  return request.get<TagStat[]>('/home/categories')
}
