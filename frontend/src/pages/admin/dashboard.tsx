import { ArrowLeft, Users, BookOpen, MessageSquare, Swords,
  TrendingUp, TrendingDown, Activity, BarChart3, RefreshCw, Pencil,
  ChevronDown, Lightbulb, CalendarDays,
} from 'lucide-react'
import { useState } from 'react'
import {
  PieChart, Pie, Cell, BarChart, Bar, XAxis, YAxis, Tooltip,
  ResponsiveContainer, Legend, CartesianGrid, ComposedChart, Area, Line,
} from 'recharts'
import { useGoBack } from '@/hooks/useGoBack'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { getDashboard, updateAiBudget,
  type AiBudget, type DashboardQuery, type DashboardRange, type UsageBreakdown,
} from '@/api/adminDashboard'

const COLORS = {
  chat: '#5B8C5A',
  debate: '#D4A574',
  roundTable: '#6B8FA8',
  info: '#9A948A',
}

const RANGE_OPTIONS: { value: DashboardRange; label: string }[] = [
  { value: 7, label: '近7天' },
  { value: 30, label: '近30天' },
  { value: 90, label: '近90天' },
  { value: 0, label: '全部' },
]

type CostDimension = 'scene' | 'model' | 'user' | 'book'
/** 与后端 AdminDashboardService.MAX_CUSTOM_RANGE_DAYS 保持一致 */
const MAX_CUSTOM_RANGE_DAYS = 366
type RangeSelection =
  | { kind: 'preset'; days: DashboardRange }
  | { kind: 'custom'; start: string; end: string }

function formatNumber(n: number): string {
  if (n >= 10000) return (n / 10000).toFixed(1) + 'w'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k'
  return String(n)
}

function formatTokens(n: number): string {
  if (n >= 1_000_000_000) return (n / 1_000_000_000).toFixed(1) + 'B'
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K'
  return String(n)
}

function formatMoney(n: number): string {
  if (!n) return '¥0'
  if (n >= 10000) return '¥' + (n / 10000).toFixed(2) + 'w'
  if (n >= 1) return '¥' + n.toFixed(2)
  return '¥' + n.toFixed(4)
}

function formatPercent(n: number): string {
  return (n * 100).toFixed(1) + '%'
}

function toIsoDate(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

function isoDaysAgo(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() - days)
  return toIsoDate(d)
}

const BUDGET_STATUS_META: Record<string, { label: string; color: string; bar: string }> = {
  DISABLED: { label: '未启用预算', color: 'text-muted-foreground', bar: 'bg-muted-foreground' },
  OK: { label: '预算正常', color: 'text-success', bar: 'bg-success' },
  WARNING: { label: '接近预算', color: 'text-warning', bar: 'bg-warning' },
  EXCEEDED: { label: '已超预算', color: 'text-danger', bar: 'bg-danger' },
}

