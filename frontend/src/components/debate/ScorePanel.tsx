import { useMemo, useState } from 'react'
import { BarChart3, RefreshCw, ArrowUpDown, Trophy, Swords } from 'lucide-react'
import MobileSheetHeader from '@/components/common/MobileSheetHeader'
import type { DebateScore } from '@/types/debate'
import { DEBATE_ROLE_COLORS, DEBATE_SCORE_DIMENSIONS, DEBATE_ROLE_NAMES, DEBATE_PERSONALITY_NAMES } from '@/types/debate'

interface ScorePanelProps {
  scores: DebateScore[]
  onClose: () => void
  onRefresh?: () => void
  isMobile?: boolean
}

/** 默认角色顺序 */
const POSITION_ORDER = ['PRO_1', 'PRO_2', 'PRO_3', 'PRO_4', 'CON_1', 'CON_2', 'CON_3', 'CON_4']

/** 简易雷达图 SVG */
function RadarChart({ proScores, conScores }: { proScores: number[]; conScores: number[] }) {
  const size = 140
  const center = size / 2
  const radius = 50
  const angles = [0, 60, 120, 180, 240, 300, 360].map(a => (a - 90) * Math.PI / 180)
  const levels = 4

  const getPoint = (score: number, angle: number) => ({
    x: center + (radius * score / 10) * Math.cos(angle),
    y: center + (radius * score / 10) * Math.sin(angle),
  })

  const proPoints = proScores.map((s, i) => getPoint(s, angles[i])).map(p => `${p.x},${p.y}`).join(' ')
  const conPoints = conScores.map((s, i) => getPoint(s, angles[i])).map(p => `${p.x},${p.y}`).join(' ')

  return (
    <svg width={size} height={size} className="mx-auto">
      {/* 网格 */}
      {Array.from({ length: levels }).map((_, i) => {
        const r = radius * (i + 1) / levels
        const points = angles.map(a => `${center + r * Math.cos(a)},${center + r * Math.sin(a)}`).join(' ')
        return <polygon key={i} points={points} fill="none" stroke="currentColor" strokeOpacity={0.08} strokeWidth={0.5} />
      })}
      {/* 轴线 */}
      {angles.map((a, i) => (
        <line key={i} x1={center} y1={center} x2={center + radius * Math.cos(a)} y2={center + radius * Math.sin(a)}
          stroke="currentColor" strokeOpacity={0.08} strokeWidth={0.5} />
      ))}
      {/* 正方区域 */}
      <polygon points={proPoints} fill="rgba(74,124,111,0.15)" stroke="#4A7C6F" strokeWidth={1.5} />
      {/* 反方区域 */}
      <polygon points={conPoints} fill="rgba(184,112,74,0.12)" stroke="#B8704A" strokeWidth={1.5} />
    </svg>
  )
}

