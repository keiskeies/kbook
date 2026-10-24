import { useDeferredValue, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  ArrowRight,
  ArrowUpDown,
  BookOpen,
  CalendarDays,
  Check,
  ChevronRight,
  Clock3,
  Download,
  History,
  LibraryBig,
  Loader2,
  MessagesSquare,
  MoreHorizontal,
  NotebookPen,
  PenLine,
  Pin,
  Search,
  Shuffle,
  SlidersHorizontal,
  Sparkles,
  Star,
  Tag,
  Trash2,
  X,
} from 'lucide-react'
import {
  deleteNote,
  getNoteBooks,
  getNoteStats,
  getNoteTags,
  getThrowback,
  getTimeline,
  searchNotes,
  semanticSearchNotes,
  setNoteFavorited,
  setNotePinned,
  type NoteBookVO,
  type NoteTagVO,
  type NoteVO,
  type NoteType,
} from '@/api/note'
import { NoteCard } from '@/components/note/NoteCard'
import { NoteBooksView } from '@/components/note/NoteBooksView'
import { NoteHeatmap } from '@/components/note/NoteHeatmap'
import { NoteStatsHero } from '@/components/note/NoteStatsHero'
import { AskNotesDialog } from '@/components/note/AskNotesDialog'
import { NOTE_PROMPTS } from '@/components/note/NoteComposer'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui'
import { useNoteComposerStore } from '@/store/noteComposer'
import { ROUTES } from '@/constants'
import { formatRelativeTime } from '@/utils/time'
import { exportNotes } from '@/utils/noteExport'
import { cn } from '@/utils/cn'

type CountKey = 'total' | 'thoughtCount' | 'captureCount' | 'excerptCount'

const FILTERS: { value: NoteType | 'ALL'; label: string; countKey: CountKey }[] = [
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

const DAILY_QUESTIONS = [
  '最近读到哪句话，让你停下来想了很久？',
  '如果只能用一句话总结今天读到的内容，会是什么？',
  '有没有哪个观点，你一开始不同意，后来被说服了？',
  '这本书里，你最想讲给朋友听的是哪一部分？',
  '今天有什么小发现，和书里的内容悄悄对上了？',
  '你现在相信、但一年前不相信的事情是什么？',
  '哪本书改变了你看待某件事的方式？',
]

const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']
const SEARCH_HISTORY_KEY = 'kbook:note-search-history'

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
  today.setHours(0, 0, 0, 0)
  const d = new Date(`${key}T00:00:00`)
  const diff = Math.round((today.getTime() - d.getTime()) / 86400000)
  const weekday = WEEKDAYS[d.getDay()]
  if (diff === 0) return `今天 · 周${weekday}`
  if (diff === 1) return `昨天 · 周${weekday}`
  const md = `${d.getMonth() + 1}月${d.getDate()}日`
  return d.getFullYear() === today.getFullYear() ? `${md} · 周${weekday}` : `${d.getFullYear()}年${md}`
}

