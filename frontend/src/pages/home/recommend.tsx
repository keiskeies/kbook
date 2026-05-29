import { useEffect, useState, useCallback, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { ArrowLeft, Sparkles, Star, RefreshCw, Tag, Trash2, Loader2 } from 'lucide-react'
import { useInView } from 'react-intersection-observer'
import { getRecommendationsPage, generateRecommendationsStream } from '@/api/book'
import { moveToTrash } from '@/api/bookTrash'
import type { RecommendedItem, RecommendProgress } from '@/api/book'
import BookCover from '@/components/book/BookCover'
import { parseFormatTags } from '@/types/book'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog'
import { toast } from 'sonner'

const PAGE_SIZE = 20

function RatingBadgeCN({ rating }: { rating: number | undefined | null }) {
  if (rating == null || rating < 0) return null
  const r = Number(rating.toFixed(1))
  let colorClass = ''
  if (r >= 5.0) colorClass = 'text-danger dark:text-danger'
  else if (r >= 4.5) colorClass = 'text-warning dark:text-warning'
  else if (r >= 4.0) colorClass = 'text-warning dark:text-warning'
  else if (r >= 3.0) colorClass = 'text-success dark:text-success'
  else if (r >= 2.5) colorClass = 'text-success dark:text-success'
  else colorClass = 'text-muted-foreground dark:text-muted-foreground'
  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${colorClass}`}>
      <Star className="h-2.5 w-2.5" />
      评分：{r}
    </span>
  )
}

function MatchBadgeCN({ score }: { score: number | undefined | null }) {
  const pct = Math.round(Math.max(0, score ?? 0) * 100)
  let colorClass = ''
  if (pct >= 100) colorClass = 'text-danger dark:text-danger'
  else if (pct >= 80) colorClass = 'text-warning dark:text-warning'
  else if (pct >= 60) colorClass = 'text-warning dark:text-warning'
  else if (pct >= 50) colorClass = 'text-success dark:text-success'
  else if (pct >= 40) colorClass = 'text-success dark:text-success'
  else colorClass = 'text-muted-foreground dark:text-muted-foreground'
  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${colorClass}`}>
      <Sparkles className="h-2.5 w-2.5" />
      匹配度：{pct}%
    </span>
  )
}

