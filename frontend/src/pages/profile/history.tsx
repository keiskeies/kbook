import { useEffect, useState, useRef, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useGoBack } from '@/hooks/useGoBack'
import { useScrollRestore } from '@/hooks/useScrollRestore'
import { ArrowLeft, Clock, ChevronDown, ChevronUp, BookOpen, Star, Sparkles, Loader2, AlertCircle, RefreshCw } from 'lucide-react'
import { useInView } from 'react-intersection-observer'
import { getReadingHistory } from '@/api/progress'
import { formatRelativeTime } from '@/utils/time'
import BookCover from '@/components/book/BookCover'
import { useMatchScores } from '@/hooks/useMatchScores'
import { useKeepAliveStore } from '@/store/keepAlive'
import { toast } from 'sonner'

const PAGE_SIZE = 10

const CACHE_KEY = '/profile/history'
const CACHE_TTL = 5 * 60 * 1000

interface HistoryCache {
  items: HistoryItem[]
  total: number
  page: number
  hasMore: boolean
  timestamp: number
}

function DescriptionBlock({ description }: { description: string }) {
  const [expanded, setExpanded] = useState(false)
  const short = description.length > 80 ? description.slice(0, 80) + '...' : description
  const needToggle = description.length > 80

  return (
    <div className="px-3.5 pb-3.5">
      <div
        onClick={(e) => { e.stopPropagation(); if (needToggle) setExpanded(!expanded) }}
        className={needToggle ? 'cursor-pointer' : ''}
      >
        <p className="text-xs text-muted-foreground leading-relaxed">
          {expanded ? description : short}
        </p>
        {needToggle && (
          <button className="flex items-center gap-0.5 mt-1 text-xs text-primary hover:underline">
            {expanded ? <><ChevronUp className="h-3 w-3" />收起</> : <><ChevronDown className="h-3 w-3" />展开</>}
          </button>
        )}
      </div>
    </div>
  )
}

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
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-xs font-semibold ${colorClass}`}>
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
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-xs font-semibold ${colorClass}`}>
      <Sparkles className="h-2.5 w-2.5" />
      匹配度：{pct}%
    </span>
  )
}

interface HistoryItem {
  progressId: number
  bookId: number
  progress: number
  currentPosition: string | null
  updatedAt: string
  title: string | null
  author: string | null
  coverUrl: string | null
  format: string | null
  fileSize: number | null
  rating: number | null
  readCount: number | null
  description: string | null
}

