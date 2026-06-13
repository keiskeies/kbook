import { useMemo } from 'react'
import { BarChart3, RefreshCw } from 'lucide-react'
import type { DebateScore } from '@/types/debate'
import { DEBATE_ROLE_COLORS, DEBATE_SCORE_DIMENSIONS, DEBATE_ROLE_NAMES } from '@/types/debate'

interface ScorePanelProps {
  scores: DebateScore[]
  onClose: () => void
  onRefresh?: () => void
  isMobile?: boolean
}

/**
 * 7维度评分面板 — 以雷达图+柱状图展示辩论评分
 */
export default function ScorePanel({ scores, onClose, onRefresh, isMobile }: ScorePanelProps) {
  // 按角色分组计算平均分
  const roleAverages = useMemo(() => {
    const grouped = new Map<string, DebateScore[]>()
    for (const s of scores) {
      const arr = grouped.get(s.roleKey) || []
      arr.push(s)
      grouped.set(s.roleKey, arr)
    }

    return Array.from(grouped.entries()).map(([roleKey, roleScores]) => {
      const avg = (dim: keyof DebateScore) => {
        const vals = roleScores.map(s => s[dim]).filter((v): v is number => v !== null)
        return vals.length > 0 ? vals.reduce((a, b) => a + b, 0) / vals.length : 0
      }

      const dims = DEBATE_SCORE_DIMENSIONS.map(d => ({
        key: d.key,
        label: d.label,
        score: avg(d.key as keyof DebateScore),
        color: d.color,
      }))

      const overall = dims.reduce((s, d) => s + d.score, 0) / dims.length

      return { roleKey, name: DEBATE_ROLE_NAMES[roleKey] || roleKey, dims, overall }
    }).sort((a, b) => b.overall - a.overall)
  }, [scores])

  // 正反方对比
  const sideComparison = useMemo(() => {
    const proScores = scores.filter(s => s.side === 'PRO')
    const conScores = scores.filter(s => s.side === 'CON')

    const avgFor = (list: DebateScore[], dim: keyof DebateScore) => {
      const vals = list.map(s => s[dim]).filter((v): v is number => v !== null)
      return vals.length > 0 ? vals.reduce((a, b) => a + b, 0) / vals.length : 0
    }

    return {
      pro: DEBATE_SCORE_DIMENSIONS.map(d => ({ key: d.key, label: d.label, score: avgFor(proScores, d.key as keyof DebateScore) })),
      con: DEBATE_SCORE_DIMENSIONS.map(d => ({ key: d.key, label: d.label, score: avgFor(conScores, d.key as keyof DebateScore) })),
    }
  }, [scores])

  if (scores.length === 0) {
    return (
      <div className="flex flex-col min-h-0">
        <div className="shrink-0 flex items-center justify-between px-4 py-3 border-b border-border/20">
          <h3 className="text-xs font-bold flex items-center gap-1.5">
            <BarChart3 className="h-3.5 w-3.5 text-brand-500" />
            评分面板
          </h3>
          {!isMobile && (
            <button onClick={onClose} className="text-[10px] text-muted-foreground hover:text-foreground mr-7">关闭</button>
          )}
        </div>
        <div className="flex-1 flex items-center justify-center">
          <p className="text-xs text-muted-foreground">发言后将自动生成评分</p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex flex-col min-h-0 h-full">
      <div className="shrink-0 flex items-center justify-between px-4 py-3 border-b border-border/20">
        <h3 className="text-xs font-bold flex items-center gap-1.5">
          <BarChart3 className="h-3.5 w-3.5 text-brand-500" />
          评分面板
        </h3>
        <div className="flex items-center gap-1">
          <button
            onClick={() => onRefresh?.()}
            className="text-[10px] text-muted-foreground hover:text-foreground p-1 rounded hover:bg-muted transition-colors flex items-center gap-0.5"
          >
            <RefreshCw className="h-3 w-3" />
            <span className="hidden sm:inline">刷新</span>
          </button>
        </div>
      </div>

      <div className="flex-1 min-h-0 overflow-y-auto overscroll-contain px-4 py-3 space-y-5">
        {/* 正反方对比 */}
        <section>
          <h4 className="text-xs font-bold text-muted-foreground mb-2">正反方对比</h4>
          <div className="space-y-2">
            {sideComparison.pro.map((d, i) => {
              const conScore = sideComparison.con[i]?.score || 0
              const maxVal = Math.max(d.score, conScore, 1)
              return (
                <div key={d.key} className="text-xs">
                  <div className="flex justify-between mb-0.5">
                    <span className="font-medium">{d.label}</span>
                    <span className="text-[10px] text-muted-foreground">
                      正{d.score.toFixed(1)} / 反{conScore.toFixed(1)}
                    </span>
                  </div>
                  <div className="relative h-3 flex rounded-full overflow-hidden bg-muted">
                    <div
                      className="h-full rounded-l-full transition-all"
                      style={{ width: `${(d.score / maxVal) * 50}%`, backgroundColor: '#3B82F6' }}
                    />
                    <div className="flex-1" />
                    <div
                      className="h-full rounded-r-full transition-all"
                      style={{ width: `${(conScore / maxVal) * 50}%`, backgroundColor: '#EF4444' }}
                    />
                  </div>
                </div>
              )
            })}
          </div>
        </section>

        {/* 角色评分汇总 */}
        <section>
          <h4 className="text-xs font-bold text-muted-foreground mb-2">角色评分汇总</h4>
          <div className="space-y-3">
            {roleAverages.map(role => {
              const color = DEBATE_ROLE_COLORS[role.roleKey] || '#888'
              return (
                <div key={role.roleKey} className="rounded-xl border border-border/20 p-3">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-xs font-bold" style={{ color }}>{role.name}</span>
                    <span className="text-xs font-bold">{role.overall.toFixed(1)}</span>
                  </div>
                  <div className="space-y-1">
                    {role.dims.map(d => (
                      <div key={d.key} className="flex items-center gap-2">
                        <span className="text-[10px] w-16 shrink-0 text-muted-foreground">{d.label}</span>
                        <div className="flex-1 h-1.5 rounded-full bg-muted overflow-hidden">
                          <div
                            className="h-full rounded-full transition-all"
                            style={{ width: `${d.score * 10}%`, backgroundColor: d.color }}
                          />
                        </div>
                        <span className="text-[10px] w-6 text-right text-muted-foreground tabular-nums">{d.score.toFixed(1)}</span>
                      </div>
                    ))}
                  </div>
                </div>
              )
            })}
          </div>
        </section>
      </div>
    </div>
  )
}
