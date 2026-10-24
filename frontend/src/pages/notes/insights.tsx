import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ArrowLeft, ChevronDown, Sparkles } from 'lucide-react'
import { generateInsight, listInsights, type NoteInsightVO } from '@/api/note'
import { formatRelativeTime } from '@/utils/time'
import { useGoBack } from '@/hooks/useGoBack'
import { cn } from '@/utils/cn'
import MarkdownRenderer from '@/components/ui/markdown-renderer'

const PERIODS = [
  { value: '7', label: '近 7 天', days: 7 },
  { value: '30', label: '近 30 天', days: 30 },
  { value: '90', label: '近 90 天', days: 90 },
  { value: '365', label: '近一年', days: 365 },
]

function toLocalIso(d: Date) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

export default function NoteInsightsPage() {
  const goBack = useGoBack()
  const queryClient = useQueryClient()
  const [period, setPeriod] = useState('30')
  const [expanded, setExpanded] = useState<number | null>(null)

  const { data: insights, isLoading } = useQuery({
    queryKey: ['notes', 'insights'],
    queryFn: listInsights,
  })

  const generateMutation = useMutation({
    mutationFn: () => {
      const cfg = PERIODS.find((p) => p.value === period) ?? PERIODS[1]
      const from = new Date()
      from.setDate(from.getDate() - cfg.days)
      return generateInsight({ from: toLocalIso(from) })
    },
    onSuccess: (insight: NoteInsightVO) => {
      queryClient.invalidateQueries({ queryKey: ['notes', 'insights'] })
      setExpanded(insight.id)
      toast.success('成长总结已生成')
    },
    onError: (err: Error) => toast.error(err.message || '生成失败'),
  })

  return (
    <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col overflow-hidden bg-background page-enter">
      <header className="shrink-0 flex items-center gap-3 border-b border-border/50 bg-navbar/95 px-4 py-3 backdrop-blur-xl z-20">
        <button
          onClick={() => goBack()}
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl transition-colors hover:bg-muted"
          aria-label="返回"
        >
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="flex-1 truncate text-base font-bold">成长总结</h1>
      </header>

      <div className="flex-1 overflow-y-auto overscroll-contain px-4 py-4 pb-20 md:pb-8">
        <div className="mx-auto w-full max-w-2xl">
          <div className="rounded-2xl border border-border/50 bg-card p-4">
            <p className="flex items-center gap-1.5 text-sm font-semibold">
              <Sparkles className="h-4 w-4 text-primary" />
              让 AI 读一遍你的笔记
            </p>
            <p className="mt-1 text-xs text-muted-foreground">选一段时间，看看自己在想什么、变了什么</p>
            <div className="mt-3 flex flex-wrap gap-1.5">
              {PERIODS.map((p) => (
                <button
                  key={p.value}
                  onClick={() => setPeriod(p.value)}
                  className={cn(
                    'rounded-full px-3 py-1 text-xs font-medium transition-colors',
                    period === p.value ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground'
                  )}
                >
                  {p.label}
                </button>
              ))}
            </div>
            <button
              onClick={() => generateMutation.mutate()}
              disabled={generateMutation.isPending}
              className="mt-3 h-11 w-full rounded-xl bg-primary text-sm font-semibold text-primary-foreground transition-transform active:scale-[0.98] disabled:opacity-60"
            >
              {generateMutation.isPending ? 'AI 正在读你的笔记…' : '生成成长总结'}
            </button>
          </div>

          <div className="mt-5 space-y-3">
            {isLoading ? (
              [0, 1].map((i) => <div key={i} className="h-24 animate-pulse rounded-2xl bg-muted" />)
            ) : !insights || insights.length === 0 ? (
              <p className="py-10 text-center text-sm text-muted-foreground">还没有生成过总结</p>
            ) : (
              insights.map((insight) => {
                const open = expanded === insight.id
                return (
                  <div key={insight.id} className="rounded-2xl border border-border/50 bg-card">
                    <button
                      onClick={() => setExpanded(open ? null : insight.id)}
                      className="flex w-full items-center gap-2 p-4 text-left"
                    >
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-semibold">{insight.title ?? '成长总结'}</p>
                        <p className="mt-0.5 text-xs text-muted-foreground">
                          {insight.noteCount ?? 0} 条笔记 · {formatRelativeTime(insight.createdAt)}
                        </p>
                      </div>
                      <ChevronDown className={cn('h-4 w-4 shrink-0 text-muted-foreground transition-transform', open && 'rotate-180')} />
                    </button>
                    {open && insight.contentMd && (
                      <div className="border-t border-border/50 px-4 py-3">
                        <MarkdownRenderer content={insight.contentMd} className="text-sm leading-relaxed" />
                      </div>
                    )}
                  </div>
                )
              })
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
