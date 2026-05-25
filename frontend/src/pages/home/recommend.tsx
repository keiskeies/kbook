import { useEffect, useState, useCallback, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { ArrowLeft, Sparkles, Star, RefreshCw, Tag } from 'lucide-react'
import { getRecommendationsPage, generateRecommendationsStream } from '@/api/book'
import type { RecommendedItem, RecommendProgress } from '@/api/book'
import BookCover from '@/components/book/BookCover'
import { parseFormatTags } from '@/types/book'

const PAGE_SIZE = 10

function RatingBadgeCN({ rating }: { rating: number | undefined | null }) {
  if (rating == null || rating < 0) return null
  const r = Number(rating.toFixed(1))
  let colorClass = ''
  if (r >= 5.0) colorClass = 'text-red-600 dark:text-red-400'
  else if (r >= 4.5) colorClass = 'text-orange-600 dark:text-orange-400'
  else if (r >= 4.0) colorClass = 'text-amber-600 dark:text-amber-400'
  else if (r >= 3.0) colorClass = 'text-emerald-600 dark:text-emerald-400'
  else if (r >= 2.5) colorClass = 'text-teal-600 dark:text-teal-400'
  else colorClass = 'text-slate-400 dark:text-slate-500'
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
  if (pct >= 100) colorClass = 'text-red-600 dark:text-red-400'
  else if (pct >= 80) colorClass = 'text-orange-600 dark:text-orange-400'
  else if (pct >= 60) colorClass = 'text-amber-600 dark:text-amber-400'
  else if (pct >= 50) colorClass = 'text-emerald-600 dark:text-emerald-400'
  else if (pct >= 40) colorClass = 'text-teal-600 dark:text-teal-400'
  else colorClass = 'text-slate-400 dark:text-slate-500'
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

export default function RecommendPage() {
  const navigate = useNavigate()
  const [books, setBooks] = useState<RecommendedItem[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [generating, setGenerating] = useState(false)
  const [progress, setProgress] = useState<RecommendProgress | null>(null)
  const abortRef = useRef<AbortController | null>(null)
  const sentinelRef = useRef<HTMLDivElement | null>(null)
  const hasMoreRef = useRef(true)
  const loadingRef = useRef(false)
  const pageRef = useRef(0)

  const fetchPage = useCallback(async (pageNum: number, append: boolean) => {
    if (loadingRef.current) return
    loadingRef.current = true

    if (append) setLoadingMore(true)
    else setLoading(true)

    try {
      const res = await getRecommendationsPage(pageNum, PAGE_SIZE) as any
      const data = res?.data || res
      const list: RecommendedItem[] = data?.list || []
      const totalCount: number = data?.total || 0

      if (append) {
        setBooks(prev => [...prev, ...list])
      } else {
        setBooks(list)
      }
      setTotal(totalCount)
      pageRef.current = pageNum
      hasMoreRef.current = list.length >= PAGE_SIZE && (pageNum * PAGE_SIZE) < totalCount
    } catch {
      if (!append) setBooks([])
    } finally {
      setLoading(false)
      setLoadingMore(false)
      loadingRef.current = false
    }
  }, [])

  const loadNextPage = useCallback(() => {
    if (loadingRef.current || !hasMoreRef.current) return
    fetchPage(pageRef.current + 1, true)
  }, [fetchPage])

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
        fetchPage(1, false)
      },
      (error) => {
        console.error('推荐生成失败:', error)
        setGenerating(false)
        setProgress(null)
        abortRef.current = null
        fetchPage(1, false)
      },
    )
    abortRef.current = controller
  }, [fetchPage])

  useEffect(() => {
    fetchPage(1, false)
  }, [fetchPage])

  useEffect(() => {
    const sentinel = sentinelRef.current
    if (!sentinel) return

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && hasMoreRef.current && !loadingRef.current) {
          loadNextPage()
        }
      },
      { rootMargin: '300px' },
    )
    observer.observe(sentinel)
    return () => observer.disconnect()
  }, [loadNextPage, books.length, generating])

  useEffect(() => {
    return () => {
      if (abortRef.current) abortRef.current.abort()
    }
  }, [])

  return (
    <div className="min-h-screen bg-background page-enter">
      <div className="sticky top-0 z-50 bg-gradient-to-b from-background/95 via-background/80 to-background/60 pt-safe-top backdrop-blur-xl border-b border-border/30">
        <header className="flex items-center gap-3 px-4 py-3">
          <button onClick={() => navigate(-1)} className="flex h-8 w-8 items-center justify-center rounded-lg hover:bg-muted transition-colors">
            <ArrowLeft className="h-5 w-5" />
          </button>
          <div className="flex items-center gap-2 flex-1">
            <Sparkles className="h-5 w-5 text-amber-500" />
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
                  className="h-full rounded-full bg-gradient-to-r from-amber-400 to-amber-500 transition-all duration-300 ease-out"
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
              {books.map((book) => {
                const tags = parseFormatTags(book.formatTags || '')
                return (
                  <div
                    key={book.bookId}
                    className="rounded-2xl bg-card p-3 shadow-sm border border-border/50 cursor-pointer active:scale-[0.98] transition-all duration-150"
                    onClick={() => navigate(`/book/${book.bookId}`)}
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
                )
              })}
            </div>

            <div ref={sentinelRef} className="h-4" />

            {loadingMore && (
              <div className="flex justify-center py-4">
                <RefreshCw className="h-5 w-5 animate-spin text-muted-foreground" />
              </div>
            )}

            {!hasMoreRef.current && books.length > 0 && (
              <div className="flex justify-center py-6">
                <span className="text-xs text-muted-foreground">— 已展示全部推荐 —</span>
              </div>
            )}
          </>
        ) : (
          <div className="flex h-[50vh] flex-col items-center justify-center">
            <Sparkles className="mb-4 h-12 w-12 text-muted-foreground" />
            <p className="text-sm text-muted-foreground">暂无推荐，点击右上角"重新计算"试试吧</p>
          </div>
        )}
      </div>
    </div>
  )
}
