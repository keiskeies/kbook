import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { ArrowLeft, ThumbsDown, CheckCircle2, Undo2, XCircle, Loader2, MessageSquareWarning } from 'lucide-react'
import { toast } from 'sonner'
import {
  getFeedbackQueue, getFeedbackStats, reviewFeedback,
  type FeedbackQueueItem, type ReviewStatus,
} from '@/api/adminBookQa'
import { ROUTES } from '@/constants'

const STATUS_TABS: { value: string; label: string }[] = [
  { value: 'QUEUED', label: '待标注' },
  { value: 'ANNOTATED', label: '已纳入黄金集' },
  { value: 'DISMISSED', label: '已忽略' },
  { value: 'PENDING', label: '好评 / 未入队' },
]

const REASON_LABEL: Record<string, string> = {
  WRONG: '答错了',
  NOT_GROUNDED: '没有依据',
  OFF_TOPIC: '答非所问',
  TOO_LONG: '太啰嗦',
  OTHER: '其他',
}

const MODE_LABEL: Record<string, string> = {
  small: '全书档',
  medium: '章节档',
  large: '检索档',
  unknown: '未知',
}

/** 反馈标注队列：把生产里的差评沉淀成评测黄金集样本。 */
export default function AdminBookQaFeedbackPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [status, setStatus] = useState('QUEUED')
  const [page, setPage] = useState(0)
  const size = 20

  const { data: stats } = useQuery({
    queryKey: ['admin', 'book-qa-feedback', 'stats'],
    queryFn: () => getFeedbackStats(),
  })
  const { data: queue, isLoading, isFetching } = useQuery({
    queryKey: ['admin', 'book-qa-feedback', 'queue', status, page],
    queryFn: () => getFeedbackQueue(status, page, size),
  })

  const review = useMutation({
    mutationFn: ({ id, next, note }: { id: number; next: ReviewStatus; note?: string }) =>
      reviewFeedback(id, next, note),
    onSuccess: (_, vars) => {
      toast.success(vars.next === 'ANNOTATED' ? '已标记为纳入黄金集' : vars.next === 'DISMISSED' ? '已忽略该反馈' : '已退回队列')
      queryClient.invalidateQueries({ queryKey: ['admin', 'book-qa-feedback'] })
    },
    onError: (e: Error) => toast.error(e.message || '操作失败'),
  })

  const switchTab = (next: string) => { setStatus(next); setPage(0) }
  const total = queue?.total ?? 0
  const maxPage = Math.max(0, Math.ceil(total / size) - 1)

  return (
    <div className="absolute inset-0 flex flex-col overflow-hidden bg-background overscroll-contain">
      <header className="z-20 flex shrink-0 items-center gap-3 border-b bg-navbar/95 px-4 py-3 backdrop-blur md:px-6 lg:px-8">
        <button onClick={() => navigate(ROUTES.ADMIN_DASHBOARD)} className="rounded-full p-1.5 active:bg-muted" title="返回">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="flex-1 text-h3 font-bold">问答反馈标注</h1>
        <MessageSquareWarning className="h-4 w-4 text-muted-foreground" />
      </header>

      <main className="flex-1 overflow-y-auto overscroll-contain px-4 py-4 md:px-6 lg:px-8">
        <div className="mx-auto max-w-5xl space-y-5">
          <section className="rounded-xl border border-border bg-card p-4 shadow-xs">
            <h2 className="mb-1.5 text-sm font-bold">差评回流 → 待标注队列 → 黄金集</h2>
            <p className="text-xs leading-relaxed text-muted-foreground">
              用户在图书问答里点「没用」并选择原因后，样本会自动进入「待标注」。
              确属回答质量问题的标为「已纳入黄金集」，并补进 <code className="rounded bg-muted px-1">docs/eval/golden-set.real.jsonl</code>；
              误报直接忽略。按上下文档位（全书 / 章节 / 检索）分组对比差评率，就能判断分档路由有没有退步。
            </p>
          </section>

          <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-4">
            {[
              { label: '总反馈', value: stats?.total ?? 0 },
              { label: '好评', value: stats?.up ?? 0 },
              { label: '差评', value: stats?.down ?? 0 },
              { label: '差评率', value: `${((stats?.downRatio ?? 0) * 100).toFixed(1)}%` },
            ].map((c) => (
              <div key={c.label} className="rounded-xl border border-border bg-card p-3">
                <p className="text-xs text-muted-foreground">{c.label}</p>
                <p className="mt-0.5 text-xl font-bold">{c.value}</p>
              </div>
            ))}
          </div>

          <div className="flex flex-wrap gap-1.5">
            {STATUS_TABS.map((t) => (
              <button
                key={t.value}
                onClick={() => switchTab(t.value)}
                className={`rounded-lg px-3 py-1.5 text-xs font-medium transition-colors ${
                  status === t.value
                    ? 'bg-primary text-primary-foreground'
                    : 'border border-border text-muted-foreground hover:bg-muted'
                }`}
              >
                {t.label}
                {t.value === 'QUEUED' && stats?.queued ? ` (${stats.queued})` : ''}
                {t.value === 'ANNOTATED' && stats?.annotated ? ` (${stats.annotated})` : ''}
              </button>
            ))}
          </div>

          {isLoading ? (
            <div className="flex items-center justify-center gap-2 py-12 text-sm text-muted-foreground">
              <Loader2 className="h-4 w-4 animate-spin" /> 加载中…
            </div>
          ) : !queue?.list?.length ? (
            <div className="rounded-xl border border-dashed border-border py-12 text-center text-sm text-muted-foreground">
              这个状态下没有反馈样本
            </div>
          ) : (
            <div className={`space-y-3 transition-opacity ${isFetching ? 'opacity-60' : ''}`}>
              {queue.list.map((item: FeedbackQueueItem) => (
                <article key={item.id} className="rounded-xl border border-border bg-card p-3.5">
                  <div className="mb-2 flex flex-wrap items-center gap-1.5 text-xs">
                    <span className="inline-flex items-center gap-1 rounded-md bg-destructive/10 px-1.5 py-0.5 font-medium text-destructive">
                      <ThumbsDown className="h-3 w-3" />{REASON_LABEL[item.reason || ''] || item.reason || '差评'}
                    </span>
                    {item.contextMode && (
                      <span className="rounded-md bg-muted px-1.5 py-0.5 text-muted-foreground">
                        {MODE_LABEL[item.contextMode] || item.contextMode}
                      </span>
                    )}
                    <span className="text-muted-foreground">《{item.bookTitle || `书 ${item.bookId}`}》</span>
                    <span className="text-muted-foreground">· 回答 #{item.conversationId}</span>
                    {item.createdAt && (
                      <span className="ml-auto text-muted-foreground">{item.createdAt.replace('T', ' ').slice(0, 16)}</span>
                    )}
                  </div>

                  {item.question && (
                    <p className="mb-1 text-sm font-medium"><span className="text-muted-foreground">问：</span>{item.question}</p>
                  )}
                  {item.answerPreview && (
                    <p className="mb-1 line-clamp-3 text-xs leading-relaxed text-muted-foreground">
                      <span className="font-medium text-foreground">答：</span>{item.answerPreview}
                    </p>
                  )}
                  {item.comment && (
                    <p className="mb-2 rounded-md bg-muted/60 px-2 py-1 text-xs">用户补充：{item.comment}</p>
                  )}
                  {item.reviewNote && (
                    <p className="mb-2 text-xs text-muted-foreground">标注备注：{item.reviewNote}</p>
                  )}

                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {status !== 'ANNOTATED' && (
                      <button
                        disabled={review.isPending}
                        onClick={() => review.mutate({ id: item.id, next: 'ANNOTATED', note: '纳入黄金集' })}
                        className="inline-flex items-center gap-1 rounded-md border border-primary/30 bg-primary/5 px-2 py-1 text-xs text-primary hover:bg-primary/10 active:scale-95 disabled:opacity-50"
                      >
                        <CheckCircle2 className="h-3.5 w-3.5" />纳入黄金集
                      </button>
                    )}
                    {status !== 'DISMISSED' && (
                      <button
                        disabled={review.isPending}
                        onClick={() => review.mutate({ id: item.id, next: 'DISMISSED', note: '误报' })}
                        className="inline-flex items-center gap-1 rounded-md border border-border px-2 py-1 text-xs text-muted-foreground hover:bg-muted active:scale-95 disabled:opacity-50"
                      >
                        <XCircle className="h-3.5 w-3.5" />忽略
                      </button>
                    )}
                    {status !== 'QUEUED' && (
                      <button
                        disabled={review.isPending}
                        onClick={() => review.mutate({ id: item.id, next: 'QUEUED' })}
                        className="inline-flex items-center gap-1 rounded-md border border-border px-2 py-1 text-xs text-muted-foreground hover:bg-muted active:scale-95 disabled:opacity-50"
                      >
                        <Undo2 className="h-3.5 w-3.5" />退回队列
                      </button>
                    )}
                  </div>
                </article>
              ))}
            </div>
          )}

          {maxPage > 0 && (
            <div className="flex items-center justify-center gap-3 pb-6 text-xs text-muted-foreground">
              <button
                disabled={page <= 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                className="rounded-md border border-border px-2.5 py-1 disabled:opacity-40"
              >上一页</button>
              <span>第 {page + 1} / {maxPage + 1} 页 · 共 {total} 条</span>
              <button
                disabled={page >= maxPage}
                onClick={() => setPage((p) => Math.min(maxPage, p + 1))}
                className="rounded-md border border-border px-2.5 py-1 disabled:opacity-40"
              >下一页</button>
            </div>
          )}
        </div>
      </main>
    </div>
  )
}
