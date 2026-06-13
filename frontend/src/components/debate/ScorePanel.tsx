import { useMemo, useState } from 'react'
import { BarChart3, RefreshCw, ArrowUpDown } from 'lucide-react'
import type { DebateScore } from '@/types/debate'
import { DEBATE_ROLE_COLORS, DEBATE_SCORE_DIMENSIONS, DEBATE_ROLE_NAMES, getPersonalityTitle } from '@/types/debate'

interface ScorePanelProps {
  scores: DebateScore[]
  onClose: () => void
  onRefresh?: () => void
  isMobile?: boolean
}

/** 默认角色顺序 */
const POSITION_ORDER = ['PRO_1', 'PRO_2', 'PRO_3', 'PRO_4', 'CON_1', 'CON_2', 'CON_3', 'CON_4']

/**
 * 7维度评分面板
 */
export default function ScorePanel({ scores, onClose, onRefresh, isMobile }: ScorePanelProps) {
  const [sortBy, setSortBy] = useState<'position' | 'score'>('position')

  // 按位置键分组计算平均分，排除主持人
  const roleAverages = useMemo(() => {
    const grouped = new Map<string, DebateScore[]>()
    for (const s of scores) {
      if (s.positionKey === 'HOST') continue
      const key = s.positionKey || s.roleKey
      const arr = grouped.get(key) || []
      arr.push(s)
      grouped.set(key, arr)
    }

    const list = Array.from(grouped.entries()).map(([key, roleScores]) => {
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

      const positionName = DEBATE_ROLE_NAMES[key] || ''
      const personalityName = getPersonalityTitle(roleScores[0]?.roleKey || '')
      const displayName = positionName && personalityName
        ? `${positionName}（${personalityName}）`
        : positionName || personalityName || key

      return { key, name: displayName, dims, overall }
    })

    // 排序
    if (sortBy === 'position') {
      list.sort((a, b) => POSITION_ORDER.indexOf(a.key) - POSITION_ORDER.indexOf(b.key))
    } else {
      list.sort((a, b) => b.overall - a.overall)
    }

    return list
  }, [scores, sortBy])

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
            <button onClick={onClose} className="text-[10px] text-muted-foreground hover:text-foreground">关闭</button>
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
                      style={{ width: `${(d.score / maxVal) * 50}%`, backgroundColor: '#6B8FA8' }}
                    />
                    <div className="flex-1" />
                    <div
                      className="h-full rounded-r-full transition-all"
                      style={{ width: `${(conScore / maxVal) * 50}%`, backgroundColor: '#C75B5B' }}
                    />
                  </div>
                </div>
              )
            })}
          </div>
        </section>

        {/* 角色评分汇总 */}
        <section>
          <div className="flex items-center justify-between mb-2">
            <h4 className="text-xs font-bold text-muted-foreground">角色评分汇总</h4>
            <button
              onClick={() => setSortBy(sortBy === 'position' ? 'score' : 'position')}
              className={`flex items-center gap-0.5 text-[10px] p-1 rounded transition-colors ${
                sortBy === 'score' ? 'bg-brand-100 text-brand-500' : 'text-muted-foreground hover:text-foreground'
              }`}
            >
              <ArrowUpDown className="h-3 w-3" />
              <span>{sortBy === 'position' ? '角色顺序' : '评分高低'}</span>
            </button>
          </div>
          <div className="space-y-3">
            {roleAverages.map(role => {
              const color = DEBATE_ROLE_COLORS[role.key] || '#888'
              return (
                <div key={role.key} className="rounded-xl border border-border/20 p-3">
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
