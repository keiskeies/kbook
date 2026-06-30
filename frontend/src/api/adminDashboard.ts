import request from '@/utils/request'

export interface DashboardOverview {
  totalUsers: number
  weeklyNewUsers: number
  weeklyActiveUsers: number
  totalBooks: number
  embeddedBooks: number
}

export interface FeatureCount {
  name: string
  count: number
}

export interface DailyTrend {
  date: string
  chatCount: number
  debateCount: number
  roundTableCount: number
}

export interface FeatureUsage {
  features: FeatureCount[]
  trend: DailyTrend[]
  avgChatRounds: number
  debateCompletionRate: number
}

export interface BookItem {
  id: number
  title: string
  author: string
  discussionCount: number
  rating: number
}

export interface DebateTopic {
  topic: string
  count: number
}

export interface ContentHeat {
  hotBooks: BookItem[]
  hotDebateTopics: DebateTopic[]
}

export interface TokenByFeature {
  name: string
  tokens: number
}

export interface CostMonitor {
  totalTokens: number
  weeklyTokens: number
  byFeature: TokenByFeature[]
}

export interface UserProfile {
  mbtiDistribution: Record<string, number>
  genderDistribution: Record<string, number>
  statusDistribution: Record<string, number>
}

export interface DashboardData {
  overview: DashboardOverview
  featureUsage: FeatureUsage
  contentHeat: ContentHeat
  costMonitor: CostMonitor
  userProfile: UserProfile
}

/** 获取仪表盘数据 */
export function getDashboard() {
  return request.get<DashboardData>('/admin/dashboard')
}
