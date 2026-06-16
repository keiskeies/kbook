import { useState, useEffect } from 'react'
import { FileText, Loader2, RefreshCw, Trophy, ChevronDown, ChevronUp, Sparkles } from 'lucide-react'
import MobileSheetHeader from '@/components/common/MobileSheetHeader'
import MarkdownRenderer from '@/components/ui/markdown-renderer'
import type { DebateReport } from '@/types/debate'
import { DEBATE_PERSONALITY_NAMES, DEBATE_PERSONALITY_COLORS, DEBATE_PERSONALITY_TITLES, DEBATE_PERSONALITY_ICONS, DEBATE_ROLE_NAMES } from '@/types/debate'

interface ReportPanelProps {
  report: DebateReport | null
  isGenerating: boolean
  isOwner?: boolean
  onTrigger: () => void
  onClose: () => void
}

/** 生成中动画日志 */
const GENERATING_LOGS = [
  '正在梳理正方核心论点...',
  '正在梳理反方核心论点...',
  '正在分析开篇立论交锋...',
  '正在复盘交叉质询环节...',
  '正在评估自由辩论表现...',
  '正在撰写评委综合评语...',
  '正在裁定胜负结果...',
]

function GeneratingAnimation() {
  const [logs, setLogs] = useState<string[]>([])

  useEffect(() => {
    let step = 0
    const interval = setInterval(() => {
      step = (step + 1) % GENERATING_LOGS.length
      setLogs(prev => [...prev.slice(-4), GENERATING_LOGS[step]])
    }, 2500)
    return () => clearInterval(interval)
  }, [])

  return (
    <div className="flex flex-col items-center py-8">
      <div className="relative mb-4">
        <div className="h-12 w-12 rounded-2xl bg-brand-50 dark:bg-brand-950/30 flex items-center justify-center">
          <Sparkles className="h-6 w-6 text-brand-500 animate-pulse" />
        </div>
        <div className="absolute -bottom-1 -right-1 h-4 w-4 rounded-full bg-brand-500 flex items-center justify-center">
          <Loader2 className="h-2.5 w-2.5 text-white animate-spin" />
        </div>
      </div>
      <p className="text-xs font-medium text-muted-foreground mb-4">AI 正在撰写评审报告</p>
      <div className="w-full max-w-xs space-y-1.5">
        {logs.map((log, i) => (
          <div
            key={`${i}-${log}`}
            className="flex items-center gap-2 text-[10px] text-muted-foreground/70 animate-in fade-in slide-in-from-left-2 duration-500"
          >
            <span className="h-1 w-1 rounded-full bg-brand-400/60" />
            <span>{log}</span>
          </div>
        ))}
      </div>
      <p className="text-[10px] text-muted-foreground/40 mt-4">预计 2-3 分钟</p>
    </div>
  )
}

