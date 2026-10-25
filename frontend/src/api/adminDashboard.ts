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

/** 读者口径：三个并列视角，彼此没有包含关系 */
export type ReaderBasis = 'READ' | 'TOUCH' | 'DEEP'

export interface BookProfileBucket {
  /** DB 存储值，反查按它下钻；并桶「其他」为 null */
  value: string | null
  label: string
  count: number
  /** 占比分母为该维度有效样本；单值维度各桶之和为 1，多值维度可 > 1 */
  ratio: number
  siteRatio: number | null
  lift: number | null
  significant: boolean
}

export interface BookProfileDimension {
  key: string
  label: string
  /** 有效样本 / 读者总数 */
  coverage: number
  unknownCount: number
  dirtyCount: number
  /** 逗号多值：一人进多桶，占比之和可 > 100% */
  multiValue: boolean
  /** 是否可作为反查榜单的维度 */
  queryable: boolean
  buckets: BookProfileBucket[]
}

export interface BookProfile {
  bookId: number
  bookTitle: string
  basis: ReaderBasis
  rangeDays: number
  rangeStart: string | null
  rangeEnd: string | null
  readers: number
  /** 小样本抑制阈值，由后端配置下发 */
  minCohortSize: number
  sampleProtected: boolean
  profiledUsers: number
  siteReaders: number
  basisNote: string
  dimensions: BookProfileDimension[]
  highlights: string[]
}

/** 单书读者画像；days=7/30/90，0 表示全部；传 start/end 时使用自定义区间 */
export function getBookProfile(params: DashboardQuery & { bookId: number; basis?: ReaderBasis }) {
  return request.get<BookProfile>('/admin/dashboard/book-profile', { params })
}

export type AudienceFitVerdict = 'ALIGNED' | 'MISMATCH' | 'NOT_ENOUGH_SAMPLE' | 'NO_DATA'

export interface BookAudienceFitItem {
  dimKey: string
  dimLabel: string
  /** DB 存储值 */
  token: string
  tokenLabel: string
  /** 不足 min-bucket-size 时 count/ratio/lift/significant 一起不发布（§4 规则 5） */
  count: number | null
  ratio: number | null
  siteRatio: number | null
  lift: number | null
  /** 零人或全站该桶为 0 时恒为 false（Wilson 下界的数值效应，见附录 Z.3） */
  significant: boolean
  verdict: AudienceFitVerdict
  /** 主导桶：三者一起有值、或一起为 null。不足 min-bucket-size 不发布（§4 规则 5） */
  topTokenLabel: string | null
  topCount: number | null
  topRatio: number | null
}

/** P3 单书版：书侧声称受众 vs 实际读者分布（口径见附录 Z） */
export interface BookAudienceFit {
  bookId: number
  bookTitle: string
  basis: ReaderBasis
  rangeDays: number
  rangeStart: string | null
  rangeEnd: string | null
  readers: number
  minCohortSize: number
  sampleProtected: boolean
  basisNote: string
  claimedTags: number
  claimedAudience: number
  interestTags: number
  genericTags: number
  noBucketTags: number
  unrecognizedTags: number
  fits: BookAudienceFitItem[]
}

/** 单书受众吻合度；与 book-profile 共用 reader 池与阈值，同一 bookId 两处 readers 必然相同 */
export function getBookAudienceFit(params: DashboardQuery & { bookId: number; basis?: ReaderBasis }) {
  return request.get<BookAudienceFit>('/admin/dashboard/book-audience-fit', { params })
}

/** 单书画像趋势的一日一点；分布不在这里，按日取 book-profile */
export interface BookProfileTrendPoint {
  date: string
  readers: number
  profiledUsers: number
  sampleProtected: boolean
}

/** 每日快照的一次执行结果（每个读者口径一条；written=0 而 updated=当天候选数 说明幂等命中） */
export interface BookProfileSnapshotResult {
  date: string
  basis: string
  candidates: number
  written: number
  updated: number
}

export function getBookProfileTrend(params: { bookId: number; basis?: ReaderBasis; days?: number }) {
  return request.get<BookProfileTrendPoint[]>('/admin/dashboard/book-profile-trend', { params })
}

/** 手工触发快照；date 不传即今天。与定时任务同一条路径，重复执行幂等 */
export function runBookProfileSnapshot(date?: string) {
  return request.post<BookProfileSnapshotResult[]>(
    `/admin/dashboard/book-profile-snapshot${date ? `?date=${encodeURIComponent(date)}` : ''}`,
  )
}

export interface ProfileBook {
  bookId: number
  title: string
  author: string
  /** 属于该分桶且与该书有对应口径行为的读者数 */
  count: number
  /** 同口径下该书的总读者数 */
  readers: number
  /** count / readers */
  ratio: number
  siteRatio: number | null
  lift: number | null
}

export type ProfileSortBy = 'READERS' | 'LIFT'

/** 全站基线的维度与分桶：人群选书页的选择器数据源，与反查共用同一套归一化口径 */
export function getProfileDimensions() {
  return request.get<BookProfileDimension[]>('/admin/dashboard/profile-dimensions')
}

/**
 * 画像反查：某人群读得最多（READERS）或最集中（LIFT）的书。
 * `value` 必须是 DB 存储值（如 FEMALE），传展示名后端返回空榜。
 */
export function getProfileBooks(params: DashboardQuery & {
  dim: string
  value: string
  basis?: ReaderBasis
  limit?: number
  sortBy?: ProfileSortBy
}) {
  return request.get<ProfileBook[]>('/admin/dashboard/profile-books', { params })
}
export function updateAiBudget(data: {
  monthlyBudget?: number
  enabled?: boolean
  alertPercent?: number
}) {
  return request.put<AiBudget>('/admin/dashboard/budget', data)
}
