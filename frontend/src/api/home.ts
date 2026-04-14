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

/** 格式分类 */
export interface FormatCategory {
  format: string
  label: string
  icon: string
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
  categories: FormatCategory[]
}

/** 获取首页全部数据 */
export function getHomeData() {
  return request.get<HomeData>('/home')
}