export default function AdminDashboardPage() {
  const goBack = useGoBack()
  const [range, setRange] = useState<RangeSelection>({ kind: 'preset', days: 7 })
  const [customOpen, setCustomOpen] = useState(false)
  const [customStart, setCustomStart] = useState(() => isoDaysAgo(29))
  const [customEnd, setCustomEnd] = useState(() => toIsoDate(new Date()))
  const [customError, setCustomError] = useState('')
  const [costDimension, setCostDimension] = useState<CostDimension>('scene')
  const [budgetOpen, setBudgetOpen] = useState(false)
  const [budgetSaving, setBudgetSaving] = useState(false)
  const [budgetError, setBudgetError] = useState('')
  const [budgetForm, setBudgetForm] = useState({
    monthlyBudget: '',
    enabled: true,
    alertPercent: 80,
  })

  const queryParams: DashboardQuery = range.kind === 'preset'
    ? { days: range.days }
    : { start: range.start, end: range.end }
  const queryKey = range.kind === 'preset'
    ? ['admin', 'dashboard', 'preset', range.days]
    : ['admin', 'dashboard', 'custom', range.start, range.end]

  const { data, isLoading, isFetching, isError, error, refetch, dataUpdatedAt } = useQuery({
    queryKey,
    queryFn: () => getDashboard(queryParams),
    placeholderData: keepPreviousData,
    refetchInterval: 5 * 60 * 1000,
  })

  if (isError && !data) {
    return (
      <div className="absolute inset-0 flex flex-col overflow-hidden bg-background">
        <header className="shrink-0 flex items-center gap-3 border-b bg-navbar/95 px-4 md:px-6 py-3 backdrop-blur z-20">
          <button onClick={goBack} className="rounded-full p-1.5 active:bg-muted">
            <ArrowLeft className="h-5 w-5" />
          </button>
          <h1 className="text-h4">数据看板</h1>
        </header>
        <div className="flex flex-1 flex-col items-center justify-center gap-3 px-6 text-center">
          <p className="text-sm text-muted-foreground">
            {error instanceof Error ? error.message : '加载失败'}
          </p>
          <button
            onClick={() => refetch()}
            className="rounded-full bg-primary px-4 py-1.5 text-xs font-medium text-primary-foreground"
          >
            重试
          </button>
        </div>
      </div>
    )
  }

  if (isLoading && !data) {
    return (
      <div className="absolute inset-0 flex flex-col overflow-hidden bg-background">
        <header className="shrink-0 flex items-center gap-3 border-b bg-navbar/95 px-4 md:px-6 py-3 backdrop-blur z-20">
          <button onClick={goBack} className="rounded-full p-1.5 active:bg-muted">
            <ArrowLeft className="h-5 w-5" />
          </button>
          <h1 className="text-h4">数据看板</h1>
        </header>
        <div className="flex flex-1 items-center justify-center">
          <div className="flex flex-col items-center gap-3 text-muted-foreground">
            <RefreshCw className="h-6 w-6 animate-spin" />
            <span className="text-caption">加载中...</span>
          </div>
        </div>
      </div>
    )
  }

  if (!data) return null

  const { overview, featureUsage, contentHeat, costMonitor, userProfile, insights } = data
  const rangeText = range.kind === 'preset'
    ? RANGE_OPTIONS.find(o => o.value === range.days)?.label ?? '近7天'
    : '自定义区间'
  const rangeLabel = range.kind === 'preset'
    ? rangeText
    : `${range.start.slice(5)} ~ ${range.end.slice(5)}`
  const trendSuffix = featureUsage.trendGranularity === 'WEEK'
    ? '（按周）'
    : featureUsage.trendGranularity === 'MONTH' ? '（按月）' : ''
  const trendTitle = featureUsage.trendGranularity === 'MONTH' && range.kind === 'preset' && range.days === 0
    ? '近12个月趋势（按月）'
    : `${rangeText}趋势${trendSuffix}`

  const budgetMeta = BUDGET_STATUS_META[costMonitor.budget.status] ?? BUDGET_STATUS_META.DISABLED
  const costRows: Record<CostDimension, UsageBreakdown[]> = {
    scene: costMonitor.byScene,
    model: costMonitor.byModel,
    user: costMonitor.topUsers,
    book: costMonitor.topBooks,
  }
  const currentCostRows = (costRows[costDimension] ?? []).slice(0, 10)
  const budgetProgress = Math.min(100, Math.max(0, costMonitor.budget.usedPercent))
  const updatedText = dataUpdatedAt
    ? new Date(dataUpdatedAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    : '--:--'

  const applyCustomRange = () => {
    if (!customStart || !customEnd) {
      setCustomError('请选择开始和结束日期')
      return
    }
    if (customStart > customEnd) {
      setCustomError('开始日期不能晚于结束日期')
      return
    }
    const spanDays = Math.round(
      (new Date(customEnd + 'T00:00:00').getTime() - new Date(customStart + 'T00:00:00').getTime()) / 86400000,
    ) + 1
    if (spanDays > MAX_CUSTOM_RANGE_DAYS) {
      setCustomError(`自定义区间最长支持 ${MAX_CUSTOM_RANGE_DAYS} 天，全部数据请选择「全部」`)
      return
    }
    setCustomError('')
    setCustomOpen(false)
    setRange({ kind: 'custom', start: customStart, end: customEnd })
  }

  const openBudgetDialog = () => {
    const b: AiBudget = costMonitor.budget
    setBudgetForm({
      monthlyBudget: b.monthlyBudget ? String(b.monthlyBudget) : '',
      enabled: b.enabled,
      alertPercent: b.alertPercent || 80,
    })
    setBudgetError('')
    setBudgetOpen(true)
  }

  const saveBudget = async () => {
    setBudgetSaving(true)
    setBudgetError('')
    try {
      await updateAiBudget({
        monthlyBudget: Number(budgetForm.monthlyBudget) || 0,
        enabled: budgetForm.enabled,
        alertPercent: Number(budgetForm.alertPercent) || 80,
      })
      setBudgetOpen(false)
      refetch()
    } catch (err: unknown) {
      setBudgetError(err instanceof Error ? err.message : '保存失败')
    } finally {
      setBudgetSaving(false)
    }
  }

  // 功能使用环形图
  const featurePieData = featureUsage.features.map(f => ({ name: f.name, value: f.count }))
  const featureTotal = featureUsage.features.reduce((sum, f) => sum + f.count, 0)

  // 趋势堆叠面积：额外算总量线
  const trendData = featureUsage.trend.map(t => ({
    ...t,
    total: t.chatCount + t.debateCount + t.roundTableCount,
  }))

  // MBTI 排序条形图
  const mbtiData = Object.entries(userProfile.mbtiDistribution)
    .map(([k, v]) => ({ name: k, value: v }))
    .sort((a, b) => b.value - a.value)
  const mbtiChartHeight = Math.max(220, mbtiData.length * 22)

  const genderData = Object.entries(userProfile.genderDistribution).map(([k, v]) => ({
    name: k === 'MALE' ? '男' : k === 'FEMALE' ? '女' : k,
    value: v,
  }))

  return (
    <div className="absolute inset-0 flex flex-col overflow-hidden bg-background">
      {/* Header */}
      <header className="shrink-0 flex items-center gap-3 border-b bg-navbar/95 px-4 md:px-6 py-3 backdrop-blur z-20">
        <button onClick={goBack} className="rounded-full p-1.5 active:bg-muted">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="text-h4">数据看板</h1>
        <div className="ml-auto flex items-center gap-2">
          <span className="hidden text-[11px] text-muted-foreground sm:inline">
            {isFetching ? '更新中…' : `更新于 ${updatedText}`}
          </span>
          <button
            onClick={() => refetch()}
            className="flex items-center gap-1.5 rounded-full bg-muted px-3 py-1.5 text-xs font-medium text-muted-foreground hover:bg-muted/80"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${isFetching ? 'animate-spin' : ''}`} />
            刷新
          </button>
        </div>
      </header>

      {/* 时间范围筛选 */}
      <div className="shrink-0 border-b bg-navbar/95 px-4 md:px-6 py-2 backdrop-blur z-10">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-1.5">
          {RANGE_OPTIONS.map(opt => (
            <button
              key={opt.value}
              onClick={() => {
                setRange({ kind: 'preset', days: opt.value })
                setCustomOpen(false)
                setCustomError('')
              }}
              className={`shrink-0 rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                range.kind === 'preset' && range.days === opt.value
                  ? 'bg-primary text-primary-foreground'
                  : 'bg-muted text-muted-foreground hover:bg-muted/80'
              }`}
            >
              {opt.label}
            </button>
          ))}
          <button
            onClick={() => setCustomOpen(v => !v)}
            className={`flex shrink-0 items-center gap-1 rounded-full px-3 py-1 text-xs font-medium transition-colors ${
              range.kind === 'custom'
                ? 'bg-primary text-primary-foreground'
                : 'bg-muted text-muted-foreground hover:bg-muted/80'
            }`}
          >
            <CalendarDays className="h-3 w-3" />
            {range.kind === 'custom' ? rangeLabel : '自定义'}
          </button>
          {range.kind === 'custom' && (
            <span className="text-[11px] text-muted-foreground">共 {data.rangeDays} 天</span>
          )}
          <span className="ml-auto hidden shrink-0 text-[11px] text-muted-foreground sm:inline">
            除累计值外，统计均按所选范围
          </span>
        </div>

        {customOpen && (
          <div className="mx-auto mt-2 flex max-w-5xl flex-wrap items-center gap-2">
            <input
              type="date"
              value={customStart}
              max={customEnd}
              onChange={(e) => setCustomStart(e.target.value)}
              className="rounded-lg border border-border bg-background px-2 py-1 text-xs outline-none focus:border-primary"
            />
            <span className="text-xs text-muted-foreground">至</span>
            <input
              type="date"
              value={customEnd}
              min={customStart}
              max={toIsoDate(new Date())}
              onChange={(e) => setCustomEnd(e.target.value)}
              className="rounded-lg border border-border bg-background px-2 py-1 text-xs outline-none focus:border-primary"
            />
            <button
              onClick={applyCustomRange}
              className="rounded-full bg-primary px-3 py-1 text-xs font-medium text-primary-foreground hover:bg-primary/90"
            >
              应用
            </button>
            <button
              onClick={() => { setCustomOpen(false); setCustomError('') }}
              className="rounded-full bg-muted px-3 py-1 text-xs font-medium text-muted-foreground hover:bg-muted/80"
            >
              取消
            </button>
            {customError && <span className="text-caption text-danger">{customError}</span>}
          </div>
        )}
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto overscroll-contain">
        <div className="mx-auto max-w-5xl px-4 md:px-6 py-5 space-y-5">

          {/* ===== 自动洞察 ===== */}
          {insights.length > 0 && (
            <div className="rounded-xl border border-primary/15 bg-primary/5 p-3.5 space-y-1.5">
              <div className="flex items-center gap-1.5 text-xs font-medium text-primary">
                <Lightbulb className="h-3.5 w-3.5" />
                自动洞察
              </div>
              {insights.map((text, i) => (
                <p key={i} className="text-sm text-foreground/90">• {text}</p>
              ))}
            </div>
          )}

          {/* ===== 第一区：平台健康度 ===== */}
          <section className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <StatCard
              icon={<Users className="h-5 w-5" />}
              label="总用户"
              value={formatNumber(overview.totalUsers)}
              sub={`${rangeText} +${overview.newUsers}`}
              color="text-brand-500"
            />
            <StatCard
              icon={<Activity className="h-5 w-5" />}
              label={`${rangeText}活跃`}
              value={formatNumber(overview.activeUsers)}
              color="text-success"
            />
            <StatCard
              icon={<BookOpen className="h-5 w-5" />}
              label="图书总数"
              value={formatNumber(overview.totalBooks)}
              sub={`已向量化 ${overview.embeddedBooks}`}
              color="text-info"
            />
            <StatCard
              icon={<MessageSquare className="h-5 w-5" />}
              label={`${rangeText} Token`}
              value={formatTokens(costMonitor.rangeTokens)}
              color="text-warning"
            />
          </section>

          {/* ===== 第二区：功能使用 ===== */}
          <CollapsibleSection
            storageKey="admin-dashboard-usage"
            icon={<BarChart3 className="h-4 w-4" />}
            title="功能使用"
          >
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {/* 功能占比环形图 */}
              <div className="rounded-lg bg-muted/50 p-4">
                <p className="text-caption text-muted-foreground mb-3">{rangeText}功能使用占比</p>
                <div className="relative h-52">
                  <ResponsiveContainer width="100%" height="100%">
                    <PieChart>
                      <Pie
                        data={featurePieData}
                        cx="50%"
                        cy="50%"
                        innerRadius={56}
                        outerRadius={82}
                        paddingAngle={3}
                        dataKey="value"
                        labelLine={false}
                      >
                        {featurePieData.map((_, i) => (
                          <Cell key={i} fill={[COLORS.chat, COLORS.roundTable, COLORS.debate][i % 3]} />
                        ))}
                      </Pie>
                      <Tooltip
                        contentStyle={{ background: 'hsl(var(--card))', border: '1px solid hsl(var(--border))', borderRadius: 8, fontSize: 12 }}
                      />
                    </PieChart>
                  </ResponsiveContainer>
                  <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                    <span className="text-xl font-semibold">{formatNumber(featureTotal)}</span>
                    <span className="text-caption text-muted-foreground">总次数</span>
                  </div>
                </div>
                <div className="mt-2 flex flex-wrap justify-center gap-x-4 gap-y-1.5">
                  {featureUsage.features.map((f, i) => (
                    <span key={f.name} className="flex items-center gap-1 text-caption text-muted-foreground">
                      <span className="h-2 w-2 rounded-full" style={{ background: [COLORS.chat, COLORS.roundTable, COLORS.debate][i % 3] }} />
                      {f.name} {f.count}
                      <DeltaBadge current={f.count} previous={f.previousCount} />
                    </span>
                  ))}
                </div>
              </div>

              {/* 趋势堆叠面积图 */}
              <div className="rounded-lg bg-muted/50 p-4">
                <p className="text-caption text-muted-foreground mb-3">{trendTitle}</p>
                <div className="h-52">
                  <ResponsiveContainer width="100%" height="100%">
                    <ComposedChart data={trendData} margin={{ top: 4, right: 4 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" vertical={false} />
                      <XAxis dataKey="date" tick={{ fontSize: 10, fill: 'hsl(var(--muted-foreground))' }} interval="preserveStartEnd" minTickGap={16} />
                      <YAxis tick={{ fontSize: 10, fill: 'hsl(var(--muted-foreground))' }} allowDecimals={false} />
                      <Tooltip
                        contentStyle={{ background: 'hsl(var(--card))', border: '1px solid hsl(var(--border))', borderRadius: 8, fontSize: 12 }}
                      />
                      <Legend wrapperStyle={{ fontSize: 11 }} />
                      <Area type="monotone" dataKey="chatCount" name="问答" stackId="1" stroke={COLORS.chat} fill={COLORS.chat} fillOpacity={0.55} />
                      <Area type="monotone" dataKey="debateCount" name="辩论" stackId="1" stroke={COLORS.debate} fill={COLORS.debate} fillOpacity={0.55} />
                      <Area type="monotone" dataKey="roundTableCount" name="圆桌" stackId="1" stroke={COLORS.roundTable} fill={COLORS.roundTable} fillOpacity={0.55} />
                      <Line type="monotone" dataKey="total" name="总次数" stroke={COLORS.info} strokeWidth={1.5} dot={false} />
                    </ComposedChart>
                  </ResponsiveContainer>
                </div>
              </div>
            </div>

            <div className="flex flex-wrap gap-4 text-sm text-muted-foreground pt-1">
              <span>
                平均对话轮数：
                <span className="font-medium text-foreground">{featureUsage.avgChatRounds}</span>
              </span>
              <span>
                辩论完成率：
                <span className="font-medium text-foreground">
                  {(featureUsage.debateCompletionRate * 100).toFixed(1)}%
                </span>
              </span>
            </div>
          </CollapsibleSection>

          {/* ===== 第三区：内容热度 ===== */}
          <CollapsibleSection
            storageKey="admin-dashboard-heat"
            icon={<TrendingUp className="h-4 w-4" />}
            title="内容热度"
          >
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {/* 图书问答热度 */}
              <div className="rounded-lg bg-muted/50 p-4">
                <div className="mb-3 flex items-center justify-between gap-2">
                  <p className="text-caption text-muted-foreground">{rangeText}图书问答热度 Top 10</p>
                  <div className="flex shrink-0 items-center gap-2 text-[10px] text-muted-foreground">
                    <span className="flex items-center gap-0.5"><span className="h-1.5 w-1.5 rounded-full" style={{ background: COLORS.chat }} />问答</span>
                    <span className="flex items-center gap-0.5"><span className="h-1.5 w-1.5 rounded-full" style={{ background: COLORS.debate }} />辩论</span>
                    <span className="flex items-center gap-0.5"><span className="h-1.5 w-1.5 rounded-full" style={{ background: COLORS.roundTable }} />圆桌</span>
                  </div>
                </div>
                <div className="space-y-2.5">
                  {contentHeat.hotBooks.length === 0 && (
                    <p className="text-caption text-muted-foreground text-center py-4">暂无数据</p>
                  )}
                  {contentHeat.hotBooks.map((book, i) => (
                    <div key={book.id} className="flex items-center gap-3">
                      <span className={`flex h-5 w-5 shrink-0 items-center justify-center rounded text-xs font-bold
                        ${i < 3 ? 'bg-primary/15 text-primary' : 'bg-muted text-muted-foreground'}`}>
                        {i + 1}
                      </span>
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-medium">{book.title}</p>
                        <p className="truncate text-caption text-muted-foreground">{book.author}</p>
                        <div className="mt-1 flex h-1.5 overflow-hidden rounded-full bg-muted">
                          <div style={{ width: `${book.discussionCount ? book.qaCount / book.discussionCount * 100 : 0}%`, background: COLORS.chat }} />
                          <div style={{ width: `${book.discussionCount ? book.debateCount / book.discussionCount * 100 : 0}%`, background: COLORS.debate }} />
                          <div style={{ width: `${book.discussionCount ? book.roundTableCount / book.discussionCount * 100 : 0}%`, background: COLORS.roundTable }} />
                        </div>
                      </div>
                      <div className="shrink-0 text-right text-caption text-muted-foreground">
                        <div>
                          <span className="font-medium text-foreground">{book.qaCount}</span> 次问答
                          <BookDelta current={book.qaCount} previous={book.previousQaCount} />
                        </div>
                        <div>共 {book.discussionCount} 次讨论</div>
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {/* 热门辩题 */}
              <div className="rounded-lg bg-muted/50 p-4">
                <p className="text-caption text-muted-foreground mb-3">{rangeText}热门辩题 Top 10</p>
                <div className="space-y-2">
                  {contentHeat.hotDebateTopics.length === 0 && (
                    <p className="text-caption text-muted-foreground text-center py-4">暂无数据</p>
                  )}
                  {contentHeat.hotDebateTopics.map((topic, i) => (
                    <div key={i} className="flex items-center gap-3">
                      <span className={`flex h-5 w-5 shrink-0 items-center justify-center rounded text-xs font-bold
                        ${i < 3 ? 'bg-warning/15 text-warning' : 'bg-muted text-muted-foreground'}`}>
                        {i + 1}
                      </span>
                      <p className="min-w-0 flex-1 text-sm truncate">{topic.topic}</p>
                      <span className="text-caption text-muted-foreground shrink-0">
                        {topic.count} 场
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </CollapsibleSection>

          {/* ===== 第四区：成本监控 ===== */}
          <CollapsibleSection
            storageKey="admin-dashboard-cost"
            icon={<Swords className="h-4 w-4" />}
            title="成本监控"
            action={(
              <button
                onClick={openBudgetDialog}
                className="flex items-center gap-1 rounded-full bg-muted px-3 py-1 text-xs font-medium text-muted-foreground hover:bg-muted/80"
              >
                <Pencil className="h-3 w-3" />
                预算设置
              </button>
            )}
          >

            {/* 费用概览 */}
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
              <CostMetric label={`${rangeText}费用`} value={formatMoney(costMonitor.rangeCost)} />
              <CostMetric label="本月费用" value={formatMoney(costMonitor.monthCost)} />
              <CostMetric label={`${rangeText}Token`} value={formatTokens(costMonitor.rangeTokens)} />
              <CostMetric label="AI 请求数" value={formatNumber(costMonitor.quality.requests)} />
            </div>

            {/* 预算进度 */}
            <div className="rounded-lg bg-muted/50 p-4 space-y-2">
              <div className="flex items-center justify-between text-sm">
                <div className="flex items-center gap-2">
                  <span className="font-medium">本月预算</span>
                  <span className={`text-xs font-medium ${budgetMeta.color}`}>{budgetMeta.label}</span>
                </div>
                <span className="text-muted-foreground">
                  {costMonitor.budget.enabled && costMonitor.budget.monthlyBudget > 0
                    ? `${formatMoney(costMonitor.budget.monthCost)} / ${formatMoney(costMonitor.budget.monthlyBudget)}`
                    : '未设置'}
                </span>
              </div>
              <div className="h-2 overflow-hidden rounded-full bg-muted">
                <div
                  className={`h-full rounded-full transition-all ${budgetMeta.bar}`}
                  style={{ width: `${budgetProgress}%` }}
                />
              </div>
              <p className="text-caption text-muted-foreground">
                {costMonitor.budget.enabled && costMonitor.budget.monthlyBudget > 0
                  ? `已使用 ${costMonitor.budget.usedPercent}%，达到 ${costMonitor.budget.alertPercent}% 时邮件告警`
                  : '启用预算后，达到阈值会向所有管理员发送邮件告警'}
              </p>
            </div>

            {/* 质量指标 */}
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
              <QualityMetric label="成功率" value={formatPercent(costMonitor.quality.successRate)} />
              <QualityMetric label="平均延迟" value={`${Math.round(costMonitor.quality.avgLatencyMs)}ms`} />
              <QualityMetric label="P95 延迟" value={`${costMonitor.quality.p95LatencyMs}ms`} />
              <QualityMetric label="截断率" value={formatPercent(costMonitor.quality.truncationRate)} />
            </div>

            {costMonitor.unpricedTokens > 0 && (
              <p className="rounded-lg bg-warning/10 px-3 py-2 text-caption text-muted-foreground">
                有 {formatTokens(costMonitor.unpricedTokens)} Token 未配置单价，费用按 0 计。请到「AI 配置」为对应模型补充输入/输出单价。
              </p>
            )}

            {costMonitor.totalTokens === 0 && (
              <p className="rounded-lg bg-warning/10 px-3 py-2 text-caption text-muted-foreground">
                Token 统计从本次更新后开始累计，历史调用没有落库、无法回溯；发起新的 AI 请求后即可看到数据。
              </p>
            )}

            {/* 四维成本明细 */}
            <div className="space-y-3">
              <div className="flex gap-1 overflow-x-auto">
                {([
                  ['scene', '按场景'],
                  ['model', '按模型'],
                  ['user', '按用户'],
                  ['book', '按图书'],
                ] as const).map(([key, label]) => (
                  <button
                    key={key}
                    onClick={() => setCostDimension(key)}
                    className={`shrink-0 rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                      costDimension === key
                        ? 'bg-primary text-primary-foreground'
                        : 'bg-muted text-muted-foreground hover:bg-muted/80'
                    }`}
                  >
                    {label}
                  </button>
                ))}
              </div>

              <div className="space-y-2">
                {currentCostRows.length === 0 && (
                  <p className="py-4 text-center text-caption text-muted-foreground">暂无数据</p>
                )}
                {currentCostRows.map((row) => (
                  <div key={`${costDimension}-${row.key}`} className="flex items-center gap-3">
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-medium">{row.label}</p>
                      {row.subLabel && (
                        <p className="truncate text-caption text-muted-foreground">{row.subLabel}</p>
                      )}
                    </div>
                    <div className="shrink-0 text-right">
                      <p className="text-sm font-medium">{formatMoney(row.cost)}</p>
                      <p className="text-caption text-muted-foreground">
                        {formatTokens(row.tokens)} tok · {row.requests} 次
                      </p>
                    </div>
                  </div>
                ))}
              </div>
              <p className="text-[11px] text-muted-foreground">
                {costMonitor.retentionDays > 0
                  ? `明细保留 ${costMonitor.retentionDays} 天，每天凌晨自动清理更早记录`
                  : '明细永久保留'}
              </p>
            </div>
          </CollapsibleSection>

          {/* ===== 第五区：用户画像 ===== */}
          <CollapsibleSection
            storageKey="admin-dashboard-user"
            icon={<Users className="h-4 w-4" />}
            title="用户画像"
          >

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {/* MBTI 排序条形图 */}
              <div className="rounded-lg bg-muted/50 p-4">
                <p className="text-caption text-muted-foreground mb-3">MBTI 分布（降序）</p>
                {mbtiData.length > 0 ? (
                  <div style={{ height: mbtiChartHeight }}>
                    <ResponsiveContainer width="100%" height="100%">
                      <BarChart data={mbtiData} layout="vertical" margin={{ left: 0, right: 12 }}>
                        <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" horizontal={false} />
                        <XAxis type="number" allowDecimals={false} tick={{ fontSize: 10, fill: 'hsl(var(--muted-foreground))' }} />
                        <YAxis type="category" dataKey="name" width={42} tick={{ fontSize: 10, fill: 'hsl(var(--muted-foreground))' }} />
                        <Tooltip
                          contentStyle={{ background: 'hsl(var(--card))', border: '1px solid hsl(var(--border))', borderRadius: 8, fontSize: 12 }}
                        />
                        <Bar dataKey="value" name="用户数" fill={COLORS.chat} radius={[0, 3, 3, 0]} />
                      </BarChart>
                    </ResponsiveContainer>
                  </div>
                ) : (
                  <p className="text-caption text-muted-foreground text-center py-8">暂无数据</p>
                )}
              </div>

              {/* 性别分布 */}
              <div className="rounded-lg bg-muted/50 p-4">
                <p className="text-caption text-muted-foreground mb-3">性别分布</p>
                <div className="h-52">
                  {genderData.length > 0 ? (
                    <ResponsiveContainer width="100%" height="100%">
                      <PieChart>
                        <Pie
                          data={genderData}
                          cx="50%"
                          cy="50%"
                          innerRadius={50}
                          outerRadius={80}
                          paddingAngle={3}
                          dataKey="value"
                        label={({ name, percent }) => percent > 0.05 ? `${name} ${(percent * 100).toFixed(0)}%` : ''}
                          labelLine={false}
                        >
                          {genderData.map((_, i) => (
                            <Cell key={i} fill={[COLORS.chat, COLORS.debate, COLORS.roundTable][i % 3]} />
                          ))}
                        </Pie>
                        <Tooltip
                          contentStyle={{ background: 'hsl(var(--card))', border: '1px solid hsl(var(--border))', borderRadius: 8, fontSize: 12 }}
                        />
                      </PieChart>
                    </ResponsiveContainer>
                  ) : (
                    <p className="text-caption text-muted-foreground text-center py-8">暂无数据</p>
                  )}
                </div>
              </div>
            </div>

            {/* 注册状态 */}
            {Object.keys(userProfile.statusDistribution).length > 0 && (
              <div className="flex flex-wrap gap-3 pt-1">
                {Object.entries(userProfile.statusDistribution).map(([status, count]) => {
                  const label = status === 'PENDING' ? '待审核'
                    : status === 'APPROVED' ? '已通过'
                    : status === 'BANNED' ? '已封禁' : status
                  const dot = status === 'APPROVED' ? 'bg-success'
                    : status === 'PENDING' ? 'bg-warning'
                    : 'bg-danger'
                  return (
                    <span key={status} className="flex items-center gap-1.5 text-sm">
                      <span className={`h-2 w-2 rounded-full ${dot}`} />
                      <span className="text-muted-foreground">{label}</span>
                      <span className="font-medium">{count}</span>
                    </span>
                  )
                })}
              </div>
            )}
          </CollapsibleSection>

          <div className="h-4" />
        </div>
      </div>

      {budgetOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
          onClick={() => setBudgetOpen(false)}
        >
          <div
            className="w-full max-w-sm rounded-xl bg-card p-5 shadow-lg"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="text-h4">AI 成本预算</h3>
            <p className="mt-1 text-caption text-muted-foreground">
              按本月已产生的费用计算，达到阈值时向所有管理员发送邮件。
            </p>

            <div className="mt-4 space-y-3">
              <div>
                <label className="mb-1 block text-xs text-muted-foreground">月度预算（元）</label>
                <input
                  type="number"
                  min={0}
                  step="0.01"
                  value={budgetForm.monthlyBudget}
                  onChange={(e) => setBudgetForm(f => ({ ...f, monthlyBudget: e.target.value }))}
                  placeholder="如: 100"
                  className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
                />
              </div>
              <div>
                <label className="mb-1 block text-xs text-muted-foreground">告警阈值（%）</label>
                <input
                  type="number"
                  min={1}
                  max={100}
                  value={budgetForm.alertPercent}
                  onChange={(e) => setBudgetForm(f => ({ ...f, alertPercent: Number(e.target.value) }))}
                  className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
                />
              </div>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={budgetForm.enabled}
                  onChange={(e) => setBudgetForm(f => ({ ...f, enabled: e.target.checked }))}
                />
                启用预算告警
              </label>
            </div>

            {budgetError && <p className="mt-3 text-caption text-danger">{budgetError}</p>}

            <div className="mt-5 flex justify-end gap-2">
              <button
                onClick={() => setBudgetOpen(false)}
                className="rounded-full bg-muted px-4 py-1.5 text-xs font-medium text-muted-foreground hover:bg-muted/80"
              >
                取消
              </button>
              <button
                onClick={saveBudget}
                disabled={budgetSaving}
                className="rounded-full bg-primary px-4 py-1.5 text-xs font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
              >
                {budgetSaving ? '保存中...' : '保存'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function CostMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg bg-muted/50 p-3 text-center">
      <p className="text-caption text-muted-foreground">{label}</p>
      <p className="mt-1 text-lg font-semibold">{value}</p>
    </div>
  )
}

function QualityMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-border px-3 py-2">
      <p className="text-caption text-muted-foreground">{label}</p>
      <p className="mt-0.5 text-sm font-medium">{value}</p>
    </div>
  )
}

function StatCard({ icon, label, value, sub, color }: {
  icon: React.ReactNode
  label: string
  value: string
  sub?: string
  color: string
}) {
  return (
    <div className="rounded-xl bg-card shadow-xs p-4 flex flex-col gap-2">
      <div className={`flex h-9 w-9 items-center justify-center rounded-lg bg-primary/10 ${color}`}>
        {icon}
      </div>
      <div>
        <p className="text-caption text-muted-foreground">{label}</p>
        <p className="text-h3 mt-0.5">{value}</p>
        {sub && <p className="text-caption text-muted-foreground mt-0.5">{sub}</p>}
      </div>
    </div>
  )
}

function CollapsibleSection({ storageKey, icon, title, action, children }: {
  storageKey: string
  icon: React.ReactNode
  title: string
  action?: React.ReactNode
  children: React.ReactNode
}) {
  const [open, setOpen] = useState(() => {
    try {
      const saved = localStorage.getItem(storageKey)
      return saved === null ? true : saved === '1'
    } catch {
      return true
    }
  })

  const toggle = () => {
    setOpen(prev => {
      const next = !prev
      try {
        localStorage.setItem(storageKey, next ? '1' : '0')
      } catch {
        // ignore storage failures
      }
      return next
    })
  }

  return (
    <section className="rounded-xl bg-card shadow-xs">
      <div className="flex items-center gap-2 px-4 py-3 md:px-5">
        <button onClick={toggle} aria-expanded={open} className="flex min-w-0 flex-1 items-center gap-2 text-left">
          <span className="text-primary">{icon}</span>
          <h2 className="text-h4">{title}</h2>
          <ChevronDown className={`h-4 w-4 shrink-0 text-muted-foreground transition-transform ${open ? 'rotate-180' : ''}`} />
        </button>
        {action}
      </div>
      {open && (
        <div className="space-y-4 px-4 pb-4 md:px-5 md:pb-5">
          {children}
        </div>
      )}
    </section>
  )
}

function DeltaBadge({ current, previous }: { current: number; previous: number }) {
  if (previous == null || previous < 0) return null
  const delta = current - previous
  if (delta === 0) {
    return <span className="text-[10px] text-muted-foreground">持平</span>
  }
  const up = delta > 0
  return (
    <span className={`flex items-center gap-0.5 text-[10px] font-medium ${up ? 'text-success' : 'text-danger'}`}>
      {up ? <TrendingUp className="h-3 w-3" /> : <TrendingDown className="h-3 w-3" />}
      {up ? '+' : ''}{delta}
    </span>
  )
}

function BookDelta({ current, previous }: { current: number; previous?: number | null }) {
  if (previous == null) return null
  const delta = current - previous
  if (delta === 0) {
    return <span className="ml-1 text-[10px] text-muted-foreground">持平</span>
  }
  const up = delta > 0
  return (
    <span className={`ml-1 inline-flex items-center text-[10px] font-medium ${up ? 'text-success' : 'text-danger'}`}>
      {up ? '↑' : '↓'}{Math.abs(delta)}
    </span>
  )
}
