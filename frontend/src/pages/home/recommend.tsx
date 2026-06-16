import { useEffect, useState, useCallback, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useGoBack } from '@/hooks/useGoBack'
import { useScrollRestore } from '@/hooks/useScrollRestore'
import { useKeepAliveStore } from '@/store/keepAlive'
import { useUiStore } from '@/store/ui'
import { ArrowLeft, Sparkles, RefreshCw, Trash2, Loader2, X } from 'lucide-react'
import { useInView } from 'react-intersection-observer'
import { getRecommendationsPage, generateRecommendationsStream } from '@/api/book'
import { moveToTrash } from '@/api/bookTrash'
import type { RecommendedItem, RecommendProgress } from '@/api/book'
import { BookCard } from '@/components/book/BookCard'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog'
import { toast } from 'sonner'


const PAGE_SIZE = 18

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

  return (
    <div className="relative overflow-hidden rounded-2xl break-inside-avoid">
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
        <BookCard book={{ ...book, id: book.bookId }} onClick={onClick} />
      </div>
    </div>
  )
}

const CACHE_KEY = '/recommend'
const CACHE_TTL = 10 * 60 * 1000

interface RecommendCache {
  books: RecommendedItem[]
  total: number
  page: number
  hasMore: boolean
  timestamp: number
}

