import { useState, useEffect } from 'react'
import {
  ArrowLeft, Users, BookOpen, MessageSquare, Swords,
  TrendingUp, Activity, BarChart3, RefreshCw,
} from 'lucide-react'
import {
  PieChart, Pie, Cell, BarChart, Bar, XAxis, YAxis, Tooltip,
  ResponsiveContainer, Legend, CartesianGrid,
} from 'recharts'
import { useGoBack } from '@/hooks/useGoBack'
import { toast } from 'sonner'
import { getDashboard, type DashboardData } from '@/api/adminDashboard'

const COLORS = {
  chat: '#5B8C5A',
  debate: '#D4A574',
  roundTable: '#6B8FA8',
  info: '#9A948A',
}

const PIE_COLORS = ['#5B8C5A', '#D4A574', '#6B8FA8', '#C75B5B', '#9A948A', '#7DBA7C', '#B8A99A']

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

export default function AdminDashboardPage() {
  const goBack = useGoBack()
  const [data, setData] = useState<DashboardData | null>(null)
  const [loading, setLoading] = useState(true)

  const load = async () => {
    setLoading(true)
    try {
      const res = await getDashboard()
      setData(res)
    } catch (e: any) {
      toast.error(e.message || '加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [])

  if (loading && !data) {
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

  const { overview, featureUsage, contentHeat, costMonitor, userProfile } = data

  // 功能使用饼图数据
  const featurePieData = featureUsage.features.map(f => ({
    name: f.name,
    value: f.count,
  }))

  // 用户画像饼图数据
  const mbtiData = Object.entries(userProfile.mbtiDistribution).map(([k, v]) => ({
    name: k,
    value: v,
  }))
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
        <button
          onClick={load}
          className="ml-auto flex items-center gap-1.5 rounded-full bg-muted px-3 py-1.5 text-xs font-medium text-muted-foreground hover:bg-muted/80"
        >
          <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
          刷新
        </button>
      </header>

      {/* Content */}
      <div className="flex-1 overflow-y-auto overscroll-contain">
        <div className="mx-auto max-w-5xl px-4 md:px-6 py-5 space-y-5">

          {/* ===== 第一区：平台健康度 ===== */}
          <section className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <StatCard
              icon={<Users className="h-5 w-5" />}
              label="总用户"
              value={formatNumber(overview.totalUsers)}
              sub={`本周 +${overview.weeklyNewUsers}`}
              color="text-brand-500"
            />
            <StatCard
              icon={<Activity className="h-5 w-5" />}
              label="周活跃"
              value={formatNumber(overview.weeklyActiveUsers)}
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
              label="周 Token"
              value={formatTokens(costMonitor.weeklyTokens)}
              color="text-warning"
            />
          </section>

          {/* ===== 第二区：功能使用 ===== */}
          <section className="rounded-xl bg-card shadow-xs p-4 md:p-5 space-y-4">
            <SectionTitle icon={<BarChart3 className="h-4 w-4" />} title="功能使用" />

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {/* 功能占比饼图 */}
              <div className="rounded-lg bg-muted/50 p-4">
                <p className="text-caption text-muted-foreground mb-3">功能使用占比</p>
                <div className="h-52">
                  <ResponsiveContainer width="100%" height="100%">
                    <PieChart>
                      <Pie
                        data={featurePieData}
                        cx="50%"
                        cy="50%"
                        innerRadius={50}
                        outerRadius={80}
                        paddingAngle={3}
                        dataKey="value"
                        label={({ name, percent }) => percent > 0.05 ? `${name} ${(percent * 100).toFixed(0)}%` : ''}
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
                </div>
              </div>

              {/* 7 天趋势折线图 */}
              <div className="rounded-lg bg-muted/50 p-4">
                <p className="text-caption text-muted-foreground mb-3">近 7 天趋势</p>
                <div className="h-52">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={featureUsage.trend} barGap={2}>
                      <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" vertical={false} />
                      <XAxis dataKey="date" tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} />
                      <YAxis tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} />
                      <Tooltip
                        contentStyle={{ background: 'hsl(var(--card))', border: '1px solid hsl(var(--border))', borderRadius: 8, fontSize: 12 }}
                      />
                      <Legend wrapperStyle={{ fontSize: 11 }} />
                      <Bar dataKey="chatCount" name="问答" fill={COLORS.chat} radius={[3, 3, 0, 0]} />
                      <Bar dataKey="debateCount" name="辩论" fill={COLORS.debate} radius={[3, 3, 0, 0]} />
                      <Bar dataKey="roundTableCount" name="圆桌" fill={COLORS.roundTable} radius={[3, 3, 0, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              </div>
            </div>

            {/* 辅助指标 */}
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
          </section>

          {/* ===== 第三区：内容热度 ===== */}
          <section className="rounded-xl bg-card shadow-xs p-4 md:p-5 space-y-4">
            <SectionTitle icon={<TrendingUp className="h-4 w-4" />} title="内容热度" />

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {/* 热门图书 */}
              <div className="rounded-lg bg-muted/50 p-4">
                <p className="text-caption text-muted-foreground mb-3">热门图书 Top 10</p>
                <div className="space-y-2">
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
                        <p className="text-sm font-medium truncate">{book.title}</p>
                        <p className="text-caption text-muted-foreground truncate">{book.author}</p>
                      </div>
                      <span className="text-caption text-muted-foreground shrink-0">
                        {book.discussionCount} 次讨论
                      </span>
                    </div>
                  ))}
                </div>
              </div>

              {/* 热门辩题 */}
              <div className="rounded-lg bg-muted/50 p-4">
                <p className="text-caption text-muted-foreground mb-3">热门辩题 Top 10</p>
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
          </section>

          {/* ===== 第四区：成本监控 ===== */}
          <section className="rounded-xl bg-card shadow-xs p-4 md:p-5 space-y-4">
            <SectionTitle icon={<Swords className="h-4 w-4" />} title="成本监控" />

            <div className="grid grid-cols-2 gap-3">
              <div className="rounded-lg bg-muted/50 p-4 text-center">
                <p className="text-caption text-muted-foreground">累计 Token</p>
                <p className="text-h3 mt-1">{formatTokens(costMonitor.totalTokens)}</p>
              </div>
              <div className="rounded-lg bg-muted/50 p-4 text-center">
                <p className="text-caption text-muted-foreground">本周 Token</p>
                <p className="text-h3 mt-1">{formatTokens(costMonitor.weeklyTokens)}</p>
              </div>
            </div>

            <div className="h-40">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={costMonitor.byFeature} layout="vertical" margin={{ left: 10 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" horizontal={false} />
                  <XAxis type="number" tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} tickFormatter={formatTokens} />
                  <YAxis type="category" dataKey="name" tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} width={70} />
                  <Tooltip
                    contentStyle={{ background: 'hsl(var(--card))', border: '1px solid hsl(var(--border))', borderRadius: 8, fontSize: 12 }}
                    formatter={(v: number) => formatTokens(v)}
                  />
                  <Bar dataKey="tokens" fill={COLORS.chat} radius={[0, 4, 4, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </section>

          {/* ===== 第五区：用户画像 ===== */}
          <section className="rounded-xl bg-card shadow-xs p-4 md:p-5 space-y-4">
            <SectionTitle icon={<Users className="h-4 w-4" />} title="用户画像" />

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {/* MBTI 分布 */}
              <div className="rounded-lg bg-muted/50 p-4">
                <p className="text-caption text-muted-foreground mb-3">MBTI 分布</p>
                <div className="h-52">
                  {mbtiData.length > 0 ? (
                    <ResponsiveContainer width="100%" height="100%">
                      <PieChart>
                        <Pie
                          data={mbtiData}
                          cx="50%"
                          cy="50%"
                          outerRadius={80}
                          paddingAngle={2}
                          dataKey="value"
                          label={({ name, percent }) => percent > 0.05 ? `${name} ${(percent * 100).toFixed(0)}%` : ''}
                          labelLine={false}
                        >
                          {mbtiData.map((_, i) => (
                            <Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} />
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
          </section>

          <div className="h-4" />
        </div>
      </div>
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

function SectionTitle({ icon, title }: { icon: React.ReactNode; title: string }) {
  return (
    <div className="flex items-center gap-2">
      <span className="text-primary">{icon}</span>
      <h2 className="text-h4">{title}</h2>
    </div>
  )
}
