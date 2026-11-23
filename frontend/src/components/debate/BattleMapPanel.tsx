import type { ReactNode } from 'react'
import { Crosshair, AlertCircle, ShieldOff, CheckCircle2 } from 'lucide-react'
import type { DebateDossier, DebateChallenge } from '@/types/debate'
import { DEBATE_ROLE_NAMES } from '@/types/debate'

interface BattleMapPanelProps {
  dossier: DebateDossier
  compact?: boolean
}

type ItemTone = 'amber' | 'red' | 'green'

/** 带色调的列表分区（未回应质疑 / 被击穿论点 / 已确立论点） */
function InfoSection({
  icon,
  title,
  tone,
  items,
  compact,
}: {
  icon: ReactNode
  title: string
  tone: ItemTone
  items: string[]
  compact: boolean
}) {
  if (items.length === 0) return null

  const toneClass: Record<ItemTone, string> = {
    amber: 'text-amber-600 dark:text-amber-400',
    red: 'text-red-600 dark:text-red-400',
    green: 'text-emerald-600 dark:text-emerald-400',
  }

  return (
    <section>
      <div className="flex items-center gap-1.5 mb-2">
        <span className={toneClass[tone]}>{icon}</span>
        <h4 className="text-xs font-bold text-muted-foreground">{title}</h4>
        <span className="ml-auto text-[10px] text-muted-foreground tabular-nums">{items.length}</span>
      </div>
      <div className="space-y-1.5">
        {items.map((item, i) => (
          <div
            key={i}
            className={`rounded-xl border border-border/50 bg-card text-[11px] leading-relaxed text-foreground/80 ${compact ? 'p-2' : 'p-2.5'}`}
          >
            {item}
          </div>
        ))}
      </div>
    </section>
  )
}

/** 战场地图 — 分歧点 + 动态战场状态（深度模式，数据来自 debate_dossiers） */
export default function BattleMapPanel({ dossier, compact = false }: BattleMapPanelProps) {
  const focalPoints = dossier.focalPoints || []
  const battleState = dossier.battleState
  const currentId = battleState?.currentFocalPointId
  const challenges: DebateChallenge[] = battleState?.unansweredChallenges || []
  const brokenPoints = battleState?.brokenPoints || []
  const establishedPoints = battleState?.establishedPoints || []

  const challengeItems = challenges.map(ch => {
    const fromName = DEBATE_ROLE_NAMES[ch.from] || ch.from
    const targetLabel = ch.targetSide === 'PRO' ? '正方' : ch.targetSide === 'CON' ? '反方' : ch.targetSide
    return `${fromName} → ${targetLabel}：${ch.text}`
  })

  const hasNoData = focalPoints.length === 0 && challengeItems.length === 0
    && brokenPoints.length === 0 && establishedPoints.length === 0

  return (
    <div className="flex flex-col min-h-0 h-full">
      {/* 标题 */}
      <div className="flex items-center gap-2 px-4 py-3 border-b border-border/50 shrink-0">
        <Crosshair className="h-4 w-4 text-brand-500" />
        <h3 className="text-sm font-bold">战场地图</h3>
        <span className="ml-auto text-[10px] text-muted-foreground tabular-nums">
          {focalPoints.length} 个分歧点
        </span>
      </div>

      <div className={`flex-1 min-h-0 overflow-y-auto overscroll-contain px-4 py-3 ${compact ? 'space-y-3' : 'space-y-5'}`}>
        {/* 分歧点列表 — 当前焦点高亮 */}
        <section>
          <div className="flex items-center gap-1.5 mb-2">
            <Crosshair className="h-3.5 w-3.5 text-brand-500" />
            <h4 className="text-xs font-bold text-muted-foreground">分歧点</h4>
          </div>
          <div className="space-y-2">
            {focalPoints.map((fp, idx) => {
              const isCurrent = currentId != null ? fp.id === currentId : idx === 0
              return (
                <div
                  key={fp.id}
                  className={`rounded-2xl border p-3 ${isCurrent
                    ? 'border-brand-400/60 bg-brand-50/60 dark:bg-brand-950/20'
                    : 'border-border/50 bg-card'}`}
                >
                  <div className="flex items-center gap-2">
                    <span className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-[10px] font-bold ${isCurrent
                      ? 'bg-brand-500 text-white'
                      : 'bg-muted text-muted-foreground'}`}
                    >
                      {idx + 1}
                    </span>
                    <span className={`text-xs font-semibold leading-snug ${isCurrent ? 'text-brand-600 dark:text-brand-400' : ''}`}>
                      {fp.title}
                    </span>
                    {isCurrent && (
                      <span className="ml-auto shrink-0 text-[10px] px-1.5 py-0.5 rounded-full bg-brand-500/10 text-brand-500">
                        当前焦点
                      </span>
                    )}
                  </div>
                  {!compact && (
                    <div className="mt-2 space-y-1">
                      <p className="text-[11px] leading-relaxed text-[#4A7C6F]">正方：{fp.proThesis}</p>
                      <p className="text-[11px] leading-relaxed text-[#B8704A]">反方：{fp.conThesis}</p>
                      <p className="text-[10px] text-muted-foreground">章节：{fp.bookAnchor}</p>
                      {fp.concepts && fp.concepts.length > 0 && (
                        <div className="flex flex-wrap gap-1 pt-0.5">
                          {fp.concepts.map((c, i) => (
                            <span key={i} className="text-[10px] px-1.5 py-0.5 rounded-full bg-muted text-muted-foreground">
                              {c}
                            </span>
                          ))}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        </section>

        {/* 未回应质疑 */}
        <InfoSection
          icon={<AlertCircle className="h-3.5 w-3.5" />}
          title="未回应质疑"
          tone="amber"
          items={challengeItems}
          compact={compact}
        />

        {/* 被击穿论点 */}
        <InfoSection
          icon={<ShieldOff className="h-3.5 w-3.5" />}
          title="被击穿论点"
          tone="red"
          items={brokenPoints}
          compact={compact}
        />

        {/* 已确立论点 */}
        <InfoSection
          icon={<CheckCircle2 className="h-3.5 w-3.5" />}
          title="已确立论点"
          tone="green"
          items={establishedPoints}
          compact={compact}
        />

        {hasNoData && (
          <p className="text-xs text-muted-foreground text-center py-6">暂无战场数据</p>
        )}
      </div>
    </div>
  )
}
