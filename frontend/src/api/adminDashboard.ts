import request from '@/utils/request'

export interface DashboardOverview {
  totalUsers: number
  newUsers: number
  activeUsers: number
  totalBooks: number
  embeddedBooks: number
}

export interface FeatureCount {
  name: string
  count: number
  /** 上一周期次数；-1 表示无可比周期 */
  previousCount: number
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
  trendGranularity: 'DAY' | 'WEEK' | 'MONTH'
  avgChatRounds: number
  debateCompletionRate: number
}

export interface BookItem {
  id: number
  title: string
  author: string
  qaCount: number
  debateCount: number
  roundTableCount: number
  discussionCount: number
  /** 上一周期问答次数；null 表示无可比周期 */
  previousQaCount?: number | null
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

export interface UsageBreakdown {
  key: string
  label: string
  subLabel?: string | null
  tokens: number
  cost: number
  requests: number
}

export interface QualityMetrics {
  requests: number
  successRate: number
  avgLatencyMs: number
  p95LatencyMs: number
  truncationRate: number
}

export type BudgetStatus = 'DISABLED' | 'OK' | 'WARNING' | 'EXCEEDED'

export interface AiBudget {
  monthlyBudget: number
  enabled: boolean
  alertPercent: number
  monthCost: number
  usedPercent: number
  status: BudgetStatus
  lastAlertMonth?: string | null
}

export interface CostMonitor {
  totalTokens: number
  rangeTokens: number
  monthTokens: number
  totalCost: number
  rangeCost: number
  monthCost: number
  unpricedTokens: number
  quality: QualityMetrics
  byFeature: TokenByFeature[]
  byScene: UsageBreakdown[]
  byModel: UsageBreakdown[]
  topUsers: UsageBreakdown[]
  topBooks: UsageBreakdown[]
  budget: AiBudget
  retentionDays: number
}

export interface UserProfile {
  mbtiDistribution: Record<string, number>
  genderDistribution: Record<string, number>
  statusDistribution: Record<string, number>
}

export interface DashboardData {
  rangeDays: number
  rangeStart?: string | null
  rangeEnd?: string | null
  insights: string[]
  overview: DashboardOverview
  featureUsage: FeatureUsage
  contentHeat: ContentHeat
  costMonitor: CostMonitor
  userProfile: UserProfile
}

export type DashboardRange = 7 | 30 | 90 | 0

export interface DashboardQuery {
  days?: DashboardRange
  start?: string
  end?: string
}

/** 获取仪表盘数据；days=7/30/90，0 表示全部；传 start/end 时使用自定义区间 */
export function getDashboard(params: DashboardQuery = { days: 7 }) {
  return request.get<DashboardData>('/admin/dashboard', { params })
}

export function getAiBudget() {
  return request.get<AiBudget>('/admin/dashboard/budget')
}

export function updateAiBudget(data: {
  monthlyBudget?: number
  enabled?: boolean
  alertPercent?: number
}) {
  return request.put<AiBudget>('/admin/dashboard/budget', data)
}