/**
 * 7维度评分面板 — 含胜负裁决 + 雷达图
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
      // 兜底：roleKey 可能是位置键（历史数据 bug），尝试用 positionKey 反查性格
      const rawRoleKey = roleScores[0]?.roleKey || ''
      let personalityName = DEBATE_PERSONALITY_NAMES[rawRoleKey] || ''
      if (!personalityName && roleScores[0]?.positionKey) {
        // 从同 positionKey 的评分记录里找有有效性格名的那条
        const fallback = roleScores.find(
          s => s.roleKey && DEBATE_PERSONALITY_NAMES[s.roleKey]
        )
        if (fallback) {
          personalityName = DEBATE_PERSONALITY_NAMES[fallback.roleKey] || ''
        }
      }
      const displayName = positionName && personalityName
        ? `${positionName}（${personalityName}）`
        : positionName || personalityName || key

      return { key, name: displayName, rawName: positionName, personality: personalityName, dims, overall }
    })

    if (sortBy === 'position') {
      list.sort((a, b) => POSITION_ORDER.indexOf(a.key) - POSITION_ORDER.indexOf(b.key))
    } else {
      list.sort((a, b) => b.overall - a.overall)
    }

    return list
  }, [scores, sortBy])

  // 正反方对比 + 胜负
  const { sideComparison, winner, proOverall, conOverall } = useMemo(() => {
    const proScores = scores.filter(s => s.side === 'PRO')
    const conScores = scores.filter(s => s.side === 'CON')

    const avgFor = (list: DebateScore[], dim: keyof DebateScore) => {
      const vals = list.map(s => s[dim]).filter((v): v is number => v !== null)
      return vals.length > 0 ? vals.reduce((a, b) => a + b, 0) / vals.length : 0
    }

    const pro = DEBATE_SCORE_DIMENSIONS.map(d => ({ key: d.key, label: d.label, score: avgFor(proScores, d.key as keyof DebateScore) }))
    const con = DEBATE_SCORE_DIMENSIONS.map(d => ({ key: d.key, label: d.label, score: avgFor(conScores, d.key as keyof DebateScore) }))

    const proAvg = pro.reduce((s, d) => s + d.score, 0) / pro.length
    const conAvg = con.reduce((s, d) => s + d.score, 0) / con.length

    return {
      sideComparison: { pro, con },
      winner: proAvg > conAvg ? 'PRO' : conAvg > proAvg ? 'CON' : 'TIE',
      proOverall: proAvg,
      conOverall: conAvg,
    }
  }, [scores])

  // 最佳辩手
  const bestDebater = useMemo(() => {
    if (roleAverages.length === 0) return null
    return roleAverages.reduce((best, r) => r.overall > best.overall ? r : best, roleAverages[0])
  }, [roleAverages])

  if (scores.length === 0) {
    return (
      <div className="flex flex-col min-h-0">
        <MobileSheetHeader
          icon={<BarChart3 className="h-5 w-5 text-brand-500" />}
          title="评分面板"
          onClose={isMobile ? onClose : undefined}
        />
        <div className="flex-1 flex items-center justify-center">
          <p className="text-xs text-muted-foreground">发言后将自动生成评分</p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex flex-col min-h-0 h-full">
      <MobileSheetHeader
        icon={<BarChart3 className="h-5 w-5 text-brand-500" />}
        title="评分面板"
        actions={
          <button
            onClick={() => onRefresh?.()}
            className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground"
            title="刷新"
          >
            <RefreshCw className="h-4 w-4" />
          </button>
        }
        onClose={isMobile ? onClose : undefined}
      />

      <div className="flex-1 min-h-0 overflow-y-auto overscroll-contain px-4 py-3 space-y-5">
        {/* 胜负裁决 */}
        <section className="rounded-2xl border border-border/50 bg-card p-4">
          <div className="flex items-center justify-center gap-3 mb-3">
            <div className="text-center">
              <div className="text-xs text-[#4A7C6F] font-medium mb-0.5">正方</div>
              <div className="text-xl font-bold text-[#4A7C6F]">{proOverall.toFixed(1)}</div>
            </div>
            <div className="flex items-center justify-center h-8 w-8 rounded-full bg-muted">
              <Swords className="h-4 w-4 text-muted-foreground" />
            </div>
            <div className="text-center">
              <div className="text-xs text-[#B8704A] font-medium mb-0.5">反方</div>
              <div className="text-xl font-bold text-[#B8704A]">{conOverall.toFixed(1)}</div>
            </div>
          </div>
          {winner !== 'TIE' && (
            <div className="flex items-center justify-center gap-1.5 py-2 rounded-xl bg-brand-50 dark:bg-brand-950/20">
              <Trophy className="h-3.5 w-3.5 text-brand-500" />
              <span className="text-xs font-bold text-brand-500">
                {winner === 'PRO' ? '正方' : '反方'} 获胜
              </span>
            </div>
          )}
        </section>

        {/* 雷达图对比 */}
        <section className="rounded-2xl border border-border/50 bg-card p-4">
          <h4 className="text-xs font-bold text-muted-foreground mb-3 text-center">能力雷达</h4>
          <RadarChart proScores={sideComparison.pro.map(d => d.score)} conScores={sideComparison.con.map(d => d.score)} />
          <div className="flex items-center justify-center gap-4 mt-2">
            <div className="flex items-center gap-1.5">
              <div className="h-2 w-2 rounded-full bg-[#4A7C6F]" />
              <span className="text-xs text-muted-foreground">正方</span>
            </div>
            <div className="flex items-center gap-1.5">
              <div className="h-2 w-2 rounded-full bg-[#B8704A]" />
              <span className="text-xs text-muted-foreground">反方</span>
            </div>
          </div>
        </section>

        {/* 最佳辩手 */}
        {bestDebater && (
          <section className="rounded-2xl border border-border/50 bg-card p-4">
            <div className="flex items-center gap-2 mb-3">
              <Trophy className="h-4 w-4 text-amber-500" />
              <span className="text-xs font-bold text-amber-600 dark:text-amber-400">最佳辩手</span>
            </div>
            <div className="flex items-center gap-2">
              <div
                className="flex h-8 w-8 items-center justify-center rounded-full text-sm font-bold text-white"
                style={{ backgroundColor: DEBATE_ROLE_COLORS[bestDebater.key] || '#888' }}
              >
                {bestDebater.name.charAt(0)}
              </div>
              <div>
                <span className="text-sm font-semibold" style={{ color: DEBATE_ROLE_COLORS[bestDebater.key] || '#888' }}>
                  {bestDebater.name}
                </span>
                <span className="text-xs text-muted-foreground ml-1">综合 {bestDebater.overall.toFixed(1)} 分</span>
              </div>
            </div>
          </section>
        )}

        {/* 角色评分汇总 */}
        <section>
          <div className="flex items-center justify-between mb-3">
            <h4 className="text-xs font-bold text-muted-foreground">角色评分汇总</h4>
            <button
              onClick={() => setSortBy(sortBy === 'position' ? 'score' : 'position')}
              className="flex items-center gap-0.5 text-xs p-1 rounded text-muted-foreground hover:text-foreground transition-colors"
            >
              <ArrowUpDown className="h-3 w-3" />
              <span>{sortBy === 'position' ? '角色顺序' : '评分高低'}</span>
            </button>
          </div>
          <div className="space-y-3">
            {roleAverages.map(role => {
              const color = DEBATE_ROLE_COLORS[role.key] || '#888'
              return (
                <div key={role.key} className="rounded-2xl border border-border/50 bg-card p-4">
                  <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-1.5">
                      <span className="text-xs font-bold" style={{ color }}>{role.rawName || role.name}</span>
                      {role.personality && (
                        <span className="text-xs px-1.5 py-0.5 rounded-full bg-muted text-muted-foreground">
                          {role.personality}
                        </span>
                      )}
                    </div>
                    <span className="text-xs font-bold">{role.overall.toFixed(1)}</span>
                  </div>
                  <div className="space-y-2">
                    {role.dims.map(d => (
                      <div key={d.key} className="flex items-center gap-2">
                        <span className="text-xs w-16 shrink-0 text-muted-foreground">{d.label}</span>
                        <div className="flex-1 h-1.5 rounded-full bg-muted overflow-hidden">
                          <div
                            className="h-full rounded-full transition-all"
                            style={{ width: `${d.score * 10}%`, backgroundColor: d.color }}
                          />
                        </div>
                        <span className="text-xs w-6 text-right text-muted-foreground tabular-nums">{d.score.toFixed(1)}</span>
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
