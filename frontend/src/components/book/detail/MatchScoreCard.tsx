import { useEffect, useState } from 'react'
import { Sparkles, ChevronDown, ChevronUp } from 'lucide-react'
import { getMatchScoreDetail } from '@/api/book'
import type { MatchScoreDetail, DimensionScore } from '@/api/book'
import { CircularProgress } from './CircularProgress'

interface MatchScoreCardProps {
  bookId: number
  ms: number | undefined | null
}

export function MatchScoreCard({ bookId, ms }: MatchScoreCardProps) {
  const [detail, setDetail] = useState<MatchScoreDetail | null>(null)
  const [expanded, setExpanded] = useState(false)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!bookId) return
    setLoading(true)
    getMatchScoreDetail(bookId)
      .then((res: unknown) => {
        const data = (res as { data?: MatchScoreDetail })?.data ?? res as MatchScoreDetail
        if (data) setDetail(data)
      })
      .catch(() => { /* ignore */ })
      .finally(() => setLoading(false))
  }, [bookId])

  const overallPct = Math.round(Math.max(0, detail?.overallScore ?? ms ?? 0) * 100)

  if (loading) {
    return (
      <div className="mb-4 rounded-2xl border border-border/50 bg-card p-4">
        <div className="flex items-center gap-3">
          <div className="h-14 w-14 animate-pulse rounded-full bg-muted" />
          <div className="flex-1 space-y-2">
            <div className="h-4 w-24 animate-pulse rounded bg-muted" />
            <div className="h-3 w-16 animate-pulse rounded bg-muted" />
          </div>
        </div>
      </div>
    )
  }

  if (!detail && overallPct <= 0) return null

  const dims = detail?.dimensions || []

  // 维度颜色映射
  const getDimColor = (pct: number) => {
    if (pct >= 80) return 'bg-success'
    if (pct >= 60) return 'bg-warning'
    if (pct >= 40) return 'bg-orange-500'
    return 'bg-slate-400'
  }

  return (
    <div className="mb-4 rounded-2xl border border-border/50 bg-gradient-to-br from-card to-muted/20 p-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <CircularProgress percentage={overallPct} />
          <div>
            <p className="text-sm font-bold">匹配度分析</p>
            <p className="text-xs text-muted-foreground">
              {detail ? `覆盖 ${detail.matchedDimensions} 个维度` : '基于你的阅读偏好'}
            </p>
            {overallPct >= 80 && (
              <span className="inline-flex items-center gap-0.5 mt-1 rounded-full bg-orange-500/10 px-2 py-0.5 text-[10px] font-medium text-orange-600">
                <Sparkles className="h-2.5 w-2.5" />
                强烈推荐
              </span>
            )}
          </div>
        </div>
        {dims.length > 0 && (
          <button
            onClick={() => setExpanded(!expanded)}
            className="flex h-8 w-8 items-center justify-center rounded-full hover:bg-muted transition-colors"
          >
            {expanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
          </button>
        )}
      </div>

      {expanded && dims.length > 0 && (
        <div className="mt-3 space-y-2.5 border-t border-border/50 pt-3">
          {dims.map((d: DimensionScore) => {
            const pct = Math.round(Math.max(0, d.score) * 100)
            return (
              <div key={d.dimension}>
                <div className="flex items-center justify-between text-xs mb-1">
                  <span className="text-muted-foreground">{d.label}</span>
                  <span className={`font-medium ${pct >= 80 ? 'text-success' : pct >= 60 ? 'text-warning' : 'text-muted-foreground'}`}>
                    {pct}%
                  </span>
                </div>
                <div className="h-1.5 w-full rounded-full bg-muted">
                  <div
                    className={`h-full rounded-full transition-all duration-500 ${getDimColor(pct)}`}
                    style={{ width: `${pct}%` }}
                  />
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
