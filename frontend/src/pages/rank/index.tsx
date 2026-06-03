import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Flame, Award, Sparkles, Tag, Clock, Star, ChevronDown, ChevronUp, TrendingUp, Loader2 } from 'lucide-react'
import { useInView } from 'react-intersection-observer'
import { getReadRank, getRatingRank, getNewBooksRank } from '@/api/book'
import type { Book } from '@/types/book'
import { parseFormatTags } from '@/types/book'
import BookCover from '@/components/book/BookCover'
import { useMatchScores } from '@/hooks/useMatchScores'
import { useIsMobile } from '@/hooks/use-mobile'

type RankType = 'read' | 'rating' | 'new'

const RANK_TABS: { key: RankType; label: string; icon: React.ReactNode }[] = [
  { key: 'read', label: '热门阅读', icon: <Flame className="h-4 w-4" /> },
  { key: 'rating', label: '高分推荐', icon: <Award className="h-4 w-4" /> },
  { key: 'new', label: '新书速递', icon: <Clock className="h-4 w-4" /> },
]

/** 格式化阅读量 */
function fmtReadCount(n: number): string {
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万次阅读`
  return `${n}次阅读`
}

/** 简介展开/收起组件 */
function BookDescription({ description }: { description: string }) {
  const [expanded, setExpanded] = useState(false)
  const isLong = description.length > 80

  return (
    <div className="mt-2 border-t border-border/30 pt-2">
      <p
        className={`text-[11px] text-muted-foreground/70 leading-relaxed transition-all duration-200 ${
          expanded ? '' : 'line-clamp-2'
        }`}
      >
        {description}
      </p>
      {isLong && (
        <button
          onClick={(e) => {
            e.stopPropagation()
            setExpanded(!expanded)
          }}
          className="mt-1 flex items-center gap-0.5 text-[10px] text-primary/80 hover:text-primary font-medium"
        >
          {expanded ? (
            <>
              <ChevronUp className="h-3 w-3" />
              收起
            </>
          ) : (
            <>
              <ChevronDown className="h-3 w-3" />
              展开
            </>
          )}
        </button>
      )}
    </div>
  )
}

/** 评分徽章 */
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

/** 匹配度徽章 */
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

const PAGE_SIZE = 10

/** 单列榜单：独立数据加载 + 无限滚动 */
function RankColumn({ type, label, icon, navigate }: {
  type: RankType
  label: string
  icon: React.ReactNode
  navigate: (path: string) => void
}) {
  const [books, setBooks] = useState<Book[]>([])
  const [hasMore, setHasMore] = useState(true)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const fetchingRef = useRef(false)
  const hasMoreRef = useRef(true)
  const pageRef = useRef(0)

  const { ref: sentinelRef, inView } = useInView({ rootMargin: '400px' })

  const fetcher = type === 'read' ? getReadRank
    : type === 'rating' ? getRatingRank
    : getNewBooksRank

  const loadPage = useCallback(async (pageNum: number) => {
    if (fetchingRef.current) return
    fetchingRef.current = true
    if (pageNum > 0) setLoadingMore(true)
    try {
      const res = await fetcher(pageNum + 1, PAGE_SIZE)
      const list = res.list ?? []
      setBooks((prev) => pageNum === 0 ? list : [...prev, ...list])
      const hasNext = list.length === PAGE_SIZE
      hasMoreRef.current = hasNext
      setHasMore(hasNext)
      pageRef.current = pageNum
    } catch {
      if (pageNum === 0) setBooks([])
    } finally {
      fetchingRef.current = false
      if (pageNum === 0) setLoading(false)
      else setLoadingMore(false)
    }
  }, [fetcher])

  useEffect(() => {
    loadPage(0)
  }, [loadPage])

  useEffect(() => {
    if (inView && hasMoreRef.current && !fetchingRef.current && !loading) {
      loadPage(pageRef.current + 1)
    }
  }, [inView, books.length, loadPage, loading])

  const matchScores = useMatchScores(books.map(b => b.id))

  return (
    <div className="flex flex-col h-full min-h-0">
      {/* 列标题 */}
      <div className="shrink-0 flex items-center gap-2 pb-3 border-b border-border/30 mb-3">
        <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-primary/10">
          {icon}
        </div>
        <h2 className="text-sm font-bold">{label}</h2>
      </div>

      {/* 列表内容 - 独立滚动 */}
      <div className="flex-1 min-h-0 overflow-y-auto overscroll-contain pr-1 -mr-1">
        {loading ? (
          <div className="space-y-2">
            {Array.from({ length: 5 }, (_, i) => (
              <div key={i} className="rounded-xl bg-card p-3 shadow-sm border border-border/50">
                <div className="flex gap-3">
                  <div className="h-7 w-7 flex-shrink-0 rounded-xl bg-muted animate-pulse" />
                  <div className="flex-1 space-y-2">
                    <div className="h-4 w-3/4 rounded bg-muted animate-pulse" />
                    <div className="h-3 w-1/2 rounded bg-muted animate-pulse" />
                  </div>
                </div>
              </div>
            ))}
          </div>
        ) : books.length > 0 ? (
          <div className="space-y-2">
            {books.map((book, index) => {
              const ms = matchScores?.[String(book.id)]
              const tags = parseFormatTags(book.formatTags)
              return (
                <div
                  key={book.id}
                  className="rounded-xl bg-card p-3 shadow-sm border border-border/50 cursor-pointer active:scale-[0.98] hover:bg-muted/30 transition-all duration-150"
                  onClick={() => navigate(`/book/${book.id}`)}
                >
                  <div className="flex gap-2.5">
                    <span className={`flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-lg text-[10px] font-bold shadow-sm ${
                      index === 0 ? 'bg-warning text-white' :
                      index === 1 ? 'bg-muted text-muted-foreground' :
                      index === 2 ? 'bg-warning/70 text-white' :
                      'bg-muted text-muted-foreground'
                    }`}>
                      {index + 1}
                    </span>
                    <div className="flex-1 min-w-0">
                      <div className="flex gap-2.5">
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
                          <div className="mt-1 flex items-center gap-1.5 flex-wrap">
                            <RatingBadgeCN rating={book.rating} />
                            <MatchBadgeCN score={ms} />
                            <span className="text-[10px] text-muted-foreground">
                              {fmtReadCount(book.readCount)}
                            </span>
                          </div>
                          {tags.length > 0 && (
                            <div className="mt-1 flex items-center gap-1 flex-wrap">
                              {tags.slice(0, 2).map((tag) => (
                                <span key={tag} className="inline-flex items-center gap-0.5 rounded-md bg-primary/10 px-1 py-0.5 text-[9px] font-medium text-primary">
                                  <Tag className="h-2 w-2" />
                                  {tag}
                                </span>
                              ))}
                            </div>
                          )}
                        </div>
                      </div>
                      {book.description && (
                        <BookDescription description={book.description} />
                      )}
                    </div>
                  </div>
                </div>
              )
            })}
            {loadingMore && (
              <div className="flex items-center justify-center py-3 text-muted-foreground">
                <Loader2 className="h-4 w-4 animate-spin" />
                <span className="ml-2 text-xs">加载中...</span>
              </div>
            )}
            {!hasMore && books.length > 0 && (
              <div className="py-3 text-center text-xs text-muted-foreground/60">
                没有更多了
              </div>
            )}
            {hasMore && <div ref={sentinelRef} className="h-px" />}
          </div>
        ) : (
          <div className="flex h-40 flex-col items-center justify-center">
            <TrendingUp className="mb-2 h-8 w-8 text-muted-foreground/40" />
            <p className="text-xs text-muted-foreground">暂无数据</p>
          </div>
        )}
      </div>
    </div>
  )
}

export default function RankPage() {
  const navigate = useNavigate()
  const isMobile = useIsMobile()
  const [type, setType] = useState<RankType>('read')
  const [books, setBooks] = useState<Book[]>([])
  const [hasMore, setHasMore] = useState(true)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const fetchingRef = useRef(false)
  const hasMoreRef = useRef(true)
  const pageRef = useRef(0)

  const { ref: sentinelRef, inView } = useInView({ rootMargin: '1000px' })

  const fetcher = type === 'read' ? getReadRank
    : type === 'rating' ? getRatingRank
    : getNewBooksRank

  const loadPage = useCallback(async (pageNum: number) => {
    if (fetchingRef.current) return
    fetchingRef.current = true
    if (pageNum > 0) setLoadingMore(true)
    try {
      const res = await fetcher(pageNum + 1, PAGE_SIZE)
      const list = res.list ?? []
      setBooks((prev) => pageNum === 0 ? list : [...prev, ...list])
      const hasNext = list.length === PAGE_SIZE
      hasMoreRef.current = hasNext
      setHasMore(hasNext)
      pageRef.current = pageNum
    } catch {
      if (pageNum === 0) setBooks([])
    } finally {
      fetchingRef.current = false
      if (pageNum === 0) setLoading(false)
      else setLoadingMore(false)
    }
  }, [fetcher])

  useEffect(() => {
    setLoading(true)
    fetchingRef.current = false
    loadPage(0)
  }, [loadPage])

  useEffect(() => {
    if (inView && hasMoreRef.current && !fetchingRef.current && !loading) {
      loadPage(pageRef.current + 1)
    }
  }, [inView, books.length, loadPage, loading])

  const handleTypeChange = (key: RankType) => {
    setType(key)
  }

  const matchScores = useMatchScores(books.map(b => b.id))

  // PC端：三列独立滚动
  if (!isMobile) {
    return (
      <div className="page-enter h-full flex flex-col overflow-hidden">
        {/* 顶部标题 */}
        <div className="shrink-0 px-6 pt-4 pb-3 border-b border-border/30">
          <h1 className="text-xl font-bold">发现好书</h1>
        </div>

        {/* 三列布局 */}
        <div className="flex-1 min-h-0 grid grid-cols-3 gap-4 px-6 py-4">
          {RANK_TABS.map((tab) => (
            <RankColumn
              key={tab.key}
              type={tab.key}
              label={tab.label}
              icon={tab.icon}
              navigate={navigate}
            />
          ))}
        </div>
      </div>
    )
  }

  // 移动端：Tab 切换
  return (
    <div className="page-enter px-4 pb-20">
      {/* 顶部头部 + Tab */}
      <div className="sticky top-0 z-50 -mx-4 px-4 pt-safe-top bg-background/80 backdrop-blur-xl border-b border-border/30">
        <header className="py-4">
          <h1 className="text-xl font-bold">发现好书</h1>
        </header>
        <div className="mb-4 flex gap-2">
          {RANK_TABS.map((tab) => (
            <button
              key={tab.key}
              onClick={() => handleTypeChange(tab.key)}
              className={`flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full px-4 py-2 text-sm font-medium transition-colors ${
                type === tab.key ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground'
              }`}
            >
              {tab.icon}
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      <div className="pb-8">
        {loading ? (
          <div className="space-y-3">
            {Array.from({ length: 8 }, (_, i) => (
              <div key={i} className="rounded-2xl bg-card p-3 shadow-sm border border-border/50">
                <div className="flex gap-3">
                  <div className="h-7 w-7 flex-shrink-0 rounded-xl bg-muted animate-pulse" />
                  <div className="flex-1">
                    <div className="flex gap-3">
                      <div className="h-24 w-16 flex-shrink-0 rounded-lg bg-muted animate-pulse" />
                      <div className="flex-1 space-y-2">
                        <div className="h-4 w-3/4 rounded bg-muted animate-pulse" />
                        <div className="h-3 w-1/2 rounded bg-muted animate-pulse" />
                        <div className="h-3 w-full rounded bg-muted animate-pulse" />
                      </div>
                    </div>
                    <div className="mt-2 h-4 w-full rounded bg-muted animate-pulse" />
                    <div className="mt-1 h-4 w-4/5 rounded bg-muted animate-pulse" />
                  </div>
                </div>
              </div>
            ))}
          </div>
        ) : books.length > 0 ? (
          <div className="space-y-2.5">
            {books.map((book, index) => {
              const ms = matchScores?.[String(book.id)]
              const tags = parseFormatTags(book.formatTags)
              return (
                <div
                  key={book.id}
                  className="rounded-2xl bg-card p-3 shadow-sm border border-border/50 cursor-pointer active:scale-[0.98] transition-all duration-150"
                  onClick={() => navigate(`/book/${book.id}`)}
                >
                  <div className="flex gap-3">
                    <span className={`flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-xl text-xs font-bold shadow-sm ${
                      index === 0 ? 'bg-warning text-white' :
                      index === 1 ? 'bg-muted text-muted-foreground' :
                      index === 2 ? 'bg-warning/70 text-white' :
                      'bg-muted text-muted-foreground'
                    }`}>
                      {index + 1}
                    </span>
                    <div className="flex-1 min-w-0">
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
                          <div className="mt-1.5 flex items-center gap-2 flex-wrap">
                            <RatingBadgeCN rating={book.rating} />
                            <MatchBadgeCN score={ms} />
                            <span className="text-[11px] text-muted-foreground">
                              {fmtReadCount(book.readCount)}
                            </span>
                          </div>
                          {tags.length > 0 && (
                            <div className="mt-1.5 flex items-center gap-1.5 flex-wrap">
                              {tags.slice(0, 3).map((tag) => (
                                <span key={tag} className="inline-flex items-center gap-0.5 rounded-md bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary">
                                  <Tag className="h-2.5 w-2.5" />
                                  {tag}
                                </span>
                              ))}
                            </div>
                          )}
                        </div>
                      </div>
                      {book.description && (
                        <BookDescription description={book.description} />
                      )}
                    </div>
                  </div>
                </div>
              )
            })}
            {loadingMore && (
              <div className="flex items-center justify-center py-4 text-muted-foreground">
                <Loader2 className="h-5 w-5 animate-spin" />
                <span className="ml-2 text-xs">加载中...</span>
              </div>
            )}
            {!hasMore && books.length > 0 && (
              <div className="py-4 text-center text-xs text-muted-foreground/60">
                没有更多了
              </div>
            )}
            {hasMore && <div ref={sentinelRef} className="h-px" />}
          </div>
        ) : (
          <div className="flex h-[50vh] flex-col items-center justify-center">
            <TrendingUp className="mb-4 h-12 w-12 text-muted-foreground" />
            <p className="text-sm text-muted-foreground">还没有相关数据</p>
          </div>
        )}
      </div>
    </div>
  )
}
