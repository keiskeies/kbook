import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  ChevronRight,
  Flame,
  History,
  NotebookPen,
  PenLine,
  Search,
  Sparkles,
  X,
} from 'lucide-react'
import {
  createNote,
  getNoteStats,
  getThrowback,
  getTimeline,
  searchNotes,
  semanticSearchNotes,
  type NoteStatsVO,
  type NoteType,
  type NoteVO,
} from '@/api/note'
import { NoteCard } from '@/components/note/NoteCard'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  Input,
  Textarea,
} from '@/components/ui'
import { ROUTES } from '@/constants'
import { formatRelativeTime } from '@/utils/time'
import { cn } from '@/utils/cn'

const FILTERS: { value: NoteType | 'ALL'; label: string; countKey: keyof NoteStatsVO }[] = [
  { value: 'ALL', label: '全部', countKey: 'total' },
  { value: 'THOUGHT', label: '想法', countKey: 'thoughtCount' },
  { value: 'REVIEW_CAPTURE', label: '收录', countKey: 'captureCount' },
  { value: 'EXCERPT', label: '摘录', countKey: 'excerptCount' },
]

const RANGES: { value: string; label: string; days: number | null }[] = [
  { value: 'ALL', label: '全部时间', days: null },
  { value: '7', label: '近 7 天', days: 7 },
  { value: '30', label: '近 30 天', days: 30 },
  { value: '365', label: '近一年', days: 365 },
]

/** Date → 本地 ISO(YYYY-MM-DDTHH:mm:ss)，避免带 Z 的 UTC 串让后端 LocalDateTime 解析失败 */
function toLocalIso(d: Date) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function dayKey(iso: string) {
  return iso.slice(0, 10)
}

