import { RefreshCw, Loader2, Target, BookOpen, Tag, Sparkles, CheckCircle, AlertTriangle, Lightbulb } from 'lucide-react'
import { Sheet, SheetContent } from '@/components/ui/sheet'
import { useQuery } from '@tanstack/react-query'
import { getRoundTableCoverage } from '@/api/roundTable'
import type { RoundTableCoverage, BlockCoverageDetail } from '@/types/roundTable'
import MarkdownRenderer from '@/components/ui/markdown-renderer'

interface CoveragePanelProps {
  sessionId: string | null
  open: boolean
  onClose: () => void
  isMobile: boolean
  /** 递增版本号，变化时自动刷新数据 */
  version?: number
}

const GRADE_STYLES: Record<string, { cls: string; bg: string; border: string }> = {
  S: { cls: 'text-brand-500',    bg: 'bg-brand-500/5',    border: 'border-brand-500/30' },
  A: { cls: 'text-green-500',     bg: 'bg-green-500/5',    border: 'border-green-500/30' },
  B: { cls: 'text-blue-500',      bg: 'bg-blue-500/5',    border: 'border-blue-500/30' },
  C: { cls: 'text-amber-500',     bg: 'bg-amber-500/5',   border: 'border-amber-500/30' },
  D: { cls: 'text-orange-500',    bg: 'bg-orange-500/5',  border: 'border-orange-500/30' },
  F: { cls: 'text-muted-foreground', bg: 'bg-muted',      border: 'border-muted' },
}

const LEVEL_LABELS: Record<number, { text: string; rowBg: string; rowBorder: string; tagBg: string; tagColor: string }> = {
  0: { text: '未覆盖',   rowBg: 'bg-muted/20',    rowBorder: 'border-muted/30',  tagBg: 'bg-muted',          tagColor: 'text-muted-foreground' },
  1: { text: '提及',     rowBg: 'bg-amber-500/5', rowBorder: 'border-amber-500/20', tagBg: 'bg-amber-500/10', tagColor: 'text-amber-600 dark:text-amber-400' },
  2: { text: '部分讨论', rowBg: 'bg-blue-500/5',  rowBorder: 'border-blue-500/20',  tagBg: 'bg-blue-500/10',  tagColor: 'text-blue-600 dark:text-blue-400' },
  3: { text: '深入讨论', rowBg: 'bg-green-500/5', rowBorder: 'border-green-500/20', tagBg: 'bg-green-500/10', tagColor: 'text-green-600 dark:text-green-400' },
}

