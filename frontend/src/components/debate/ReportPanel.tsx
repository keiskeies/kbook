import { FileText, Loader2, RefreshCw, Trophy } from 'lucide-react'
import MarkdownRenderer from '@/components/ui/markdown-renderer'
import type { DebateReport } from '@/types/debate'
import { DEBATE_ROLE_COLORS, DEBATE_ROLE_NAMES, DEBATE_PERSONALITY_NAMES, DEBATE_PERSONALITY_COLORS } from '@/types/debate'

interface ReportPanelProps {
  report: DebateReport | null
  isGenerating: boolean
  onTrigger: () => void
  onClose: () => void
  isMobile?: boolean
}

/**
 * 辩论报告面板 — 展示 AI 生成的完整辩论评审报告
 */
export default function ReportPanel({ report, isGenerating, onTrigger, onClose, isMobile }: ReportPanelProps) {
  return (
    <div className="flex flex-col min-h-0 h-full">
      <div className="shrink-0 flex items-center justify-between px-4 py-3 border-b border-border/20">
        <h3 className="text-xs font-bold flex items-center gap-1.5">
          <FileText className="h-3.5 w-3.5 text-brand-500" />
          辩论报告
        </h3>
        {!isMobile && (
          <button onClick={onClose} className="text-[10px] text-muted-foreground hover:text-foreground">关闭</button>
        )}
      </div>

      <div className="flex-1 min-h-0 overflow-y-auto overscroll-contain px-4 py-3">
        {!report && !isGenerating && (
          <div className="text-center py-6">
            <p className="text-xs text-muted-foreground mb-3">辩论结束后，AI 将生成一份完整的评审报告</p>
            <button
              onClick={onTrigger}
              className="inline-flex items-center gap-1.5 rounded-xl bg-gradient-to-r from-brand-400 to-brand-500 px-4 py-2 text-xs font-medium text-white shadow-sm active:scale-[0.97] transition-transform"
            >
              <FileText className="h-3 w-3" />
              生成辩论报告
            </button>
            <p className="text-[10px] text-muted-foreground/60 mt-2">预计 2-3 分钟</p>
          </div>
        )}

        {isGenerating && (
          <div className="text-center py-6">
            <Loader2 className="h-6 w-6 animate-spin text-brand-500 mx-auto mb-2" />
            <p className="text-xs text-muted-foreground">AI 正在分析辩论内容...</p>
            <p className="text-[10px] text-muted-foreground/60 mt-1">预计 2-3 分钟</p>
          </div>
        )}

        {report?.status === 'COMPLETED' && report.content && (
          <div>
            {report.bestDebater && (() => {
              const key = report.bestDebater
              const posName = DEBATE_ROLE_NAMES[key] || ''
              const persName = DEBATE_PERSONALITY_NAMES[key] || ''
              const color = DEBATE_ROLE_COLORS[key] || DEBATE_PERSONALITY_COLORS[key] || '#D4A843'
              const displayName = posName || persName || key
              const subtitle = posName && persName ? persName : ''
              const initial = posName ? posName.replace(/[方\d辩]/g, '') : key.charAt(0)
              return (
              <div className="mb-4 rounded-xl border border-amber-200 dark:border-amber-800 bg-gradient-to-br from-amber-50 to-brand-50 dark:from-amber-950/30 dark:to-brand-950/20 p-4">
                <div className="flex items-center gap-2 mb-1">
                  <Trophy className="h-4 w-4 text-amber-500" />
                  <span className="text-xs font-bold text-amber-700 dark:text-amber-400">最佳辩手</span>
                </div>
                <div className="flex items-center gap-2">
                  <div
                    className="flex h-8 w-8 items-center justify-center rounded-full text-sm font-bold text-white"
                    style={{ backgroundColor: color }}
                  >
                    {initial}
                  </div>
                  <div>
                    <span className="text-sm font-semibold" style={{ color }}>{displayName}</span>
                    {subtitle && <span className="text-[10px] text-muted-foreground ml-1.5">{subtitle}</span>}
                  </div>
                </div>
              </div>
              )
            })()}
            <div className="[&_h2]:border-border/30 [&_h2]:pb-1.5 [&_h2]:mb-3 [&_h2]:!text-base
                          [&_h3]:!text-sm [&_h3]:mb-2
                          [&_p]:my-2
                          [&_blockquote]:!border-l-4 [&_blockquote]:!border-brand-300 [&_blockquote]:!bg-brand-50/50 dark:[&_blockquote]:!bg-brand-500/10 [&_blockquote]:pl-4
                          [&_strong]:!text-brand-600 dark:[&_strong]:!text-brand-400
                          [&_li]:my-0.5
                          [&_hr]:!my-4
                          [&_ul]:my-2 [&_ol]:my-2
                          [&_.table-scroll-wrapper]:-mx-4 [&_.table-scroll]:px-4">
            <MarkdownRenderer content={report.content} className="!text-[14px] !leading-relaxed" />
          </div>
          </div>
        )}

        {report?.status === 'FAILED' && (
          <div className="text-center py-6">
            <p className="text-xs text-red-500 mb-2">报告生成失败：{report.errorMessage || '未知错误'}</p>
            <button
              onClick={onTrigger}
              className="inline-flex items-center gap-1.5 rounded-xl bg-gradient-to-r from-brand-400 to-brand-500 px-4 py-2 text-xs font-medium text-white shadow-sm active:scale-[0.97] transition-transform"
            >
              <RefreshCw className="h-3 w-3" />
              重新生成
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