function shortDayLabel(key: string) {
  const d = new Date(`${key}T00:00:00`)
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

function notePreview(note: NoteVO) {
  if (note.contentMd?.trim()) return note.contentMd.trim()
  const s = note.snapshot
  if (!s) return '（这条笔记没有正文）'
  return s.excerpt?.trim() || s.sourceContent?.trim() || s.sourceTitle?.trim() || '（这条笔记没有正文）'
}

function readHistory(): string[] {
  try {
    const raw = localStorage.getItem(SEARCH_HISTORY_KEY)
    const parsed = raw ? JSON.parse(raw) : []
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string').slice(0, 8) : []
  } catch {
    return []
  }
}

function writeHistory(list: string[]) {
  try {
    localStorage.setItem(SEARCH_HISTORY_KEY, JSON.stringify(list))
  } catch {
    // 隐私模式下忽略
  }
}

function InsightEntry({ onClick }: { onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className="flex w-full items-center gap-3 rounded-2xl border border-primary/20 bg-gradient-to-r from-primary/10 to-transparent p-4 text-left transition-transform active:scale-[0.99] hover:border-primary/35"
    >
      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-primary text-primary-foreground">
        <Sparkles className="h-5 w-5" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block text-sm font-semibold">AI 成长总结</span>
        <span className="block text-xs text-muted-foreground">根据笔记生成阶段性回顾</span>
      </span>
      <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
    </button>
  )
}

function ThrowbackCard({
  note,
  onOpen,
  onDismiss,
  onShuffle,
  refreshing,
}: {
  note: NoteVO
  onOpen: () => void
  onDismiss: () => void
  onShuffle: () => void
  refreshing: boolean
}) {
  const words = notePreview(note)
  return (
    <div className="relative overflow-hidden rounded-2xl border border-border/60 bg-card p-4">
      <div className="pointer-events-none absolute -right-10 -top-10 h-28 w-28 rounded-full bg-warning/10 blur-2xl" />
      <button
        onClick={onDismiss}
        className="absolute right-1.5 top-1.5 flex h-9 w-9 items-center justify-center rounded-lg text-muted-foreground/60 transition-colors hover:bg-muted"
        aria-label="关闭时光机"
      >
        <X className="h-3.5 w-3.5" />
      </button>
      <p className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <History className="h-3.5 w-3.5" />
        时光机 · {formatRelativeTime(note.occurredAt)}
      </p>
      {note.bookTitle && <p className="mt-1 truncate text-xs text-muted-foreground">《{note.bookTitle}》</p>}
      {note.title && <p className="mt-2 text-sm font-semibold leading-snug">{note.title}</p>}
      <p className="mt-1.5 line-clamp-3 whitespace-pre-wrap text-sm leading-relaxed">{words}</p>
      <div className="mt-2.5 flex items-center gap-3">
        <button onClick={onOpen} className="inline-flex h-8 items-center px-1 text-xs font-semibold text-primary">再看看 →</button>
        <button
          onClick={onShuffle}
          disabled={refreshing}
          className="flex items-center gap-1 text-xs text-muted-foreground transition-colors hover:text-foreground disabled:opacity-50"
        >
          <Shuffle className={cn('h-3 w-3', refreshing && 'animate-spin')} />
          换一条
        </button>
      </div>
    </div>
  )
}

function PromptCard({ question, onWrite }: { question: string; onWrite: () => void }) {
  return (
    <div className="rounded-2xl border border-border/60 bg-gradient-to-br from-card to-primary/[0.06] p-4">
      <p className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <Sparkles className="h-3.5 w-3.5 text-primary" />
        今日一问
      </p>
      <p className="mt-2 text-sm leading-relaxed">{question}</p>
      <button
        onClick={onWrite}
        className="mt-2 flex h-8 items-center gap-1 px-1 text-xs font-semibold text-primary transition-transform active:translate-x-0.5"
      >
        写一写 <ArrowRight className="h-3.5 w-3.5" />
      </button>
    </div>
  )
}

function BookRail({
  books,
  onOpen,
  onAll,
}: {
  books: NoteBookVO[]
  onOpen: (book: NoteBookVO) => void
  onAll: () => void
}) {
  return (
    <div className="rounded-2xl border border-border/60 bg-card p-4">
      <div className="flex items-center justify-between">
        <p className="flex items-center gap-1.5 text-xs font-semibold text-muted-foreground">
          <LibraryBig className="h-3.5 w-3.5" />
          常写的书
        </p>
        <button onClick={onAll} className="inline-flex h-8 items-center px-1 text-xs text-muted-foreground transition-colors hover:text-primary">
          全部
        </button>
      </div>
      <div className="mt-2 space-y-1">
        {books.slice(0, 3).map((book) => (
          <button
            key={book.bookId}
            onClick={() => onOpen(book)}
            className="flex w-full items-center gap-2.5 rounded-lg px-1 py-2 text-left transition-colors hover:bg-muted/60"
          >
            {book.bookCover ? (
              <img src={book.bookCover} alt={book.bookTitle ?? ''} className="h-9 w-6 shrink-0 rounded object-cover" />
            ) : (
              <span className="flex h-9 w-6 shrink-0 items-center justify-center rounded bg-muted">
                <BookOpen className="h-3 w-3 text-muted-foreground/50" />
              </span>
            )}
            <span className="min-w-0 flex-1 truncate text-xs font-medium">{book.bookTitle ?? '未命名书籍'}</span>
            <span className="shrink-0 text-xs tabular-nums text-muted-foreground">{book.count}</span>
          </button>
        ))}
      </div>
    </div>
  )
}

function TagRail({
  tags,
  activeTag,
  onSelect,
}: {
  tags: NoteTagVO[]
  activeTag: string | null
  onSelect: (tag: string) => void
}) {
  return (
    <div className="rounded-2xl border border-border/60 bg-card p-4">
      <p className="flex items-center gap-1.5 text-xs font-semibold text-muted-foreground">
        <Tag className="h-3.5 w-3.5" />
        常用标签
      </p>
      <div className="mt-2 flex flex-wrap gap-1.5">
        {tags.slice(0, 10).map((t) => (
          <button
            key={t.tag}
            onClick={() => onSelect(t.tag)}
            className={cn(
              'flex h-8 items-center rounded-full px-3 text-xs transition-colors sm:h-7',
              activeTag === t.tag
                ? 'bg-primary text-primary-foreground'
                : 'bg-muted text-muted-foreground hover:bg-primary/10 hover:text-primary'
            )}
          >
            #{t.tag}
            <span className="ml-1 opacity-60">{t.count}</span>
          </button>
        ))}
      </div>
    </div>
  )
}

function SearchField({
  value,
  onChange,
  semantic,
  onToggleSemantic,
  onFocus,
  onBlur,
  onCommit,
  inputRef,
  className,
}: {
  value: string
  onChange: (value: string) => void
  semantic: boolean
  onToggleSemantic: () => void
  onFocus?: () => void
  onBlur?: () => void
  onCommit?: (value: string) => void
  inputRef?: React.RefObject<HTMLInputElement | null>
  className?: string
}) {
  return (
    <div className={cn('flex items-center gap-2 rounded-xl bg-muted px-3 py-2', className)}>
      <Search className="h-4 w-4 shrink-0 text-muted-foreground" />
      <input
        ref={inputRef}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onFocus={onFocus}
        onBlur={() => onBlur?.()}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault()
            onCommit?.(value)
            e.currentTarget.blur()
          }
        }}
        placeholder="搜索我的笔记"
        className="min-w-0 flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground/60"
      />
      {value.trim() && (
        <button
          onClick={onToggleSemantic}
          onMouseDown={(e) => e.preventDefault()}
          className={cn(
            'shrink-0 rounded-full px-2 py-0.5 text-xs font-medium transition-colors',
            semantic ? 'bg-primary text-primary-foreground' : 'bg-background text-muted-foreground'
          )}
          title="按语义相关度搜索"
        >
          语义
        </button>
      )}
      {value && (
        <button
          onMouseDown={(e) => e.preventDefault()}
          onClick={() => onChange('')}
          aria-label="清除搜索"
        >
          <X className="h-4 w-4 text-muted-foreground" />
        </button>
      )}
    </div>
  )
}

function WelcomeState({ onWrite, onPrompt }: { onWrite: () => void; onPrompt: (seed: string) => void }) {
  return (
    <div className="rounded-3xl border border-dashed border-border/70 bg-card/60 px-6 py-12 text-center">
      <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-primary/10">
        <NotebookPen className="h-7 w-7 text-primary" />
      </div>
      <h2 className="mt-4 text-base font-bold">从一句话开始</h2>
      <p className="mx-auto mt-1.5 max-w-xs text-xs leading-relaxed text-muted-foreground">
        笔记不必完整。读到心动的句子、冒出一个问题，都可以先放进来，慢慢长成你自己的书。
      </p>
      <button
        onClick={onWrite}
        className="mt-5 inline-flex items-center gap-1.5 rounded-xl bg-primary px-5 py-2.5 text-sm font-semibold text-primary-foreground transition-transform active:scale-[0.98]"
      >
        <PenLine className="h-4 w-4" />
        写第一条笔记
      </button>
      <div className="mt-5 flex flex-wrap justify-center gap-2">
        {NOTE_PROMPTS.map((p) => (
          <button
            key={p.label}
            onClick={() => onPrompt(p.template)}
            className="rounded-full border border-border/60 bg-background px-3 py-1.5 text-xs text-muted-foreground transition-colors hover:border-primary/40 hover:text-primary"
          >
            {p.label}
          </button>
        ))}
      </div>
    </div>
  )
}

