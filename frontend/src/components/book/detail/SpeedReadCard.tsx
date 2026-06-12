import { useState } from 'react'
import { Clock, ChevronDown, ChevronUp, Target, Users, UserX, Lightbulb, Gauge } from 'lucide-react'
import type { BookSpeedRead } from '@/api/book'

interface SpeedReadCardProps {
  data: BookSpeedRead | null
  loading: boolean
}

export function SpeedReadCard({ data, loading }: SpeedReadCardProps) {
  const [expanded, setExpanded] = useState(true)

  const getDifficultyBadge = (difficulty: string) => {
    const d = difficulty?.toLowerCase() || ''
    if (d.includes('入门') || d.includes('简单')) {
      return 'bg-success/10 text-success dark:bg-success/20 dark:text-success border-success/20'
    }
    if (d.includes('中等') || d.includes('进阶')) {
      return 'bg-warning/10 text-warning dark:bg-warning/20 dark:text-warning border-warning/20'
    }
    if (d.includes('高级') || d.includes('困难')) {
      return 'bg-danger/10 text-danger dark:bg-danger/20 dark:text-danger border-danger/20'
    }
    return 'bg-primary/5 text-primary border-primary/20'
  }

  const sections = [
    {
      key: 'corePoints' as const,
      icon: <Target className="h-3.5 w-3.5 text-primary" />,
      title: '核心观点',
      titleClass: 'text-foreground',
      hasData: () => data?.corePoints && data.corePoints.length > 0,
      renderItems: () =>
        data?.corePoints && data.corePoints.length > 0 ? (
          <div className="border-l-2 border-primary/30 pl-3 space-y-2">
            {data.corePoints.map((point, idx) => (
              <div key={idx} className="flex items-start gap-2">
                <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary/10 text-[10px] font-bold text-primary">
                  {idx + 1}
                </span>
                <p className="text-sm text-muted-foreground leading-relaxed">{point}</p>
              </div>
            ))}
          </div>
        ) : null,
      skeleton: (
        <div className="border-l-2 border-primary/30 pl-3 space-y-2">
          {[0, 1, 2].map((i) => (
            <div key={i} className="flex items-start gap-2">
              <span className="mt-0.5 flex h-5 w-5 shrink-0 animate-pulse rounded-full bg-primary/10" />
              <div className="flex-1 space-y-1">
                <div className="h-[20px] animate-pulse rounded bg-muted" style={{ width: `${90 - i * 5}%` }} />
                <div className="h-[20px] animate-pulse rounded bg-muted" style={{ width: `${70 - i * 10}%` }} />
              </div>
            </div>
          ))}
        </div>
      ),
    },
    {
      key: 'suitableFor' as const,
      icon: <Users className="h-3.5 w-3.5 text-success" />,
      title: '适合谁读',
      titleClass: 'text-success dark:text-success',
      hasData: () => data?.suitableFor && data.suitableFor.length > 0,
      renderItems: () =>
        data?.suitableFor && data.suitableFor.length > 0 ? (
          <div className="flex flex-wrap gap-1.5">
            {data.suitableFor.map((item, idx) => (
              <span
                key={idx}
                className="rounded-full bg-success/10 border border-success/20 px-2.5 py-0.5 text-xs font-medium text-success dark:bg-success/20 dark:border-success/30 dark:text-success"
              >
                {item}
              </span>
            ))}
          </div>
        ) : null,
      skeleton: (
        <div className="space-y-1.5">
          {[0, 1, 2].map((i) => (
            <div
              key={i}
              className="h-[24px] animate-pulse rounded-full bg-success/10"
              style={{ width: `${55 + i * 8}%` }}
            />
          ))}
        </div>
      ),
    },
    {
      key: 'notSuitableFor' as const,
      icon: <UserX className="h-3.5 w-3.5 text-danger" />,
      title: '不适合谁读',
      titleClass: 'text-danger dark:text-danger',
      hasData: () => data?.notSuitableFor && data.notSuitableFor.length > 0,
      renderItems: () =>
        data?.notSuitableFor && data.notSuitableFor.length > 0 ? (
          <div className="flex flex-wrap gap-1.5">
            {data.notSuitableFor.map((item, idx) => (
              <span
                key={idx}
                className="rounded-full bg-danger/10 border border-danger/20 px-2.5 py-0.5 text-xs font-medium text-danger dark:bg-danger/20 dark:border-danger/30 dark:text-danger"
              >
                {item}
              </span>
            ))}
          </div>
        ) : null,
      skeleton: (
        <div className="space-y-1.5">
          {[0, 1, 2].map((i) => (
            <div
              key={i}
              className="h-[24px] animate-pulse rounded-full bg-danger/10"
              style={{ width: `${55 + i * 8}%` }}
            />
          ))}
        </div>
      ),
    },
    {
      key: 'takeaways' as const,
      icon: <Lightbulb className="h-3.5 w-3.5 text-warning" />,
      title: '读完能收获什么',
      titleClass: 'text-warning dark:text-warning',
      hasData: () => data?.takeaways && data.takeaways.length > 0,
      renderItems: () =>
        data?.takeaways && data.takeaways.length > 0 ? (
          <div className="border-l-2 border-warning/30 pl-3 space-y-1.5">
            {data.takeaways.map((item, idx) => (
              <div key={idx} className="flex items-start gap-2">
                <Gauge className="mt-0.5 h-3.5 w-3.5 shrink-0 text-warning" />
                <p className="text-sm text-muted-foreground">{item}</p>
              </div>
            ))}
          </div>
        ) : null,
      skeleton: (
        <div className="border-l-2 border-warning/30 pl-3 space-y-1.5">
          {[0, 1, 2].map((i) => (
            <div key={i} className="flex items-start gap-2">
              <div className="mt-0.5 h-3.5 w-3.5 shrink-0 animate-pulse rounded bg-warning/10" />
              <div className="flex-1 space-y-1">
                <div className="h-[20px] animate-pulse rounded bg-muted" style={{ width: `${85 - i * 5}%` }} />
                <div className="h-[20px] animate-pulse rounded bg-muted" style={{ width: `${65 - i * 10}%` }} />
              </div>
            </div>
          ))}
        </div>
      ),
    },
  ]

  if (!loading && !data) return null

  return (
    <div className="mb-4 rounded-2xl border border-border/50 bg-gradient-to-br from-card to-muted/20 p-4">
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center justify-between"
      >
        <div className="flex items-center gap-2">
          <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-primary/10">
            <Clock className="h-4 w-4 text-primary" />
          </div>
          <h3 className="text-sm font-bold">3分钟速读</h3>
          {data?.difficulty && (
            <span className={`rounded-full border px-2 py-0.5 text-[10px] font-medium ${getDifficultyBadge(data.difficulty)}`}>
              {data.difficulty}
              {loading && data.currentSection === '难度' && <span className="animate-pulse">|</span>}
            </span>
          )}
          {loading && data?.currentSection === '难度' && !data?.difficulty && data?.currentItem && (
            <span className="rounded-full border px-2 py-0.5 text-[10px] font-medium bg-primary/5 text-primary border-primary/20">
              {data.currentItem}<span className="animate-pulse">|</span>
            </span>
          )}
        </div>
        {expanded ? <ChevronUp className="h-4 w-4 text-muted-foreground" /> : <ChevronDown className="h-4 w-4 text-muted-foreground" />}
      </button>

      {expanded && (
        <div className="mt-3 space-y-4">
          {sections.map((section) => {
            const hasData = section.hasData()
            const isCurrentSection = data?.currentSection === section.title
            const showCurrentItem = isCurrentSection && data?.currentItem
            const content = hasData ? section.renderItems() : (loading && !showCurrentItem ? section.skeleton : null)
            if (!content && !showCurrentItem) return null
            return (
              <div key={section.key} className="space-y-2">
                <div className={`flex items-center gap-1.5 text-xs font-semibold ${section.titleClass}`}>
                  {section.icon}
                  {section.title}
                </div>
                {content}
                {showCurrentItem && (
                  <div className="flex items-start gap-2">
                    <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary/10 text-[10px] font-bold text-primary animate-pulse">
                      {(section.key === 'corePoints' ? (data?.corePoints?.length || 0) : section.key === 'suitableFor' ? (data?.suitableFor?.length || 0) : section.key === 'notSuitableFor' ? (data?.notSuitableFor?.length || 0) : (data?.takeaways?.length || 0)) + 1}
                    </span>
                    <p className="text-sm text-muted-foreground leading-relaxed">{data.currentItem}<span className="animate-pulse">|</span></p>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