export default function ReadingHistoryPage() {
  const navigate = useNavigate()
  const goBack = useGoBack()
  const savePageData = useKeepAliveStore((s) => s.savePageData)
  const getPageData = useKeepAliveStore((s) => s.getPageData)

  const cached = getPageData<HistoryCache>(CACHE_KEY)
  const isCacheValid = cached && Date.now() - cached.timestamp < CACHE_TTL

  const [items, setItems] = useState<HistoryItem[]>(() => isCacheValid ? cached.items : [])
  const [loading, setLoading] = useState(() => !isCacheValid)
  const [loadingMore, setLoadingMore] = useState(false)
  const [hasMore, setHasMore] = useState(() => isCacheValid ? cached.hasMore : true)
  const [total, setTotal] = useState(() => isCacheValid ? cached.total : 0)
  const [error, setError] = useState<string | null>(null)
  const fetchingRef = useRef(false)
  const hasMoreRef = useRef(isCacheValid ? cached.hasMore : true)
  const pageRef = useRef(isCacheValid ? cached.page : 0)
  const scrollRef = useRef<HTMLDivElement>(null)
  const [scrollRoot, setScrollRoot] = useState<Element | null>(null)
  const { handleScroll } = useScrollRestore(scrollRef)

  const updateCache = useCallback((i: HistoryItem[], t: number, p: number, h: boolean) => {
    savePageData(CACHE_KEY, { items: i, total: t, page: p, hasMore: h, timestamp: Date.now() })
  }, [savePageData])

  const scrollRefCallback = useCallback((node: HTMLDivElement | null) => {
    scrollRef.current = node
    setScrollRoot(node)
  }, [])

  const { ref: sentinelRef, inView } = useInView({
    root: scrollRoot,
    rootMargin: '1000px',
  })

  const loadPage = useCallback(async (pageNum: number) => {
    if (fetchingRef.current) return
    fetchingRef.current = true
    if (pageNum === 0) {
      setLoading(true)
    } else {
      setLoadingMore(true)
    }
    try {
      const res = await getReadingHistory(pageNum, PAGE_SIZE)
      const data = (res as any)?.data || (res as any)
      const list: any[] = Array.isArray(data?.list) ? data.list : []
      const totalCount = data?.total ?? 0

      const mapped: HistoryItem[] = list.map((r: any) => ({
        progressId: r.progressId,
        bookId: r.bookId,
        progress: r.progress ?? 0,
        currentPosition: r.currentPosition,
        updatedAt: r.updatedAt,
        title: r.title,
        author: r.author,
        coverUrl: r.coverUrl,
        format: r.format,
        fileSize: r.fileSize,
        rating: r.rating,
        readCount: r.readCount,
        description: r.description,
      }))

      const hasNext = mapped.length === PAGE_SIZE && (pageNum + 1) * PAGE_SIZE < totalCount

      setItems((prev) => {
        const next = pageNum === 0 ? mapped : [...prev, ...mapped]
        updateCache(next, totalCount, pageNum, hasNext)
        return next
      })
      setTotal(totalCount)
      hasMoreRef.current = hasNext
      setHasMore(hasNext)
      pageRef.current = pageNum
    } catch (err: any) {
      const msg = err?.message || '加载失败'
      if (pageNum === 0) {
        setItems([])
        setError(msg)
      } else {
        toast.error(msg)
      }
    } finally {
      fetchingRef.current = false
      setLoading(false)
      setLoadingMore(false)
    }
  }, [updateCache])

  useEffect(() => {
    if (!isCacheValid) { loadPage(0) }
  }, [loadPage, isCacheValid])

  useEffect(() => {
    if (inView && hasMoreRef.current && !fetchingRef.current && !loading) {
      loadPage(pageRef.current + 1)
    }
  }, [inView, items.length, loadPage, loading])

  const bookIds = items.map((i) => i.bookId).filter(Boolean)
  const matchScores = useMatchScores(bookIds)

  if (loading) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent" />
      </div>
    )
  }

  return (
    <div className="absolute inset-0 flex flex-col overflow-hidden bg-background page-enter">
      <header className="shrink-0 z-10 flex items-center gap-3 border-b border-border/50 bg-background/80 px-4 py-3 backdrop-blur-xl">
        <button onClick={() => goBack()} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted transition-colors">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="flex-1 text-base font-bold">阅读历史</h1>
        <span className="text-xs text-muted-foreground">{total} 本</span>
      </header>

      <div ref={scrollRefCallback} onScroll={handleScroll} className="flex-1 overflow-y-auto overscroll-contain px-4 md:px-6 lg:px-8">
      {items.length === 0 ? (
        <div className="flex h-60 flex-col items-center justify-center text-muted-foreground">
          {error ? (
            <>
              <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-danger/10">
                <AlertCircle className="h-8 w-8 text-danger/60" />
              </div>
              <p className="mt-4 text-sm text-danger">{error}</p>
              <button
                onClick={() => { setError(null); loadPage(0) }}
                className="mt-3 inline-flex items-center gap-1.5 rounded-xl bg-primary px-4 py-2 text-xs font-medium text-primary-foreground active:scale-[0.97] transition-transform"
              >
                <RefreshCw className="h-3.5 w-3.5" />
                重试
              </button>
            </>
          ) : (
            <>
              <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-muted">
                <BookOpen className="h-8 w-8 text-muted-foreground/50" />
              </div>
              <p className="mt-4 text-sm">暂无阅读记录</p>
            </>
          )}
        </div>
      ) : (
        <div className="py-3 columns-1 sm:columns-2 lg:columns-3 gap-3 space-y-3">
          {items.map((item) => {
            const ms = item.bookId ? matchScores?.[String(item.bookId)] : null

            return (
              <div
                key={item.progressId}
                className="flex flex-col rounded-2xl bg-card shadow-sm border border-border/50 active:scale-[0.98] transition-all duration-150 cursor-pointer overflow-hidden break-inside-avoid"
                onClick={() => item.bookId && navigate(`/book/${item.bookId}`)}
              >
                <div className="flex items-center gap-3.5 p-3.5">
                  <BookCover coverUrl={item.coverUrl} title={item.title ?? '未知图书'} author={item.author} size="sm" className="flex-shrink-0 shadow-sm" />

                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-semibold">{item.title || '未知图书'}</p>
                    <p className="mt-0.5 text-xs text-muted-foreground truncate">{item.author || '未知作者'}</p>

                    <div className="mt-1.5 flex items-center gap-1.5">
                      <RatingBadgeCN rating={item.rating} />
                      <MatchBadgeCN score={ms} />
                    </div>
                  </div>

                  <div className="flex flex-shrink-0 flex-col items-end gap-1">
                    <span className="flex items-center gap-1 text-xs text-muted-foreground">
                      <Clock className="h-2.5 w-2.5" />
                      {formatRelativeTime(item.updatedAt)}
                    </span>
                    {item.format && (
                      <span className="rounded-md bg-primary/8 px-1.5 py-0.5 text-xs font-medium text-primary">
                        {item.format}
                      </span>
                    )}
                  </div>
                </div>

                {item.description && (
                  <DescriptionBlock description={item.description} />
                )}
              </div>
            )
          })}

          {loadingMore && (
            <div className="[column-span:all] flex items-center justify-center py-4">
              <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
              <span className="ml-2 text-xs text-muted-foreground">加载中...</span>
            </div>
          )}

          {!hasMore && items.length > 0 && (
            <div className="[column-span:all] py-4 text-center text-xs text-muted-foreground">
              没有更多了
            </div>
          )}

          {hasMore && <div ref={sentinelRef} className="h-1" />}
        </div>
      )}
      </div>
    </div>
  )
}