export default function NotesPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const openComposer = useNoteComposerStore((s) => s.openComposer)

  const [type, setType] = useState<NoteType | 'ALL'>('ALL')
  const [favoritedOnly, setFavoritedOnly] = useState(false)
  const [range, setRange] = useState('ALL')
  const [keyword, setKeyword] = useState('')
  const [semantic, setSemantic] = useState(false)
  const [view, setView] = useState<'timeline' | 'books'>('timeline')
  const [activeBook, setActiveBook] = useState<NoteBookVO | null>(null)
  const [activeTag, setActiveTag] = useState<string | null>(null)
  const [dayFilter, setDayFilter] = useState<string | null>(null)
  const [sort, setSort] = useState<'occurred' | 'updated'>('occurred')
  const [hideThrowback, setHideThrowback] = useState(false)
  const [rangeOpen, setRangeOpen] = useState(false)
  const [sortOpen, setSortOpen] = useState(false)
  const [tagOpen, setTagOpen] = useState(false)
  const [mobileFilterOpen, setMobileFilterOpen] = useState(false)
  const [searchFocused, setSearchFocused] = useState(false)
  const [recentSearches, setRecentSearches] = useState<string[]>(() => readHistory())
  const [pendingDelete, setPendingDelete] = useState<NoteVO | null>(null)
  const [askOpen, setAskOpen] = useState(false)
  const [heatmapOpen, setHeatmapOpen] = useState(false)
  const [exporting, setExporting] = useState(false)
  const searchRef = useRef<HTMLInputElement>(null)

  const deferredKeyword = useDeferredValue(keyword)
  const query = deferredKeyword.trim()
  const semanticMode = semantic && !!query
  const effectiveView = query ? 'timeline' : view

  const rangeFrom = useMemo(() => {
    if (dayFilter) return `${dayFilter}T00:00:00`
    const cfg = RANGES.find((r) => r.value === range)
    if (!cfg?.days) return undefined
    const d = new Date()
    d.setDate(d.getDate() - cfg.days)
    return toLocalIso(d)
  }, [range, dayFilter])
  const rangeTo = dayFilter ? `${dayFilter}T23:59:59` : undefined

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key !== '/' || e.metaKey || e.ctrlKey || e.altKey) return
      const el = e.target as HTMLElement | null
      if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)) return
      e.preventDefault()
      searchRef.current?.focus()
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [])

  const { data: stats } = useQuery({
    queryKey: ['notes', 'stats'],
    queryFn: getNoteStats,
    staleTime: 60_000,
  })

  const {
    data: throwback,
    isFetching: throwbackFetching,
    refetch: refetchThrowback,
  } = useQuery({
    queryKey: ['notes', 'throwback'],
    queryFn: getThrowback,
    enabled: !!stats && stats.total > 0,
    staleTime: 60_000,
  })

  const { data: books, isLoading: booksLoading } = useQuery({
    queryKey: ['notes', 'books'],
    queryFn: getNoteBooks,
    enabled: !!stats && stats.total > 0,
    staleTime: 60_000,
  })

  const { data: tags } = useQuery({
    queryKey: ['notes', 'tags'],
    queryFn: getNoteTags,
    enabled: !!stats && stats.total > 0,
    staleTime: 60_000,
  })

  const { data, fetchNextPage, hasNextPage, isFetchingNextPage, isLoading } = useInfiniteQuery({
    queryKey: ['notes', 'timeline', type, favoritedOnly, range, query, activeBook?.bookId, activeTag, sort, dayFilter],
    queryFn: ({ pageParam }) =>
      query
        ? searchNotes(query, pageParam, 20)
        : getTimeline({
            type: type === 'ALL' ? undefined : type,
            bookId: activeBook?.bookId,
            tag: activeTag ?? undefined,
            favorited: favoritedOnly || undefined,
            sort,
            from: rangeFrom,
            to: rangeTo,
            page: pageParam,
            size: 20,
          }),
    initialPageParam: 1,
    enabled: effectiveView === 'timeline' && !semanticMode,
    getNextPageParam: (lastPage) => {
      const loaded = lastPage.page * lastPage.size
      return loaded < lastPage.total ? lastPage.page + 1 : undefined
    },
  })

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

  const pinnedNotes = useMemo(
    () => (!semanticMode && !query ? displayNotes.filter((n) => n.pinned) : []),
    [displayNotes, semanticMode, query]
  )
  const timelineNotes = useMemo(
    () => (pinnedNotes.length ? displayNotes.filter((n) => !n.pinned) : displayNotes),
    [displayNotes, pinnedNotes.length]
  )

  const groups = useMemo(() => {
    const out: { key: string; items: NoteVO[] }[] = []
    for (const n of timelineNotes) {
      const k = dayKey(n.occurredAt)
      const last = out[out.length - 1]
      if (last && last.key === k) last.items.push(n)
      else out.push({ key: k, items: [n] })
    }
    return out
  }, [timelineNotes])

  const invalidateNotes = () => queryClient.invalidateQueries({ queryKey: ['notes'] })

  const pinMutation = useMutation({
    mutationFn: ({ id, pinned }: { id: number; pinned: boolean }) => setNotePinned(id, pinned),
    onSuccess: (_data, vars) => {
      invalidateNotes()
      toast.success(vars.pinned ? '已置顶' : '已取消置顶')
    },
    onError: (err: Error) => toast.error(err.message || '操作失败'),
  })

  const favoriteMutation = useMutation({
    mutationFn: ({ id, favorited }: { id: number; favorited: boolean }) => setNoteFavorited(id, favorited),
    onSuccess: (_data, vars) => {
      invalidateNotes()
      toast.success(vars.favorited ? '已收藏' : '已取消收藏')
    },
    onError: (err: Error) => toast.error(err.message || '操作失败'),
  })

  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteNote(id),
    onSuccess: () => {
      invalidateNotes()
      setPendingDelete(null)
      toast.success('笔记已删除')
    },
    onError: (err: Error) => toast.error(err.message || '删除失败'),
  })

  const recordSearch = (term: string) => {
    const value = term.trim()
    if (value.length < 2) return
    setRecentSearches((prev) => {
      const next = [value, ...prev.filter((v) => v !== value)].slice(0, 8)
      writeHistory(next)
      return next
    })
  }

  const clearHistory = () => {
    setRecentSearches([])
    writeHistory([])
  }

  const openBook = (book: NoteBookVO) => {
    setActiveBook(book)
    setActiveTag(null)
    setDayFilter(null)
    setType('ALL')
    setFavoritedOnly(false)
    setRange('ALL')
    setView('timeline')
  }

  const selectTag = (tag: string) => {
    setActiveTag(tag)
    setActiveBook(null)
    setDayFilter(null)
    setType('ALL')
    setFavoritedOnly(false)
    setRange('ALL')
    setView('timeline')
    setTagOpen(false)
  }

  const changeKeyword = (value: string) => {
    setKeyword(value)
    if (!value.trim()) {
      setSemantic(false)
      return
    }
    setView('timeline')
    setActiveBook(null)
    setActiveTag(null)
    setDayFilter(null)
  }

  const clearAllFilters = () => {
    setType('ALL')
    setFavoritedOnly(false)
    setRange('ALL')
    setActiveBook(null)
    setActiveTag(null)
    setDayFilter(null)
  }

  const openAsk = () => setAskOpen(true)
  const openHeatmap = () => setHeatmapOpen(true)

  const handleExport = async (format: 'md' | 'json') => {
    if (exporting) return
    setExporting(true)
    try {
      const count = await exportNotes(format)
      toast.success(count > 0 ? `已导出 ${count} 条笔记` : '还没有笔记可导出')
    } catch (err) {
      toast.error((err as Error).message || '导出失败')
    } finally {
      setExporting(false)
    }
  }

  const dailyQuestion = useMemo(
    () => DAILY_QUESTIONS[Math.floor(Date.now() / 86400000) % DAILY_QUESTIONS.length],
    []
  )

  const hasNotes = !!stats && stats.total > 0
  const showFilters = !query && stats?.total !== 0
  const showExtras = !query && hasNotes
  const showThrowback = showExtras && !hideThrowback && type === 'ALL' && range === 'ALL' && !activeBook && !activeTag && !dayFilter && !!throwback
  const rangeLabel = RANGES.find((r) => r.value === range)?.label ?? '全部时间'
  const isFiltered = type !== 'ALL' || range !== 'ALL' || !!activeBook || !!activeTag || favoritedOnly || !!dayFilter
  const topBooks = books?.slice(0, 3) ?? []

  const heatmapActiveDays = useMemo(
    () => (stats?.heatmap ?? []).filter((d) => d.count > 0).length,
    [stats?.heatmap]
  )
  const heatmapTotal = useMemo(
    () => (stats?.heatmap ?? []).reduce((sum, d) => sum + d.count, 0),
    [stats?.heatmap]
  )

  const insightEntry = <InsightEntry onClick={() => navigate(ROUTES.NOTE_INSIGHTS)} />
  const throwbackEntry = showThrowback ? (
    <ThrowbackCard
      note={throwback!}
      onOpen={() => navigate(`/notes/${throwback!.id}`)}
      onDismiss={() => setHideThrowback(true)}
      onShuffle={() => refetchThrowback()}
      refreshing={throwbackFetching}
    />
  ) : null

  const headerCount = query
    ? `搜索到 ${displayTotal} 条`
    : effectiveView === 'books'
      ? `${books?.length ?? 0} 本书`
      : `${displayTotal} 条`

  const sourceChips = recentSearches.length > 0
    ? recentSearches.slice(0, 4)
    : (tags ?? []).slice(0, 4).map((t) => t.tag)

  return (
    <div className="flex flex-col">
      <header className="sticky top-0 z-20 border-b border-border/50 bg-navbar/95 backdrop-blur-xl">
        <div className="relative mx-auto w-full max-w-6xl px-4 py-3 md:px-6">
          <div className="flex items-center gap-2.5">
            <h1 className="text-base font-bold md:text-lg">笔记</h1>
            {(query || hasNotes) && <span className="text-xs text-muted-foreground">{headerCount}</span>}
            <div className="ml-auto flex items-center gap-1.5">
              <SearchField
                value={keyword}
                onChange={changeKeyword}
                semantic={semantic}
                onToggleSemantic={() => setSemantic((v) => !v)}
                onFocus={() => setSearchFocused(true)}
                onBlur={() => setSearchFocused(false)}
                onCommit={recordSearch}
                inputRef={searchRef}
                className="hidden w-56 md:flex lg:w-72"
              />
              <button
                onClick={openAsk}
                title="问我的笔记"
                className="flex h-9 items-center gap-1.5 rounded-xl border border-border/60 bg-card px-3 text-xs font-medium text-muted-foreground transition-colors hover:border-primary/35 hover:text-primary"
              >
                <MessagesSquare className="h-3.5 w-3.5" />
                <span className="hidden sm:inline">问笔记</span>
              </button>
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <button
                    className="flex h-9 w-9 items-center justify-center rounded-xl text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                    aria-label="更多操作"
                  >
                    <MoreHorizontal className="h-4 w-4" />
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="w-44">
                  <DropdownMenuItem onClick={openHeatmap}>
                    <CalendarDays className="mr-2 h-4 w-4" />
                    写作足迹
                  </DropdownMenuItem>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onClick={() => handleExport('md')} disabled={exporting}>
                    {exporting ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Download className="mr-2 h-4 w-4" />}
                    导出全部 Markdown
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={() => handleExport('json')} disabled={exporting}>
                    <Download className="mr-2 h-4 w-4" />
                    导出 JSON
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
              <button
                onClick={() => openComposer()}
                className="flex h-9 items-center gap-1.5 rounded-xl bg-primary px-4 text-sm font-semibold text-primary-foreground shadow-sm transition-transform active:scale-95"
              >
                <PenLine className="h-3.5 w-3.5" />
                记一笔
              </button>
            </div>
          </div>

          <SearchField
            value={keyword}
            onChange={changeKeyword}
            semantic={semantic}
            onToggleSemantic={() => setSemantic((v) => !v)}
            onFocus={() => setSearchFocused(true)}
            onBlur={() => setSearchFocused(false)}
            onCommit={recordSearch}
            className="mt-2.5 md:hidden"
          />

          {searchFocused && !keyword && sourceChips.length > 0 && (
            <div
              onMouseDown={(e) => e.preventDefault()}
              className="absolute left-4 right-4 top-full z-30 mt-1 flex items-center gap-1.5 overflow-x-auto rounded-xl border border-border/60 bg-popover p-2 shadow-lg scrollbar-hide md:left-auto md:right-6 md:w-[420px]"
            >
              <span className="flex shrink-0 items-center gap-1 text-xs text-muted-foreground/70">
                <Clock3 className="h-3 w-3" />
                {recentSearches.length ? '最近' : '常搜'}
              </span>
              {sourceChips.map((item) => (
                <button
                  key={item}
                  onClick={() => {
                    if (recentSearches.length) {
                      setKeyword(item)
                      setView('timeline')
                    } else {
                      selectTag(item)
                    }
                  }}
                  className="flex h-8 shrink-0 items-center rounded-full bg-muted px-3 text-xs text-muted-foreground transition-colors hover:text-foreground"
                >
                  {recentSearches.length ? item : `#${item}`}
                </button>
              ))}
              {recentSearches.length > 0 && (
                <button
                  onClick={clearHistory}
                  className="ml-auto flex h-8 shrink-0 items-center px-1.5 text-xs text-muted-foreground/60 transition-colors hover:text-foreground"
                >
                  清除
                </button>
              )}
            </div>
          )}

          {showFilters && (
            <div className="mt-2.5 flex items-center gap-2">
              {effectiveView === 'timeline' ? (
                <div className="flex min-w-0 flex-1 gap-1.5 overflow-x-auto scrollbar-hide">
                  {FILTERS.map((f) => {
                    const active = !favoritedOnly && type === f.value
                    const count = range === 'ALL' && !activeBook && !activeTag && !dayFilter
                      ? stats?.[f.countKey]
                      : undefined
                    return (
                      <button
                        key={f.value}
                        onClick={() => {
                          setType(f.value)
                          setFavoritedOnly(false)
                        }}
                        className={cn(
                          'flex h-9 shrink-0 items-center rounded-full px-3.5 text-xs font-medium transition-colors sm:h-8',
                          active ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:text-foreground'
                        )}
                      >
                        {f.label}
                        {count != null && <span className="ml-1 hidden opacity-60 sm:inline">{count}</span>}
                      </button>
                    )
                  })}
                  <button
                    onClick={() => {
                      setFavoritedOnly(true)
                      setType('ALL')
                    }}
                    className={cn(
                      'hidden h-8 shrink-0 items-center gap-1 rounded-full px-3.5 text-xs font-medium transition-colors sm:flex',
                      favoritedOnly ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:text-foreground'
                    )}
                  >
                    <Star className={cn('h-3 w-3', favoritedOnly && 'fill-current')} />
                    收藏
                    {stats?.favoriteCount ? <span className="hidden opacity-60 sm:inline">{stats.favoriteCount}</span> : null}
                  </button>
                </div>
              ) : (
                <p className="flex min-w-0 flex-1 items-center gap-1.5 text-xs text-muted-foreground">
                  <LibraryBig className="h-3.5 w-3.5" />
                  按最近记录排序
                </p>
              )}

              <div className="flex shrink-0 items-center gap-1.5">
                {effectiveView === 'timeline' && (
                  <>
                    <div className="hidden items-center gap-1.5 lg:flex">
                    <Popover open={rangeOpen} onOpenChange={setRangeOpen}>
                      <PopoverTrigger asChild>
                        <button
                          className={cn(
                            'flex h-8 items-center gap-1 rounded-full px-3 text-xs font-medium transition-colors',
                            range === 'ALL' && !dayFilter ? 'bg-muted text-muted-foreground' : 'bg-foreground text-background'
                          )}
                          title="时间范围"
                        >
                          <CalendarDays className="h-3.5 w-3.5" />
                          <span className="hidden lg:inline">{dayFilter ? shortDayLabel(dayFilter) : rangeLabel}</span>
                        </button>
                      </PopoverTrigger>
                      <PopoverContent align="end" className="w-36 p-1">
                        {RANGES.map((r) => (
                          <button
                            key={r.value}
                            onClick={() => {
                              setRange(r.value)
                              setDayFilter(null)
                              setRangeOpen(false)
                            }}
                            className={cn(
                              'flex w-full items-center justify-between rounded-md px-2.5 py-2 text-xs transition-colors hover:bg-muted',
                              range === r.value && !dayFilter && 'bg-accent text-accent-foreground'
                            )}
                          >
                            {r.label}
                            {range === r.value && !dayFilter && <Check className="h-3.5 w-3.5" />}
                          </button>
                        ))}
                      </PopoverContent>
                    </Popover>

                    <Popover open={sortOpen} onOpenChange={setSortOpen}>
                      <PopoverTrigger asChild>
                        <button
                          className={cn(
                            'flex h-8 items-center gap-1 rounded-full px-3 text-xs font-medium transition-colors',
                            sort === 'updated' ? 'bg-foreground text-background' : 'bg-muted text-muted-foreground'
                          )}
                          title="排序方式"
                        >
                          <ArrowUpDown className="h-3.5 w-3.5" />
                          <span className="hidden lg:inline">{sort === 'updated' ? '最近编辑' : '记录时间'}</span>
                        </button>
                      </PopoverTrigger>
                      <PopoverContent align="end" className="w-36 p-1">
                        {[
                          { value: 'occurred', label: '按记录时间' },
                          { value: 'updated', label: '按最近编辑' },
                        ].map((option) => (
                          <button
                            key={option.value}
                            onClick={() => {
                              setSort(option.value as 'occurred' | 'updated')
                              setSortOpen(false)
                            }}
                            className={cn(
                              'flex w-full items-center justify-between rounded-md px-2.5 py-2 text-xs transition-colors hover:bg-muted',
                              sort === option.value && 'bg-accent text-accent-foreground'
                            )}
                          >
                            {option.label}
                            {sort === option.value && <Check className="h-3.5 w-3.5" />}
                          </button>
                        ))}
                      </PopoverContent>
                    </Popover>

                    <Popover open={tagOpen} onOpenChange={setTagOpen}>
                      <PopoverTrigger asChild>
                        <button
                          className={cn(
                            'flex h-8 items-center gap-1 rounded-full px-3 text-xs font-medium transition-colors',
                            activeTag ? 'bg-foreground text-background' : 'bg-muted text-muted-foreground'
                          )}
                          title="按标签筛选"
                        >
                          <Tag className="h-3.5 w-3.5" />
                          <span className="hidden lg:inline">{activeTag ? `#${activeTag}` : '标签'}</span>
                        </button>
                      </PopoverTrigger>
                      <PopoverContent align="end" className="w-52 p-1">
                        <p className="px-2 py-1 text-xs font-medium text-muted-foreground">按标签筛选</p>
                        {tags && tags.length > 0 ? (
                          <div className="max-h-64 overflow-y-auto">
                            {tags.map((t) => (
                              <button
                                key={t.tag}
                                onClick={() => selectTag(t.tag)}
                                className={cn(
                                  'flex w-full items-center justify-between rounded-md px-2.5 py-2 text-xs transition-colors hover:bg-muted',
                                  activeTag === t.tag && 'bg-accent text-accent-foreground'
                                )}
                              >
                                <span className="truncate">#{t.tag}</span>
                                <span className="ml-2 tabular-nums text-muted-foreground">{t.count}</span>
                              </button>
                            ))}
                          </div>
                        ) : (
                          <p className="px-2 py-3 text-center text-xs text-muted-foreground">还没有标签</p>
                        )}
                      </PopoverContent>
                    </Popover>
                    </div>

                    <Popover open={mobileFilterOpen} onOpenChange={setMobileFilterOpen}>
                      <PopoverTrigger asChild>
                        <button
                          className={cn(
                            'flex h-9 items-center gap-1 rounded-full px-3 text-xs font-medium transition-colors lg:hidden',
                            (range !== 'ALL' || sort === 'updated' || activeTag || dayFilter || favoritedOnly)
                              ? 'bg-foreground text-background'
                              : 'bg-muted text-muted-foreground'
                          )}
                          title="筛选"
                        >
                          <SlidersHorizontal className="h-3.5 w-3.5" />
                          筛选
                        </button>
                      </PopoverTrigger>
                      <PopoverContent align="end" className="w-64 p-2">
                        <p className="px-1 py-1 text-xs font-medium text-muted-foreground">时间范围</p>
                        <div className="grid grid-cols-2 gap-1">
                          {RANGES.map((r) => (
                            <button
                              key={r.value}
                              onClick={() => {
                                setRange(r.value)
                                setDayFilter(null)
                              }}
                              className={cn(
                                'rounded-md px-2.5 py-2 text-left text-xs transition-colors hover:bg-muted',
                                range === r.value && !dayFilter && 'bg-accent text-accent-foreground'
                              )}
                            >
                              {r.label}
                            </button>
                          ))}
                        </div>

                        <p className="mt-2 px-1 py-1 text-xs font-medium text-muted-foreground">排序</p>
                        <div className="grid grid-cols-2 gap-1">
                          {[
                            { value: 'occurred', label: '记录时间' },
                            { value: 'updated', label: '最近编辑' },
                          ].map((option) => (
                            <button
                              key={option.value}
                              onClick={() => setSort(option.value as 'occurred' | 'updated')}
                              className={cn(
                                'rounded-md px-2.5 py-2 text-left text-xs transition-colors hover:bg-muted',
                                sort === option.value && 'bg-accent text-accent-foreground'
                              )}
                            >
                              {option.label}
                            </button>
                          ))}
                        </div>

                        <button
                          onClick={() => {
                            setFavoritedOnly((v) => !v)
                            setType('ALL')
                          }}
                          className={cn(
                            'mt-2 flex w-full items-center justify-between rounded-md px-2.5 py-2 text-xs transition-colors',
                            favoritedOnly ? 'bg-accent text-accent-foreground' : 'hover:bg-muted'
                          )}
                        >
                          <span className="flex items-center gap-1">
                            <Star className={cn('h-3 w-3', favoritedOnly && 'fill-current')} />
                            只看收藏
                          </span>
                          <span className="tabular-nums text-muted-foreground">{stats?.favoriteCount ?? 0}</span>
                        </button>

                        {tags && tags.length > 0 && (
                          <>
                            <p className="mt-2 px-1 py-1 text-xs font-medium text-muted-foreground">标签</p>
                            <div className="max-h-40 overflow-y-auto">
                              {tags.map((t) => (
                                <button
                                  key={t.tag}
                                  onClick={() => {
                                    selectTag(t.tag)
                                    setMobileFilterOpen(false)
                                  }}
                                  className={cn(
                                    'flex w-full items-center justify-between rounded-md px-2.5 py-2 text-xs transition-colors hover:bg-muted',
                                    activeTag === t.tag && 'bg-accent text-accent-foreground'
                                  )}
                                >
                                  <span className="truncate">#{t.tag}</span>
                                  <span className="ml-2 tabular-nums text-muted-foreground">{t.count}</span>
                                </button>
                              ))}
                            </div>
                          </>
                        )}

                        <button
                          onClick={() => {
                            setRange('ALL')
                            setSort('occurred')
                            setActiveTag(null)
                            setDayFilter(null)
                            setFavoritedOnly(false)
                            setMobileFilterOpen(false)
                          }}
                          className="mt-2 w-full rounded-md bg-muted py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:text-foreground"
                        >
                          重置筛选
                        </button>
                      </PopoverContent>
                    </Popover>
                  </>
                )}

                <div className="hidden rounded-lg bg-muted p-0.5 sm:flex">
                  <button
                    onClick={() => setView('timeline')}
                    title="时间轴"
                    className={cn(
                      'flex h-8 items-center gap-1 rounded-md px-2.5 text-xs font-medium transition-colors',
                      view === 'timeline' ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground'
                    )}
                  >
                    <History className="h-3.5 w-3.5" />
                    <span>时间轴</span>
                  </button>
                  <button
                    onClick={() => setView('books')}
                    title="按书"
                    className={cn(
                      'flex h-8 items-center gap-1 rounded-md px-2.5 text-xs font-medium transition-colors',
                      view === 'books' ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground'
                    )}
                  >
                    <LibraryBig className="h-3.5 w-3.5" />
                    <span>按书</span>
                  </button>
                </div>

                <button
                  onClick={() => setView(view === 'timeline' ? 'books' : 'timeline')}
                  className="flex h-9 items-center gap-1 rounded-full bg-muted px-3 text-xs font-medium text-muted-foreground transition-colors sm:hidden"
                >
                  {view === 'timeline' ? <LibraryBig className="h-3.5 w-3.5" /> : <History className="h-3.5 w-3.5" />}
                  {view === 'timeline' ? '按书' : '时间轴'}
                </button>
              </div>
            </div>
          )}
        </div>
      </header>

      <div className="mx-auto w-full max-w-6xl px-4 py-4 pb-24 md:px-6 md:py-5">
        {hasNotes && !query && (
          <NoteStatsHero stats={stats!} onWrite={() => openComposer()} onOpenHeatmap={openHeatmap} />
        )}

        <div className={cn('mt-4', hasNotes && 'lg:grid lg:grid-cols-[minmax(0,1fr)_300px] lg:items-start lg:gap-6')}>
          <div className="min-w-0" id="note-timeline">
            {showExtras && (
              <div className="mb-4 space-y-3 lg:hidden">
                {insightEntry}
                {throwbackEntry}
              </div>
            )}

            {effectiveView === 'timeline' && (activeBook || activeTag || dayFilter) && (
              <div className="mb-3 flex flex-wrap items-center gap-2">
                {activeBook && (
                  <span className="flex h-8 items-center gap-1.5 rounded-full border border-primary/20 bg-primary/5 px-3 text-xs">
                    <BookOpen className="h-3.5 w-3.5 text-primary" />
                    只看《{activeBook.bookTitle ?? '未命名书籍'}》
                    <button onClick={() => setActiveBook(null)} aria-label="清除书籍筛选" className="flex h-6 w-6 items-center justify-center rounded-full transition-colors hover:bg-primary/10"><X className="h-3.5 w-3.5" /></button>
                  </span>
                )}
                {activeTag && (
                  <span className="flex h-8 items-center gap-1.5 rounded-full border border-primary/20 bg-primary/5 px-3 text-xs">
                    <Tag className="h-3.5 w-3.5 text-primary" />
                    #{activeTag}
                    <button onClick={() => setActiveTag(null)} aria-label="清除标签筛选" className="flex h-6 w-6 items-center justify-center rounded-full transition-colors hover:bg-primary/10"><X className="h-3.5 w-3.5" /></button>
                  </span>
                )}
                {dayFilter && (
                  <span className="flex h-8 items-center gap-1.5 rounded-full border border-primary/20 bg-primary/5 px-3 text-xs">
                    <CalendarDays className="h-3.5 w-3.5 text-primary" />
                    只看 {shortDayLabel(dayFilter)}
                    <button onClick={() => setDayFilter(null)} aria-label="清除日期筛选" className="flex h-6 w-6 items-center justify-center rounded-full transition-colors hover:bg-primary/10"><X className="h-3.5 w-3.5" /></button>
                  </span>
                )}
              </div>
            )}

            {effectiveView === 'books' ? (
              <NoteBooksView books={books} isLoading={booksLoading} onOpen={openBook} />
            ) : loading ? (
              <div className="space-y-3">
                {[0, 1, 2].map((i) => <div key={i} className="h-28 animate-pulse rounded-2xl bg-muted" />)}
              </div>
            ) : displayNotes.length === 0 ? (
              query ? (
                <div className="flex flex-col items-center gap-3 pt-16 text-muted-foreground">
                  <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-muted">
                    <Search className="h-7 w-7 text-muted-foreground/50" />
                  </div>
                  <p className="text-sm">没有找到相关笔记</p>
                  <p className="text-xs text-muted-foreground/70">换个关键词，或关掉「语义」再试试</p>
                </div>
              ) : isFiltered ? (
                <div className="flex flex-col items-center gap-3 pt-16 text-muted-foreground">
                  <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-muted">
                    <NotebookPen className="h-7 w-7 text-muted-foreground/50" />
                  </div>
                  <p className="text-sm">这个筛选下还没有笔记</p>
                  <button onClick={clearAllFilters} className="inline-flex h-8 items-center px-2 text-xs font-medium text-primary">清除筛选</button>
                </div>
              ) : (
                <WelcomeState onWrite={() => openComposer()} onPrompt={(seed) => openComposer(seed)} />
              )
            ) : (
              <div className="space-y-5">
                {semanticMode ? (
                  <div className="space-y-3">
                    {displayNotes.map((note) => (
                      <NoteCard
                        key={note.id}
                        note={note}
                        keyword={query}
                        onPin={(n) => pinMutation.mutate({ id: n.id, pinned: !n.pinned })}
                        onFavorite={(n) => favoriteMutation.mutate({ id: n.id, favorited: !n.favorited })}
                        onDelete={setPendingDelete}
                        onEdit={(n) => navigate(`/notes/${n.id}?edit=1`)}
                        onTagClick={selectTag}
                      />
                    ))}
                  </div>
                ) : (
                  <>
                    {pinnedNotes.length > 0 && (
                      <div>
                        <div className="mb-2 flex items-center gap-2">
                          <Pin className="h-3 w-3 rotate-45 text-primary" />
                          <p className="text-xs font-semibold text-primary">置顶</p>
                          <span className="text-xs text-muted-foreground/60">{pinnedNotes.length} 条</span>
                          <div className="h-px flex-1 bg-border/60" />
                        </div>
                        <div className="space-y-3">
                          {pinnedNotes.map((note) => (
                            <NoteCard
                              key={note.id}
                              note={note}
                              keyword={query}
                              onPin={(n) => pinMutation.mutate({ id: n.id, pinned: !n.pinned })}
                              onFavorite={(n) => favoriteMutation.mutate({ id: n.id, favorited: !n.favorited })}
                              onDelete={setPendingDelete}
                              onEdit={(n) => navigate(`/notes/${n.id}?edit=1`)}
                              onTagClick={selectTag}
                            />
                          ))}
                        </div>
                      </div>
                    )}

                    {sort === 'updated' && !query ? (
                      <div>
                        <div className="mb-2 flex items-center gap-2">
                          <Clock3 className="h-3 w-3 text-muted-foreground" />
                          <p className="shrink-0 text-xs font-semibold text-muted-foreground">最近编辑</p>
                          <div className="h-px flex-1 bg-border/60" />
                        </div>
                        <div className="space-y-3">
                          {timelineNotes.map((note) => (
                            <NoteCard
                              key={note.id}
                              note={note}
                              keyword={query}
                              onPin={(n) => pinMutation.mutate({ id: n.id, pinned: !n.pinned })}
                              onFavorite={(n) => favoriteMutation.mutate({ id: n.id, favorited: !n.favorited })}
                              onDelete={setPendingDelete}
                              onEdit={(n) => navigate(`/notes/${n.id}?edit=1`)}
                              onTagClick={selectTag}
                            />
                          ))}
                        </div>
                      </div>
                    ) : (
                      groups.map((g) => (
                        <div key={g.key}>
                          <div className="mb-2 flex items-center gap-2">
                            <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-primary/60" />
                            <p className="shrink-0 text-xs font-semibold text-muted-foreground">{dayLabel(g.key)}</p>
                            <span className="shrink-0 text-xs text-muted-foreground/60">{g.items.length} 条</span>
                            <div className="h-px flex-1 bg-border/60" />
                          </div>
                          <div className="space-y-3">
                            {g.items.map((note) => (
                              <NoteCard
                                key={note.id}
                                note={note}
                                keyword={query}
                                onPin={(n) => pinMutation.mutate({ id: n.id, pinned: !n.pinned })}
                                onFavorite={(n) => favoriteMutation.mutate({ id: n.id, favorited: !n.favorited })}
                                onDelete={setPendingDelete}
                                onEdit={(n) => navigate(`/notes/${n.id}?edit=1`)}
                                onTagClick={selectTag}
                              />
                            ))}
                          </div>
                        </div>
                      ))
                    )}
                  </>
                )}

                <div className="pt-1">
                  {semanticMode ? (
                    <p className="text-center text-xs text-muted-foreground">按语义相关度排序</p>
                  ) : hasNextPage ? (
                    <button
                      onClick={() => fetchNextPage()}
                      disabled={isFetchingNextPage}
                      className="w-full rounded-xl bg-muted py-3 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground disabled:opacity-50"
                    >
                      {isFetchingNextPage ? '加载中…' : '加载更多'}
                    </button>
                  ) : (
                    <p className="text-center text-xs text-muted-foreground">已经到底了</p>
                  )}
                </div>
              </div>
            )}
          </div>

          {hasNotes && !query && (
            <aside className="hidden lg:sticky lg:top-[120px] lg:block lg:space-y-3 lg:pr-12">
              {insightEntry}
              {throwbackEntry}
              {topBooks.length > 0 && (
                <BookRail books={topBooks} onOpen={openBook} onAll={() => setView('books')} />
              )}
              {tags && tags.length > 0 && (
                <TagRail tags={tags} activeTag={activeTag} onSelect={selectTag} />
              )}
              <PromptCard question={dailyQuestion} onWrite={() => openComposer(`${dailyQuestion}\n`)} />
            </aside>
          )}
        </div>
      </div>

      <Dialog open={heatmapOpen} onOpenChange={setHeatmapOpen}>
        <DialogContent className="gap-0 p-0 sm:max-w-3xl">
          <DialogHeader className="space-y-1 border-b border-border/50 px-5 py-4 pr-12">
            <DialogTitle className="flex items-center gap-2 text-base">
              <CalendarDays className="h-4 w-4 text-primary" />
              写作足迹
            </DialogTitle>
            <DialogDescription className="text-xs">
              过去一年在 {heatmapActiveDays} 天里写下 {heatmapTotal} 条笔记 · 点有颜色的格子可以只看那天
            </DialogDescription>
          </DialogHeader>
          <div className="px-5 py-4">
            <NoteHeatmap
              days={stats?.heatmap ?? []}
              selected={dayFilter}
              onSelectDay={(date) => {
                setDayFilter(date)
                setActiveBook(null)
                setActiveTag(null)
                setType('ALL')
                setFavoritedOnly(false)
                setRange('ALL')
                setView('timeline')
                setHeatmapOpen(false)
                setTimeout(() => document.getElementById('note-timeline')?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 80)
              }}
            />
          </div>
        </DialogContent>
      </Dialog>

      <AskNotesDialog open={askOpen} onOpenChange={setAskOpen} />

      <AlertDialog open={!!pendingDelete} onOpenChange={(open) => { if (!open) setPendingDelete(null) }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle className="flex items-center gap-2">
              <Trash2 className="h-4 w-4 text-destructive" />
              删除这条笔记？
            </AlertDialogTitle>
            <AlertDialogDescription>
              {pendingDelete?.title ? `「${pendingDelete.title}」` : '这条笔记'}将被移出时间轴，确定吗？
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => pendingDelete && deleteMutation.mutate(pendingDelete.id)}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              {deleteMutation.isPending ? '删除中…' : '删除'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