export default function CoveragePanel({ sessionId, open, onClose, isMobile, version }: CoveragePanelProps) {
  const {
    data: coverage = null,
    isLoading: loading,
    isRefetching,
    refetch,
  } = useQuery({
    queryKey: ['round-table', 'coverage', sessionId, version],
    queryFn: () => getRoundTableCoverage(sessionId!) as Promise<RoundTableCoverage>,
    enabled: open && !!sessionId,
  })

  if (!open) return null

  const blockDetails: BlockCoverageDetail[] = coverage?.blockDetailsJson
    ? safeParseJson<BlockCoverageDetail[]>(coverage.blockDetailsJson) ?? []
    : []
  const coveredConcepts: string[] = coverage?.coveredConceptsJson
    ? safeParseJson<string[]>(coverage.coveredConceptsJson) ?? []
    : []
  const missedConcepts: string[] = coverage?.missedConceptsJson
    ? safeParseJson<string[]>(coverage.missedConceptsJson) ?? []
    : []
  const llmDimensions: Record<string, number> = coverage?.llmDimensionsJson
    ? safeParseJson<Record<string, number>>(coverage.llmDimensionsJson) ?? {}
    : {}
  const llmStrengths: string[] = coverage?.llmStrengthsJson
    ? safeParseJson<string[]>(coverage.llmStrengthsJson) ?? []
    : []
  const llmWeaknesses: string[] = coverage?.llmWeaknessesJson
    ? safeParseJson<string[]>(coverage.llmWeaknessesJson) ?? []
    : []
  const llmSuggestions: string[] = coverage?.llmSuggestionsJson
    ? safeParseJson<string[]>(coverage.llmSuggestionsJson) ?? []
    : []

  const overallScore = coverage?.overallScore ?? 0
  const grade = coverage?.grade ?? '-'
  const gradeStyle = GRADE_STYLES[grade] ?? GRADE_STYLES['F']
  const hasLlmData = Object.keys(llmDimensions).length > 0

  const content = (
    <div className="flex flex-col flex-1 min-h-0">
      {/* 头部 */}
      <div className="shrink-0 flex items-center justify-between px-4 py-3 border-b border-border/20">
        <h3 className="text-xs font-bold flex items-center gap-1.5">
          <Target className="h-3.5 w-3.5 text-brand-500" />
          话题覆盖度
        </h3>
        <div className="flex items-center gap-2 mr-7">
          <button
            onClick={() => refetch()}
            disabled={isRefetching}
            className="flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors disabled:opacity-50"
          >
            <RefreshCw className={`h-3 w-3 ${isRefetching ? 'animate-spin' : ''}`} />
            刷新
          </button>
        </div>
      </div>

      {/* 内容 */}
      <div className="flex-1 min-h-0 overflow-y-auto overscroll-y-contain px-4 py-3 space-y-4">
        {loading ? (
          <div className="flex flex-col items-center justify-center py-8 text-muted-foreground">
            <Loader2 className="h-5 w-5 animate-spin mb-2" />
            <span className="text-xs">加载覆盖度数据...</span>
          </div>
        ) : !coverage ? (
          <div className="flex flex-col items-center justify-center py-8 text-muted-foreground">
            <Target className="h-8 w-8 mb-2 opacity-30" />
            <span className="text-xs">暂无覆盖度数据</span>
            <span className="text-xs mt-1 opacity-60">讨论几轮后自动生成</span>
          </div>
        ) : (
          <>
            {/* 综合评分 */}
            <div className="flex items-center gap-4">
              <div className={`flex h-14 w-14 shrink-0 items-center justify-center rounded-2xl text-xl font-black border-2 ${gradeStyle.cls} ${gradeStyle.bg} ${gradeStyle.border}`}>
                {grade}
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-baseline gap-1.5">
                  <span className={`text-2xl font-black ${gradeStyle.cls}`}>
                    {overallScore.toFixed(0)}
                  </span>
                  <span className="text-xs text-muted-foreground">/ 100</span>
                </div>
                <div className="mt-1 h-1.5 rounded-full bg-muted overflow-hidden">
                  <div
                    className={`h-full rounded-full transition-all duration-700 ${gradeStyle.cls.replace('text-', 'bg-')}`}
                    style={{ width: `${Math.min(100, overallScore)}%` }}
                  />
                </div>
                <div className="flex items-center gap-3 mt-1.5 text-xs text-muted-foreground">
                  <span>内容块 {coverage.coveredBlocks ?? 0}/{coverage.totalBlocks ?? 0}</span>
                  <span>概念 {coverage.coveredConceptsCount ?? 0}/{coverage.totalConcepts ?? 0}</span>
                </div>
              </div>
            </div>

            {/* LLM 评估维度 */}
            {hasLlmData && (
              <div>
                <h4 className="text-xs font-bold flex items-center gap-1 mb-2">
                  <Sparkles className="h-3 w-3 text-brand-500" />
                  评估维度
                </h4>
                <div className="space-y-2">
                  {Object.entries(llmDimensions).map(([name, score]) => {
                    const pct = (score / 10) * 100
                    return (
                      <div key={name}>
                        <div className="flex items-center justify-between mb-0.5">
                          <span className="text-xs font-medium text-foreground">{name}</span>
                          <span className="text-xs font-bold text-brand-500">{score.toFixed(1)}<span className="text-muted-foreground/50">/10</span></span>
                        </div>
                        <div className="h-1.5 rounded-full bg-muted overflow-hidden">
                          <div
                            className="h-full rounded-full transition-all duration-500 bg-brand-500"
                            style={{ width: `${pct}%` }}
                          />
                        </div>
                      </div>
                    )
                  })}
                </div>
              </div>
            )}

            {/* 强项 */}
            {llmStrengths.length > 0 && (
              <div>
                <h4 className="text-xs font-bold flex items-center gap-1 mb-1.5">
                  <CheckCircle className="h-3 w-3 text-green-500" />
                  强项
                </h4>
                <div className="space-y-1">
                  {llmStrengths.map((s, i) => (
                    <div key={i} className="flex items-start gap-1.5 rounded-lg px-2.5 py-1.5 bg-green-500/5 border border-green-500/15">
                      <span className="text-xs text-green-600 dark:text-green-400 mt-px shrink-0 font-bold">✓</span>
                      <MarkdownRenderer content={s} className="!text-xs !leading-relaxed" />
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* 不足 */}
            {llmWeaknesses.length > 0 && (
              <div>
                <h4 className="text-xs font-bold flex items-center gap-1 mb-1.5">
                  <AlertTriangle className="h-3 w-3 text-amber-500" />
                  不足
                </h4>
                <div className="space-y-1">
                  {llmWeaknesses.map((s, i) => (
                    <div key={i} className="flex items-start gap-1.5 rounded-lg px-2.5 py-1.5 bg-amber-500/5 border border-amber-500/15">
                      <span className="text-xs text-amber-600 dark:text-amber-400 mt-px shrink-0 font-bold">✗</span>
                      <MarkdownRenderer content={s} className="!text-xs !leading-relaxed" />
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* 改进建议 */}
            {llmSuggestions.length > 0 && (
              <div>
                <h4 className="text-xs font-bold flex items-center gap-1 mb-1.5">
                  <Lightbulb className="h-3 w-3 text-blue-500" />
                  改进建议
                </h4>
                <div className="space-y-1">
                  {llmSuggestions.map((s, i) => (
                    <div key={i} className="flex items-start gap-1.5 rounded-lg px-2.5 py-1.5 bg-blue-500/5 border border-blue-500/15">
                      <span className="text-xs text-blue-600 dark:text-blue-400 mt-px shrink-0 font-bold">→</span>
                      <MarkdownRenderer content={s} className="!text-xs !leading-relaxed" />
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* 内容块覆盖 */}
            {blockDetails.length > 0 && (
              <div>
                <h4 className="text-xs font-bold flex items-center gap-1 mb-2">
                  <BookOpen className="h-3 w-3 text-muted-foreground" />
                  内容块覆盖
                </h4>
                <div className="space-y-1.5">
                  {blockDetails.map((block, i) => {
                    const level = LEVEL_LABELS[block.coverageLevel] ?? LEVEL_LABELS[0]
                    return (
                      <div
                        key={i}
                        className={`flex items-center gap-2 rounded-lg px-2.5 py-1.5 border ${level.rowBg} ${level.rowBorder}`}
                      >
                        <span className={`text-xs font-medium flex-1 truncate ${level.tagColor}`}>
                          {block.title}
                        </span>
                        <span className={`shrink-0 rounded-full px-1.5 py-0.5 text-xs font-bold ${level.tagBg} ${level.tagColor}`}>
                          {level.text}
                        </span>
                      </div>
                    )
                  })}
                </div>
              </div>
            )}

            {/* 概念标签覆盖 */}
            {(coveredConcepts.length > 0 || missedConcepts.length > 0) && (
              <div>
                <h4 className="text-xs font-bold flex items-center gap-1 mb-2">
                  <Tag className="h-3 w-3 text-muted-foreground" />
                  概念标签
                </h4>
                {/* 未覆盖 */}
                {missedConcepts.length > 0 && (
                  <div className="mb-2">
                    <span className="text-xs text-muted-foreground mb-1 block">未覆盖</span>
                    <div className="flex flex-wrap gap-1">
                      {missedConcepts.map(tag => (
                        <span
                          key={tag}
                          className="rounded-full px-2 py-0.5 text-xs font-medium bg-red-500/8 text-red-600 dark:text-red-400 border border-red-500/15"
                        >
                          {tag}
                        </span>
                      ))}
                    </div>
                  </div>
                )}
                {/* 已覆盖 */}
                {coveredConcepts.length > 0 && (
                  <div>
                    <span className="text-xs text-muted-foreground mb-1 block">已覆盖</span>
                    <div className="flex flex-wrap gap-1">
                      {coveredConcepts.map(tag => (
                        <span
                          key={tag}
                          className="rounded-full px-2 py-0.5 text-xs font-medium bg-green-500/8 text-green-600 dark:text-green-400 border border-green-500/15"
                        >
                          {tag}
                        </span>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            )}

            {/* LLM 数据为空时的提示 */}
            {!hasLlmData && (
              <div className="text-center py-2 text-xs text-muted-foreground/50">
                讨论 6 轮后将生成评估维度分析
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )

  // PC: 右侧面板
  if (!isMobile) {
    return (
      <div className="flex-1 min-h-0 w-80 border-l border-border/20 bg-navbar/95 backdrop-blur-xl animate-in slide-in-from-right duration-200 flex flex-col overflow-hidden">
        {content}
      </div>
    )
  }

  // 手机: 底部抽屉
  return (
    <Sheet open={open} onOpenChange={onClose}>
      <SheetContent side="bottom" className="rounded-t-2xl p-0 max-h-[85vh] [&>button]:hidden">
        {content}
      </SheetContent>
    </Sheet>
  )
}

function safeParseJson<T>(json: string): T | null {
  try {
    return JSON.parse(json)
  } catch {
    return null
  }
}