function fmtReadCount(n: number): string {
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万次阅读`
  return `${n}次阅读`
}

function fmtFileSize(bytes: number | null | undefined): string {
  if (bytes == null || bytes <= 0) return ''
  if (bytes < 1024) return `${bytes}B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)}MB`
}

const SWIPE_THRESHOLD = 60
const SWIPE_MAX = 80

function SwipeableBookCard({
  book,
  onClick,
  onTrash,
}: {
  book: RecommendedItem
  onClick: () => void
  onTrash: () => void
}) {
  const [offsetX, setOffsetX] = useState(0)
  const startXRef = useRef(0)
  const startYRef = useRef(0)
  const currentXRef = useRef(0)
  const directionRef = useRef<'none' | 'horizontal' | 'vertical'>('none')

  const handleTouchStart = (e: React.TouchEvent) => {
    startXRef.current = e.touches[0].clientX
    startYRef.current = e.touches[0].clientY
    currentXRef.current = 0
    directionRef.current = 'none'
  }

  const handleTouchMove = (e: React.TouchEvent) => {
    const diffX = e.touches[0].clientX - startXRef.current
    const diffY = e.touches[0].clientY - startYRef.current

    if (directionRef.current === 'none') {
      if (Math.abs(diffX) > 8 || Math.abs(diffY) > 8) {
        directionRef.current = Math.abs(diffX) > Math.abs(diffY) ? 'horizontal' : 'vertical'
      } else {
        return
      }
    }

    if (directionRef.current === 'vertical') return

    currentXRef.current = diffX
    if (diffX < 0) {
      setOffsetX(Math.max(diffX, -SWIPE_MAX))
    } else {
      if (offsetX < 0) {
        setOffsetX(Math.min(0, diffX))
      }
    }
  }

  const handleTouchEnd = () => {
    if (directionRef.current === 'horizontal' && currentXRef.current < -SWIPE_THRESHOLD) {
      setOffsetX(-SWIPE_MAX)
    } else {
      setOffsetX(0)
    }
    directionRef.current = 'none'
  }

  const tags = parseFormatTags(book.formatTags || '')

  return (
    <div className="relative overflow-hidden rounded-2xl">
      <div
        className="absolute right-0 top-0 bottom-0 flex items-center justify-center bg-red-500 text-white transition-opacity duration-200"
        style={{ 
          width: SWIPE_MAX,
          opacity: offsetX < -10 ? 1 : 0
        }}
      >
        <button
          onClick={onTrash}
          className="flex flex-col items-center justify-center h-full w-full gap-1 active:bg-red-600 transition-colors"
        >
          <Trash2 className="h-5 w-5" />
          <span className="text-xs font-medium">垃圾桶</span>
        </button>
      </div>
      <div
        className="relative bg-card p-3 shadow-sm border border-border/50 cursor-pointer active:scale-[0.98] transition-transform duration-150"
        style={{
          transform: `translateX(${offsetX}px)`,
          transition: directionRef.current === 'horizontal' ? 'none' : 'transform 0.2s ease-out',
        }}
        onClick={() => {
          if (offsetX === 0) onClick()
          else setOffsetX(0)
        }}
        onTouchStart={handleTouchStart}
        onTouchMove={handleTouchMove}
        onTouchEnd={handleTouchEnd}
      >
        <div className="flex gap-3">
          <BookCover
            coverUrl={book.coverUrl}
            title={book.title}
            author={book.author}
            format={book.format}
            size="md"
            className="flex-shrink-0"
          />
          <div className="flex-1 min-w-0 flex flex-col justify-between">
            <div>
              <p className="truncate text-sm font-semibold">{book.title}</p>
              <p className="mt-0.5 truncate text-xs text-muted-foreground">
                {book.author || '未知作者'}
              </p>
            </div>

            <div className="mt-1.5 flex items-center gap-1.5 flex-wrap">
              <RatingBadgeCN rating={book.rating} />
              <MatchBadgeCN score={book.matchScore} />
              {book.readCount != null && book.readCount > 0 && (
                <span className="text-[11px] text-muted-foreground">
                  {fmtReadCount(book.readCount)}
                </span>
              )}
              {fmtFileSize(book.fileSize) && (
                <span className="text-[11px] text-muted-foreground">
                  {fmtFileSize(book.fileSize)}
                </span>
              )}
            </div>

            {tags.length > 0 && (
              <div className="mt-1.5 flex items-center gap-1.5 overflow-x-auto scrollbar-hide">
                {tags.map((t) => (
                  <span
                    key={t}
                    className="inline-flex shrink-0 items-center gap-0.5 whitespace-nowrap rounded-md bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary"
                  >
                    <Tag className="h-2.5 w-2.5" />
                    {t}
                  </span>
                ))}
              </div>
            )}
          </div>
        </div>

        {book.description && (
          <div className="mt-2 border-t border-border/30 pt-2">
            <p className="text-[11px] text-muted-foreground/70 leading-relaxed line-clamp-2">
              {book.description}
            </p>
          </div>
        )}
      </div>
    </div>
  )
}

export default function RecommendPage() {
  const navigate = useNavigate()
  const [books, setBooks] = useState<RecommendedItem[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [generating, setGenerating] = useState(false)
  const [progress, setProgress] = useState<RecommendProgress | null>(null)
  const abortRef = useRef<AbortController | null>(null)
  const fetchingRef = useRef(false)
  const hasMoreRef = useRef(true)
  const pageRef = useRef(0)

  const { ref: sentinelRef, inView } = useInView({
    root: document.body,
    rootMargin: '1000px',
  })

  const [trashDialogOpen, setTrashDialogOpen] = useState(false)
  const [trashTarget, setTrashTarget] = useState<RecommendedItem | null>(null)
  const [trashing, setTrashing] = useState(false)

  const loadPage = useCallback(async (pageNum: number) => {
    if (fetchingRef.current) return
    fetchingRef.current = true
    if (pageNum === 0) {
      setLoading(true)
    } else {
      setLoadingMore(true)
    }
    try {
      const res = await getRecommendationsPage(pageNum + 1, PAGE_SIZE) as any
      const data = res?.data || res
      const list: RecommendedItem[] = data?.list || []
      const totalCount: number = data?.total || 0

      setBooks((prev) => pageNum === 0 ? list : [...prev, ...list])
      setTotal(totalCount)
      const hasNext = list.length >= PAGE_SIZE && (pageNum + 1) * PAGE_SIZE < totalCount
      hasMoreRef.current = hasNext
      pageRef.current = pageNum
    } catch {
      if (pageNum === 0) setBooks([])
    } finally {
      fetchingRef.current = false
      setLoading(false)
      setLoadingMore(false)
    }
  }, [])

  const startGenerate = useCallback(() => {
    if (abortRef.current) {
      abortRef.current.abort()
      abortRef.current = null
    }

    setGenerating(true)
    setProgress({ stage: 'loading', message: '正在准备...', progress: 0 })
    setBooks([])
    setTotal(0)
    pageRef.current = 0

    const controller = generateRecommendationsStream(
      (data) => {
        setProgress(data)
      },
      () => {
        setGenerating(false)
        setProgress(null)
        abortRef.current = null
        loadPage(0)
      },
      (error) => {
        console.error('推荐生成失败:', error)
        setGenerating(false)
        setProgress(null)
        abortRef.current = null
        loadPage(0)
      },
    )
    abortRef.current = controller
  }, [loadPage])

  useEffect(() => {
    loadPage(0)
  }, [loadPage])

  useEffect(() => {
    if (inView && hasMoreRef.current && !fetchingRef.current && !loading) {
      loadPage(pageRef.current + 1)
    }
  }, [inView, books.length, loadPage, loading])

  const handleTrashClick = (book: RecommendedItem) => {
    setTrashTarget(book)
    setTrashDialogOpen(true)
  }

  const handleConfirmTrash = async () => {
    if (!trashTarget) return
    setTrashing(true)
    try {
      await moveToTrash(trashTarget.bookId)
      toast.success('已丢入垃圾桶')
      setBooks(prev => prev.filter(b => b.bookId !== trashTarget.bookId))
      setTotal(prev => prev - 1)
    } catch (err: any) {
      toast.error(err?.message || '操作失败')
    } finally {
      setTrashing(false)
      setTrashDialogOpen(false)
      setTrashTarget(null)
    }
  }

  return (
    <div className="min-h-screen bg-background page-enter">
      <div className="sticky top-0 z-50 bg-gradient-to-b from-background/95 via-background/80 to-background/60 pt-safe-top backdrop-blur-xl border-b border-border/30">
        <header className="flex items-center gap-3 px-4 py-3">
          <button onClick={() => navigate(-1)} className="flex h-8 w-8 items-center justify-center rounded-lg hover:bg-muted transition-colors">
            <ArrowLeft className="h-5 w-5" />
          </button>
          <div className="flex items-center gap-2 flex-1">
            <Sparkles className="h-5 w-5 text-warning" />
            <h1 className="text-lg font-bold">为你推荐</h1>
            {total > 0 && !generating && (
              <span className="text-xs text-muted-foreground">共{total}本</span>
            )}
          </div>
          <button
            onClick={startGenerate}
            disabled={generating}
            className="flex items-center gap-1.5 rounded-full border border-border/50 bg-card px-3.5 py-1.5 text-xs font-medium text-muted-foreground shadow-sm transition-all active:scale-95 hover:border-primary/30 hover:text-primary disabled:opacity-50"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${generating ? 'animate-spin' : ''}`} />
            重新计算
          </button>
        </header>
      </div>

      <div className="px-4 py-3">
        {generating && progress ? (
          <div className="flex flex-col items-center justify-center py-20">
            <div className="w-full max-w-xs">
              <div className="mb-3 flex items-center justify-between">
                <span className="text-sm font-medium text-foreground">{progress.message}</span>
                <span className="text-xs text-muted-foreground">{progress.progress}%</span>
              </div>
              <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
                <div
                  className="h-full rounded-full bg-gradient-to-r from-warning to-warning transition-all duration-300 ease-out"
                  style={{ width: `${progress.progress}%` }}
                />
              </div>
              {progress.current != null && progress.total != null && progress.total > 0 && (
                <p className="mt-2 text-center text-xs text-muted-foreground">
                  {progress.current} / {progress.total} 本
                </p>
              )}
            </div>
          </div>
        ) : loading && books.length === 0 ? (
          <div className="space-y-3">
            {Array.from({ length: 5 }, (_, i) => (
              <div key={i} className="rounded-2xl bg-card p-3 shadow-sm border border-border/50">
                <div className="flex gap-3">
                  <div className="h-24 w-16 flex-shrink-0 rounded-lg bg-muted animate-pulse" />
                  <div className="flex-1 space-y-2">
                    <div className="h-4 w-3/4 rounded bg-muted animate-pulse" />
                    <div className="h-3 w-1/2 rounded bg-muted animate-pulse" />
                    <div className="h-3 w-full rounded bg-muted animate-pulse" />
                  </div>
                </div>
              </div>
            ))}
          </div>
        ) : books.length > 0 ? (
          <>
            <div className="space-y-2.5">
              {books.map((book) => (
                <SwipeableBookCard
                  key={book.bookId}
                  book={book}
                  onClick={() => navigate(`/book/${book.bookId}`)}
                  onTrash={() => handleTrashClick(book)}
                />
              ))}
            </div>

            {loadingMore && (
              <div className="flex items-center justify-center py-4">
                <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
                <span className="ml-2 text-xs text-muted-foreground">加载中...</span>
              </div>
            )}

            {!hasMoreRef.current && books.length > 0 && (
              <div className="flex justify-center py-6">
                <span className="text-xs text-muted-foreground">— 已展示全部推荐 —</span>
              </div>
            )}

            {hasMoreRef.current && <div ref={sentinelRef} className="h-1" />}
          </>
        ) : (
          <div className="flex h-[50vh] flex-col items-center justify-center">
            <Sparkles className="mb-4 h-12 w-12 text-muted-foreground" />
            <p className="text-sm text-muted-foreground">暂无推荐，点击右上角"重新计算"试试吧</p>
          </div>
        )}
      </div>

      <Dialog open={trashDialogOpen} onOpenChange={setTrashDialogOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>丢入垃圾桶</DialogTitle>
          </DialogHeader>
          <p className="text-sm text-muted-foreground">
            确定将「{trashTarget?.title}」丢入垃圾桶吗？丢入垃圾桶后，该图书将不再出现在推荐列表中。你可以前往「我的 → 推荐 → 垃圾桶」中恢复。
          </p>
          <DialogFooter>
            <button
              onClick={() => setTrashDialogOpen(false)}
              className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground hover:bg-muted transition-colors"
            >
              取消
            </button>
            <button
              onClick={handleConfirmTrash}
              disabled={trashing}
              className="rounded-lg bg-red-500 px-4 py-2 text-sm font-medium text-white hover:bg-red-600 transition-colors disabled:opacity-50"
            >
              {trashing ? '处理中...' : '丢入垃圾桶'}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