function dayLabel(key: string) {
  const today = new Date()
  const d = new Date(key)
  const diff = Math.round((today.setHours(0, 0, 0, 0) - new Date(key).setHours(0, 0, 0, 0)) / 86400000)
  if (diff === 0) return '今天'
  if (diff === 1) return '昨天'
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`
}

function notePreview(note: NoteVO) {
  if (note.contentMd?.trim()) return note.contentMd.trim()
  const s = note.snapshot
  if (!s) return '（这条笔记没有正文）'
  return s.excerpt?.trim() || s.sourceContent?.trim() || s.sourceTitle?.trim() || '（这条笔记没有正文）'
}

function StatsCard({ stats }: { stats: NoteStatsVO }) {
  return (
    <div className="rounded-2xl border border-border/50 bg-gradient-to-br from-primary/10 via-card to-card p-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="text-xs text-muted-foreground">你的笔记</p>
          <p className="mt-0.5 text-2xl font-bold tabular-nums">
            {stats.total}
            <span className="ml-1 text-xs font-normal text-muted-foreground">条</span>
          </p>
        </div>
        <span className="flex shrink-0 items-center gap-1 rounded-full bg-orange-500/10 px-2.5 py-1 text-xs font-semibold text-orange-600">
          <Flame className="h-3.5 w-3.5" />
          {stats.streakDays > 0 ? `连续 ${stats.streakDays} 天` : '今天记一笔吧'}
        </span>
      </div>
      <div className="mt-3 flex items-center gap-4 text-xs text-muted-foreground">
        <span>本周 <b className="text-foreground">+{stats.weekCount}</b></span>
        <span className="h-3 w-px bg-border" />
        <span>涉及 <b className="text-foreground">{stats.bookCount}</b> 本书</span>
      </div>
    </div>
  )
}

function InsightEntry({ onClick }: { onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className="flex w-full items-center gap-3 rounded-2xl border border-primary/20 bg-gradient-to-r from-primary/10 to-transparent p-4 text-left transition-transform active:scale-[0.99]"
    >
      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-primary text-primary-foreground">
        <Sparkles className="h-5 w-5" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block text-sm font-semibold">让 AI 看看这段时间的你</span>
        <span className="block text-xs text-muted-foreground">根据笔记生成阶段性成长总结</span>
      </span>
      <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
    </button>
  )
}

function ThrowbackCard({ note, onOpen, onDismiss }: { note: NoteVO; onOpen: () => void; onDismiss: () => void }) {
  return (
    <div className="relative rounded-2xl border border-border/50 bg-card p-4">
      <button
        onClick={onDismiss}
        className="absolute right-2 top-2 flex h-7 w-7 items-center justify-center rounded-lg text-muted-foreground/60 transition-colors hover:bg-muted"
        aria-label="关闭时光机"
      >
        <X className="h-3.5 w-3.5" />
      </button>
      <p className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <History className="h-3.5 w-3.5" />
        时光机 · {formatRelativeTime(note.occurredAt)}
      </p>
      {note.bookTitle && <p className="mt-1 truncate text-xs text-muted-foreground">《{note.bookTitle}》</p>}
      <p className="mt-2 line-clamp-3 whitespace-pre-wrap text-sm leading-relaxed">{notePreview(note)}</p>
      <button onClick={onOpen} className="mt-2 text-xs font-semibold text-primary">再看看 →</button>
    </div>
  )
}

export default function NotesPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [type, setType] = useState<NoteType | 'ALL'>('ALL')
  const [range, setRange] = useState('ALL')
  const [keyword, setKeyword] = useState('')
  const [semantic, setSemantic] = useState(false)
  const [hideThrowback, setHideThrowback] = useState(false)
  const [composerOpen, setComposerOpen] = useState(false)
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')

  const query = keyword.trim() ? keyword.trim() : ''
  const rangeFrom = useMemo(() => {
    const cfg = RANGES.find((r) => r.value === range)
    if (!cfg?.days) return undefined
    const d = new Date()
    d.setDate(d.getDate() - cfg.days)
    return toLocalIso(d)
  }, [range])

  const { data: stats } = useQuery({
    queryKey: ['notes', 'stats'],
    queryFn: getNoteStats,
    staleTime: 60_000,
  })

  const { data: throwback } = useQuery({
    queryKey: ['notes', 'throwback'],
    queryFn: getThrowback,
    enabled: !!stats && stats.total > 0,
    staleTime: 60_000,
  })

  const { data, fetchNextPage, hasNextPage, isFetchingNextPage, isLoading } = useInfiniteQuery({
    queryKey: ['notes', 'timeline', type, range, query],
    queryFn: ({ pageParam }) =>
      query
        ? searchNotes(query, pageParam, 20)
        : getTimeline({ type: type === 'ALL' ? undefined : type, from: rangeFrom, page: pageParam, size: 20 }),
    initialPageParam: 1,
    enabled: !(semantic && !!query),
    getNextPageParam: (lastPage) => {
      const loaded = lastPage.page * lastPage.size
      return loaded < lastPage.total ? lastPage.page + 1 : undefined
    },
  })

  const semanticMode = semantic && !!query
  const { data: semanticNotes, isFetching: semanticLoading } = useQuery({
    queryKey: ['notes', 'semantic', query],
    queryFn: () => semanticSearchNotes(query, 20),
    enabled: semanticMode,
  })

  const notes = useMemo<NoteVO[]>(() => data?.pages.flatMap((p) => p.list) ?? [], [data])
  const total = data?.pages[0]?.total ?? 0

  const displayNotes = semanticMode ? (semanticNotes ?? []) : notes
  const displayTotal = semanticMode ? displayNotes.length : total
  const loading = semanticMode ? semanticLoading : isLoading

  const groups = useMemo(() => {
    const out: { key: string; items: NoteVO[] }[] = []
    for (const n of displayNotes) {
      const k = dayKey(n.occurredAt)
      const last = out[out.length - 1]
      if (last && last.key === k) last.items.push(n)
      else out.push({ key: k, items: [n] })
    }
    return out
  }, [displayNotes])

  const createMutation = useMutation({
    mutationFn: () => createNote({ title: title.trim() || null, contentMd: content.trim() || null }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notes', 'timeline'] })
      queryClient.invalidateQueries({ queryKey: ['notes', 'stats'] })
      queryClient.invalidateQueries({ queryKey: ['notes', 'throwback'] })
      queryClient.invalidateQueries({ queryKey: ['notes', 'semantic'] })
      queryClient.invalidateQueries({ queryKey: ['notes', 'count'] })
      toast.success('记下了')
      setComposerOpen(false)
      setTitle('')
      setContent('')
    },
    onError: (err: Error) => toast.error(err.message || '保存失败'),
  })

  const showExtras = !query && !!stats && stats.total > 0
  const showThrowback = showExtras && !hideThrowback && type === 'ALL' && range === 'ALL' && !!throwback
  const isFiltered = type !== 'ALL' || range !== 'ALL'

  return (
    <div className="flex flex-col">
      <header className="sticky top-0 z-20 border-b border-border/50 bg-navbar/95 px-4 py-3 backdrop-blur-xl">
        <div className="flex items-center gap-2">
          <h1 className="text-base font-bold">笔记</h1>
          {!query && displayTotal > 0 && <span className="text-xs text-muted-foreground">{displayTotal} 条</span>}
          {query && <span className="text-xs text-muted-foreground">搜索到 {displayTotal} 条</span>}
          <div className="ml-auto flex items-center gap-1.5">
            <button
              onClick={() => setComposerOpen(true)}
              className="flex items-center gap-1.5 rounded-xl bg-primary px-3 py-1.5 text-xs font-semibold text-primary-foreground active:scale-95 transition-transform"
            >
              <PenLine className="h-3.5 w-3.5" />
              记一笔
            </button>
          </div>
        </div>

        <div className="mt-2.5 flex items-center gap-2 rounded-xl bg-muted px-3 py-2">
          <Search className="h-4 w-4 shrink-0 text-muted-foreground" />
          <input
            value={keyword}
            onChange={(e) => {
              const v = e.target.value
              setKeyword(v)
              if (!v.trim()) setSemantic(false)
            }}
            placeholder="搜索我的笔记"
            className="flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground/60"
          />
          {query && (
            <button
              onClick={() => setSemantic((v) => !v)}
              className={cn(
                'shrink-0 rounded-full px-2 py-0.5 text-[11px] font-medium transition-colors',
                semantic ? 'bg-primary text-primary-foreground' : 'bg-background text-muted-foreground'
              )}
              title="按语义相关度搜索"
            >
              语义
            </button>
          )}
          {keyword && (
            <button onClick={() => { setKeyword(''); setSemantic(false) }} aria-label="清除搜索">
              <X className="h-4 w-4 text-muted-foreground" />
            </button>
          )}
        </div>

        {!query && (
          <>
            <div className="mt-2.5 flex gap-1.5">
              {FILTERS.map((f) => {
                const count = range === 'ALL' ? stats?.[f.countKey] : undefined
                return (
                  <button
                    key={f.value}
                    onClick={() => setType(f.value)}
                    className={cn(
                      'rounded-full px-3 py-1 text-xs font-medium transition-colors',
                      type === f.value ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground'
                    )}
                  >
                    {f.label}
                    {count != null && <span className="ml-1 opacity-60">{count}</span>}
                  </button>
                )
              })}
            </div>
            <div className="mt-1.5 flex gap-1.5 overflow-x-auto">
              {RANGES.map((r) => (
                <button
                  key={r.value}
                  onClick={() => setRange(r.value)}
                  className={cn(
                    'shrink-0 rounded-full px-3 py-1 text-xs font-medium transition-colors',
                    range === r.value ? 'bg-foreground text-background' : 'bg-muted text-muted-foreground'
                  )}
                >
                  {r.label}
                </button>
              ))}
            </div>
          </>
        )}
      </header>

      <div className="px-4 py-4 pb-24">
        {showExtras && (
          <div className="mb-4 space-y-3">
            <StatsCard stats={stats!} />
            <InsightEntry onClick={() => navigate(ROUTES.NOTE_INSIGHTS)} />
            {showThrowback && (
              <ThrowbackCard
                note={throwback!}
                onOpen={() => navigate(`/notes/${throwback!.id}`)}
                onDismiss={() => setHideThrowback(true)}
              />
            )}
          </div>
        )}

        {loading ? (
          <div className="space-y-3">
            {[0, 1, 2].map((i) => <div key={i} className="h-28 animate-pulse rounded-2xl bg-muted" />)}
          </div>
        ) : displayNotes.length === 0 ? (
          <div className="flex flex-col items-center gap-3 pt-16 text-muted-foreground">
            <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-muted">
              {query ? <Search className="h-7 w-7 text-muted-foreground/50" /> : <NotebookPen className="h-7 w-7 text-muted-foreground/50" />}
            </div>
            <p className="text-sm">{query ? '没有找到相关笔记' : isFiltered ? '这个筛选下还没有笔记' : '还没有笔记'}</p>
            {!query && !isFiltered && (
              <p className="max-w-[240px] text-center text-xs text-muted-foreground/70">
                读书记一笔想法，或把打动你的书评收录进来
              </p>
            )}
          </div>
        ) : (
          <div className="space-y-5">
            {groups.map((g) => (
              <div key={g.key}>
                <p className="mb-2 text-xs font-semibold text-muted-foreground">{dayLabel(g.key)}</p>
                <div className="space-y-3">
                  {g.items.map((note) => <NoteCard key={note.id} note={note} />)}
                </div>
              </div>
            ))}
            <div className="pt-1">
              {semanticMode ? (
                <p className="text-center text-xs text-muted-foreground">按语义相关度排序</p>
              ) : hasNextPage ? (
                <button
                  onClick={() => fetchNextPage()}
                  disabled={isFetchingNextPage}
                  className="w-full rounded-xl bg-muted py-3 text-sm font-medium text-muted-foreground transition-colors disabled:opacity-50"
                >
                  {isFetchingNextPage ? '加载中...' : '加载更多'}
                </button>
              ) : (
                <p className="text-center text-xs text-muted-foreground">已经到底了</p>
              )}
            </div>
          </div>
        )}
      </div>

      <Dialog open={composerOpen} onOpenChange={setComposerOpen}>
        <DialogContent className="gap-0 p-0">
          <DialogHeader className="border-b border-border/50 p-4">
            <DialogTitle>记一笔</DialogTitle>
          </DialogHeader>
          <div className="space-y-3 p-4">
            <Input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="标题（可选）"
              maxLength={100}
            />
            <Textarea
              value={content}
              onChange={(e) => setContent(e.target.value)}
              placeholder="此刻的想法…"
              rows={5}
              maxLength={20000}
              autoFocus
              className="resize-none"
            />
          </div>
          <div className="border-t border-border/50 p-4">
            <button
              onClick={() => createMutation.mutate()}
              disabled={createMutation.isPending || (!title.trim() && !content.trim())}
              className="h-11 w-full rounded-xl bg-primary text-sm font-semibold text-primary-foreground transition-transform active:scale-[0.98] disabled:opacity-50"
            >
              {createMutation.isPending ? '保存中...' : '保存'}
            </button>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  )
}
