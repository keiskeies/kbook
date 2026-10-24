import { ArrowRight, Flame, PenLine, TrendingUp } from 'lucide-react'
import type { NoteDailyCount, NoteStatsVO } from '@/api/note'
import { cn } from '@/utils/cn'

const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']

function toDayKey(d: Date) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

function weekdayOf(dayKey: string) {
  const [y, m, d] = dayKey.split('-').map(Number)
  return WEEKDAYS[new Date(y, m - 1, d).getDay()]
}

function lastSevenEmpty(): NoteDailyCount[] {
  return Array.from({ length: 7 }, (_, i) => {
    const d = new Date()
    d.setDate(d.getDate() - (6 - i))
    return { date: toDayKey(d), count: 0 }
  })
}

const SEGMENTS = [
  { key: 'thoughtCount', label: '想法', className: 'bg-amber-400' },
  { key: 'captureCount', label: '收录', className: 'bg-primary/80' },
  { key: 'excerptCount', label: '摘录', className: 'bg-sky-400' },
] as const

interface Props {
  stats: NoteStatsVO
  onWrite: () => void
  onOpenHeatmap?: () => void
}

/** 写作手账 — 连续天数、近 7 天节奏与笔记构成 */
export function NoteStatsHero({ stats, onWrite, onOpenHeatmap }: Props) {
  const days = stats.recentDays?.length ? stats.recentDays : lastSevenEmpty()
  const todayKey = toDayKey(new Date())
  const todayCount = days.find((d) => d.date === todayKey)?.count ?? 0
  const weekTotal = days.reduce((sum, d) => sum + d.count, 0)
  const maxCount = Math.max(1, ...days.map((d) => d.count))
  const typedTotal = stats.thoughtCount + stats.captureCount + stats.excerptCount

  const distribution = typedTotal > 0 && (
    <div>
      <div className="flex h-1.5 overflow-hidden rounded-full bg-muted">
        {SEGMENTS.map((s) => {
          const count = stats[s.key]
          if (count <= 0) return null
          return (
            <div
              key={s.key}
              className={cn('h-full transition-all', s.className)}
              style={{ width: `${(count / typedTotal) * 100}%` }}
            />
          )
        })}
      </div>
      <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted-foreground">
        {SEGMENTS.map((s) => (
          <span key={s.key} className="flex items-center gap-1">
            <i className={cn('h-1.5 w-1.5 rounded-full', s.className)} />
            {s.label} {stats[s.key]}
          </span>
        ))}
      </div>
    </div>
  )

  return (
    <section className="relative overflow-hidden rounded-3xl border border-border/60 bg-card">
      <div className="pointer-events-none absolute -right-20 -top-24 h-56 w-56 rounded-full bg-primary/10 blur-3xl" />
      <div className="pointer-events-none absolute -bottom-24 -left-16 h-44 w-44 rounded-full bg-warning/10 blur-3xl" />

      <div className="relative p-4 md:p-6">
        <div className="md:flex md:items-stretch md:gap-8">
          <div className="min-w-0 md:flex md:w-[320px] md:shrink-0 md:flex-col md:justify-between">
            <div>
              <p className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
                <TrendingUp className="h-3.5 w-3.5" />
                写作手账
              </p>
              <div className="mt-1.5 flex items-baseline gap-2">
                <span className="text-[30px] font-bold leading-none tabular-nums md:text-[36px]">
                  {stats.streakDays}
                </span>
                <span className="text-sm font-medium text-foreground/80">天连续记录</span>
                {stats.streakDays > 0 && <Flame className="h-4 w-4 text-orange-500" />}
              </div>
              <p className="mt-2 text-xs text-muted-foreground">
                本周 <b className="font-semibold text-foreground">+{stats.weekCount}</b>
                <span className="mx-1.5 text-border">·</span>
                累计 <b className="font-semibold text-foreground">{stats.activeDays ?? '—'}</b> 天
                <span className="mx-1.5 text-border">·</span>
                涉及 <b className="font-semibold text-foreground">{stats.bookCount}</b> 本书
                <span className="mx-1.5 text-border">·</span>
                共 <b className="font-semibold text-foreground">{stats.total}</b> 条
                {stats.favoriteCount > 0 && (
                  <>
                    <span className="mx-1.5 text-border">·</span>
                    收藏 <b className="font-semibold text-foreground">{stats.favoriteCount}</b>
                  </>
                )}
              </p>

              <div className="mt-3">
                {todayCount > 0 ? (
                  <span className="inline-flex rounded-full bg-primary/10 px-3 py-1.5 text-xs font-medium text-primary">
                    今天已记 {todayCount} 条
                  </span>
                ) : (
                  <button
                    onClick={onWrite}
                    className="flex items-center gap-1.5 rounded-full border border-primary/25 bg-primary/5 px-3 py-1.5 text-xs font-medium text-primary transition-colors hover:bg-primary/10"
                  >
                    <PenLine className="h-3.5 w-3.5" />
                    {stats.streakDays > 0 ? '今天还没记，写一句保持连续' : '记下第一笔'}
                  </button>
                )}
              </div>
            </div>

            {typedTotal > 0 && <div className="mt-4 hidden md:block">{distribution}</div>}
          </div>

          <div className="mt-5 md:flex md:min-w-0 md:flex-1 md:flex-col md:justify-center md:pt-1">
            <p className="mb-2 flex items-center justify-between text-[11px] text-muted-foreground">
              <span>近 7 天节奏 · {weekTotal} 条</span>
              {onOpenHeatmap && (
                <button
                  onClick={onOpenHeatmap}
                  className="flex items-center gap-0.5 font-medium text-primary transition-colors hover:text-primary/80"
                >
                  全年足迹
                  <ArrowRight className="h-3 w-3" />
                </button>
              )}
            </p>
            <div className="flex items-end gap-1.5 md:gap-2.5">
              {days.map((d) => {
                const isToday = d.date === todayKey
                const height = d.count > 0 ? Math.max(10, Math.round((d.count / maxCount) * 100)) : 0
                return (
                  <div key={d.date} className="flex min-w-0 flex-1 flex-col items-center gap-1">
                    <span
                      className={cn(
                        'text-[10px] tabular-nums transition-colors',
                        d.count > 0 ? 'text-muted-foreground' : 'text-transparent'
                      )}
                    >
                      {d.count || 0}
                    </span>
                    <div className="flex h-10 w-full items-end overflow-hidden rounded-md bg-muted/50 md:h-16">
                      <div
                        className={cn(
                          'w-full rounded-md transition-all duration-500',
                          isToday ? 'bg-primary' : 'bg-primary/40'
                        )}
                        style={{ height: `${height}%` }}
                      />
                    </div>
                    <span
                      className={cn(
                        'text-[10px]',
                        isToday ? 'font-semibold text-foreground' : 'text-muted-foreground/70'
                      )}
                    >
                      {isToday ? '今天' : weekdayOf(d.date)}
                    </span>
                  </div>
                )
              })}
            </div>
          </div>
        </div>

        {typedTotal > 0 && <div className="mt-4 md:hidden">{distribution}</div>}
      </div>
    </section>
  )
}