export default function RecommendPage() {
  const navigate = useNavigate()
  const goBack = useGoBack()
  const savePageData = useKeepAliveStore((s) => s.savePageData)
  const getPageData = useKeepAliveStore((s) => s.getPageData)
  const clearPageData = useKeepAliveStore((s) => s.clearPageData)

  const cached = getPageData<RecommendCache>(CACHE_KEY)
  const isCacheValid = cached && Date.now() - cached.timestamp < CACHE_TTL

  const [books, setBooks] = useState<RecommendedItem[]>(() =>
    isCacheValid ? cached.books : [],
  )
  const [total, setTotal] = useState(() =>
    isCacheValid ? cached.total : 0,
  )
  const [loading, setLoading] = useState(() => !isCacheValid)
  const [loadingMore, setLoadingMore] = useState(false)
  const [generating, setGenerating] = useState(false)
  const [progress, setProgress] = useState<RecommendProgress | null>(null)
  const [profileIncomplete, setProfileIncomplete] = useState(false)
  const profileHintRef = useRef(
    typeof window !== 'undefined' && localStorage.getItem('kbook_profile_hint_dismissed') === '1'
  )
  const abortRef = useRef<AbortController | null>(null)
  const fetchingRef = useRef(false)
  const hasMoreRef = useRef(isCacheValid ? cached.hasMore : true)
  const pageRef = useRef(isCacheValid ? cached.page : 0)

  const [trashDialogOpen, setTrashDialogOpen] = useState(false)
  const [trashTarget, setTrashTarget] = useState<RecommendedItem | null>(null)
  const [trashing, setTrashing] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)
  const [scrollRoot, setScrollRoot] = useState<Element | null>(null)
  const { handleScroll } = useScrollRestore(scrollRef)

  const scrollRefCallback = useCallback((node: HTMLDivElement | null) => {
    scrollRef.current = node
    setScrollRoot(node)
  }, [])

  const { ref: sentinelRef, inView } = useInView({
    root: scrollRoot,
    rootMargin: '1000px',
  })

  const updateCache = useCallback((
    b: RecommendedItem[],
    t: number,
    p: number,
    h: boolean,
  ) => {
    savePageData(CACHE_KEY, {
      books: b,
      total: t,
      page: p,
      hasMore: h,
      timestamp: Date.now(),
    })
  }, [savePageData])

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

      if (data?.profileIncomplete && !profileHintRef.current) {
        setProfileIncomplete(true)
      }

      setBooks((prev) => {
        const merged = pageNum === 0 ? list : [...prev, ...list]
        return merged
      })
      setTotal(totalCount)
      const hasNext = list.length >= PAGE_SIZE && (pageNum + 1) * PAGE_SIZE < totalCount
      hasMoreRef.current = hasNext
      pageRef.current = pageNum

      setBooks((prev) => {
        updateCache(prev, totalCount, pageNum, hasNext)
        return prev
      })
    } catch {
      if (pageNum === 0) setBooks([])
    } finally {
      fetchingRef.current = false
      setLoading(false)
      setLoadingMore(false)
    }
  }, [updateCache])

  const startGenerate = useCallback(() => {
    if (abortRef.current) {
      abortRef.current.abort()
      abortRef.current = null
    }

    clearPageData(CACHE_KEY)
    setGenerating(true)
    setProgress({ stage: 'loading', message: '正在准备...', progress: 0 })
    setBooks([])
    setTotal(0)
    pageRef.current = 0
    hasMoreRef.current = true

    const triggerRefresh = useUiStore.getState().triggerRefreshRecommend

    const controller = generateRecommendationsStream(
      (data) => {
        setProgress(data)
      },
      () => {
        setGenerating(false)
        setProgress(null)
        abortRef.current = null
        loadPage(0)
        triggerRefresh()
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
  }, [loadPage, clearPageData])

  useEffect(() => {
    if (!isCacheValid) {
      loadPage(0)
    }
  }, [loadPage, isCacheValid])

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
      setBooks(prev => {
        const next = prev.filter(b => b.bookId !== trashTarget.bookId)
        setTotal(t => {
          updateCache(next, t - 1, pageRef.current, hasMoreRef.current)
          return t - 1
        })
        return next
      })
    } catch (err: any) {
      toast.error(err?.message || '操作失败')
    } finally {
      setTrashing(false)
      setTrashDialogOpen(false)
      setTrashTarget(null)
    }
  }

  return (
    <div className="absolute inset-0 flex flex-col overflow-hidden bg-background page-enter">
      <div className="shrink-0 z-50 bg-gradient-to-b from-background/95 via-background/80 to-background/60 pt-safe-top backdrop-blur-xl border-b border-border/30">
        <header className="flex items-center gap-3 px-4 py-3">
          <button onClick={() => goBack()} className="flex h-8 w-8 items-center justify-center rounded-lg hover:bg-muted transition-colors">
            <ArrowLeft className="h-5 w-5" />
          </button>
          <div className="flex items-center gap-2 flex-1">
            <h1 className="text-h3 font-bold">为你推荐</h1>
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

      <div ref={scrollRefCallback} onScroll={handleScroll} className="flex-1 overflow-y-auto overscroll-contain px-4 md:px-6 lg:px-8 py-3">
        {generating && progress ? (
          <div className="flex flex-col items-center justify-center py-20">
            <div className="w-full max-w-xs">
              <div className="mb-3 flex items-center justify-between">
                <span className="text-sm font-medium text-foreground">{progress.message}</span>
                <span className="text-xs text-muted-foreground">{progress.progress}%</span>
              </div>
              <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
                <div
                  className="h-full rounded-full bg-gradient-to-r from-warning to-warning transition-all duration-700 ease-out"
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
            {profileIncomplete && (
              <div className="mb-3 flex items-center gap-2 rounded-2xl bg-muted p-3 text-xs text-muted-foreground shadow-sm border border-border/50">
                <Sparkles className="h-3.5 w-3.5 shrink-0" />
                <span className="flex-1">完善画像，获取更懂你的专属推荐</span>
                <button
                  onClick={() => navigate('/profile')}
                  className="shrink-0 hover:text-foreground transition-colors"
                >
                  去完善
                </button>
                <button
                  onClick={() => {
                    localStorage.setItem('kbook_profile_hint_dismissed', '1')
                    profileHintRef.current = true
                    setProfileIncomplete(false)
                  }}
                  className="shrink-0 rounded-md p-0.5 hover:text-foreground transition-colors"
                  aria-label="关闭"
                >
                  <X className="h-3.5 w-3.5" />
                </button>
              </div>
            )}
            <div className="columns-1 sm:columns-2 lg:columns-3 gap-3 space-y-3">
              {books.map((book) => (
                <SwipeableBookCard
                  key={book.bookId}
                  book={book}
                  onClick={() => navigate(`/book/${book.bookId}`)}
                  onTrash={() => handleTrashClick(book)}
                />
              ))}

              {loadingMore && (
                <div className="[column-span:all] flex items-center justify-center py-4">
                  <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
                  <span className="ml-2 text-xs text-muted-foreground">加载中...</span>
                </div>
              )}

              {!hasMoreRef.current && books.length > 0 && (
                <div className="[column-span:all] flex justify-center py-6">
                  <span className="text-xs text-muted-foreground">— 已展示全部推荐 —</span>
                </div>
              )}
            </div>

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