/** 结构化报告分节 */
function StructuredReport({ content }: { content: string }) {
  const [expandedSections, setExpandedSections] = useState<Record<string, boolean>>({
    review: true,
    arguments: true,
    clashes: true,
    evaluation: true,
    verdict: true,
  })

  const toggle = (key: string) => setExpandedSections(prev => ({ ...prev, [key]: !prev[key] }))

  // 尝试从 Markdown 内容中提取各节
  // 简单策略：按 h2/h3 分节
  const sections = content.split(/\n## /).filter(Boolean)

  // 如果内容没有明确分节，直接渲染
  if (sections.length <= 1) {
    return (
      <div className="[&_h2]:border-border/30 [&_h2]:pb-1.5 [&_h2]:mb-3 [&_h2]:!text-base
                    [&_h3]:!text-sm [&_h3]:mb-2
                    [&_p]:my-2
                    [&_blockquote]:!border-l-4 [&_blockquote]:!border-brand-300 [&_blockquote]:!bg-brand-50/50 dark:[&_blockquote]:!bg-brand-500/10 [&_blockquote]:pl-4
                    [&_strong]:!text-brand-600 dark:[&_strong]:!text-brand-400
                    [&_li]:my-0.5
                    [&_hr]:!my-4
                    [&_ul]:my-2 [&_ol]:my-2
                    [&_.table-scroll-wrapper]:-mx-4 [&_.table-scroll]:px-4">
        <MarkdownRenderer content={content} className="!text-detail !leading-relaxed" />
      </div>
    )
  }

  const sectionConfig: Record<string, { title: string; icon: string }> = {
    '赛事回顾': { title: '赛事回顾', icon: '📋' },
    '双方论点': { title: '双方论点梳理', icon: '⚖️' },
    '关键交锋': { title: '关键交锋分析', icon: '⚔️' },
    '评委点评': { title: '评委综合点评', icon: '📝' },
    '胜负裁定': { title: '胜负裁定', icon: '🏆' },
  }

  return (
    <div className="space-y-2">
      {sections.map((section, idx) => {
        const lines = section.split('\n')
        const titleLine = lines[0].replace(/^#+\s*/, '').trim()
        const sectionBody = lines.slice(1).join('\n')
        const config = Object.entries(sectionConfig).find(([k]) => titleLine.includes(k))?.[1]
        const sectionKey = `section-${idx}`
        const isExpanded = expandedSections[sectionKey] !== false

        return (
          <div key={sectionKey} className="rounded-xl border border-border/20 overflow-hidden">
            <button
              onClick={() => toggle(sectionKey)}
              className="w-full flex items-center justify-between px-3 py-2.5 bg-muted/20 hover:bg-muted/40 transition-colors"
            >
              <div className="flex items-center gap-2">
                <span className="text-sm">{config?.icon || '📄'}</span>
                <span className="text-xs font-semibold">{config?.title || titleLine}</span>
              </div>
              {isExpanded ? (
                <ChevronUp className="h-3.5 w-3.5 text-muted-foreground" />
              ) : (
                <ChevronDown className="h-3.5 w-3.5 text-muted-foreground" />
              )}
            </button>
            {isExpanded && (
              <div className="px-3 py-3">
                <MarkdownRenderer content={sectionBody} className="!text-detail !leading-relaxed" />
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}

/**
 * 辩论报告面板 — 结构化分节 + 生成中动画
 */
export default function ReportPanel({ report, isGenerating, isOwner, onTrigger, onClose }: ReportPanelProps) {
  return (
    <div className="flex flex-col min-h-0 h-full">
      <MobileSheetHeader
        icon={<FileText className="h-5 w-5 text-brand-500" />}
        title="辩论报告"
        onClose={onClose}
      />

      <div className="flex-1 min-h-0 overflow-y-auto overscroll-contain px-4 py-3">
        {!report && !isGenerating && (
          <div className="text-center py-6">
            {isOwner !== false ? (
              <>
                <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-muted mx-auto mb-3">
                  <FileText className="h-6 w-6 text-muted-foreground" />
                </div>
                <p className="text-xs text-muted-foreground mb-3">辩论结束后，AI 将生成一份完整的评审报告</p>
                <button
                  onClick={onTrigger}
                  className="inline-flex items-center gap-1.5 rounded-xl bg-gradient-to-r from-brand-400 to-brand-500 px-4 py-2 text-xs font-medium text-white shadow-sm active:scale-[0.97] transition-transform"
                >
                  <Sparkles className="h-3 w-3" />
                  生成辩论报告
                </button>
                <p className="text-xs text-muted-foreground/60 mt-2">预计 2-3 分钟</p>
              </>
            ) : (
              <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-muted mx-auto mb-3">
                <FileText className="h-6 w-6 text-muted-foreground/50" />
              </div>
            )}
          </div>
        )}

        {isGenerating && <GeneratingAnimation />}

        {report?.status === 'COMPLETED' && report.content && (
          <div>
            {report.bestDebater && (() => {
              const key = report.bestDebater
              const posKey = report.bestDebaterPosition
              const posName = posKey ? (DEBATE_ROLE_NAMES[posKey] || '') : ''
              const persTitle = DEBATE_PERSONALITY_TITLES[key] || ''
              const persIcon = DEBATE_PERSONALITY_ICONS[key] || '👤'
              const color = DEBATE_PERSONALITY_COLORS[key] || '#D4A843'
              const displayName = posName || DEBATE_PERSONALITY_NAMES[key] || key
              return (
                <div className="mb-4 rounded-2xl border border-border/50 bg-card p-4">
                  <div className="flex items-center gap-2 mb-3">
                    <Trophy className="h-4 w-4 text-amber-500" />
                    <span className="text-xs font-bold text-amber-600 dark:text-amber-400">最佳辩手</span>
                  </div>
                  <div className="flex items-center gap-2">
                    <div
                      className="flex h-8 w-8 items-center justify-center rounded-full text-sm font-bold text-white"
                      style={{ backgroundColor: color }}
                    >
                      {persIcon}
                    </div>
                    <div className="flex items-baseline gap-1.5">
                      <span className="text-sm font-semibold" style={{ color }}>{displayName}</span>
                      {persTitle && <span className="text-xs text-muted-foreground">{persTitle}</span>}
                    </div>
                  </div>
                </div>
              )
            })()}
            <StructuredReport content={report.content} />
          </div>
        )}

        {report?.status === 'FAILED' && (
          <div className="text-center py-6">
            <p className="text-xs text-red-500 mb-2">报告生成失败：{report.errorMessage || '未知错误'}</p>
            {isOwner !== false && (
              <button
                onClick={onTrigger}
                className="inline-flex items-center gap-1.5 rounded-xl bg-gradient-to-r from-brand-400 to-brand-500 px-4 py-2 text-xs font-medium text-white shadow-sm active:scale-[0.97] transition-transform"
              >
                <RefreshCw className="h-3 w-3" />
                重新生成
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
