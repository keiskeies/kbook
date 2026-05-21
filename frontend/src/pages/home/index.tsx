import { useEffect, useState, useMemo, useRef, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Search, BookOpen, ChevronRight, Clock, Star, Sparkles,
  TrendingUp, Zap, Flame, Award,
  Bell, MessageSquareText, Tag, BarChart3, BookCheck, BookOpenCheck,
} from 'lucide-react'
import {
  getHomeStats, getHomeRecent, getHomePersonalized,
  getHomeTopRated, getHomeNewBooks, getHomePopular, getHomeCategories,
} from '@/api/home'
import { getUnreadCount } from '@/api/notification'
import { getReadRank, getRatingRank, getNewBooksRank } from '@/api/book'
import type { RecentBookVO, RecommendedBook, SimpleBookVO, ReadingStatsVO, TagStat } from '@/api/home'
import type { Book } from '@/types/book'
import { formatRelativeTime } from '@/utils/time'
import { parseFormatTags } from '@/types/book'
import BookCover from '@/components/book/BookCover'
import { useMatchScores } from '@/hooks/useMatchScores'
import { useAuthStore } from '@/store/auth'

/** 继续阅读 3D Carousel 走马灯 */
function ContinueReadingCarousel({ books, onReadClick }: {
  books: RecentBookVO[]
  onReadClick: (bookId: number) => void
}) {
  const [activeIndex, setActiveIndex] = useState(0)
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const touchStartX = useRef(0)
  const totalRef = useRef(books.length)

  const total = books.length

  useEffect(() => {
    totalRef.current = total
  }, [total])

  const startAutoPlay = useCallback(() => {
    if (timerRef.current) clearInterval(timerRef.current)
    timerRef.current = setInterval(() => {
      setActiveIndex((prev) => (prev + 1) % totalRef.current)
    }, 3000)
  }, [])

  const stopAutoPlay = useCallback(() => {
    if (timerRef.current) {
      clearInterval(timerRef.current)
      timerRef.current = null
    }
  }, [])

  useEffect(() => {
    if (total > 1) startAutoPlay()
    return stopAutoPlay
  }, [total, startAutoPlay, stopAutoPlay])

  const goTo = useCallback((index: number) => {
    setActiveIndex(index)
    if (totalRef.current > 1) startAutoPlay()
  }, [startAutoPlay])

  if (total === 0) return null

  const handleTouchStart = (e: React.TouchEvent) => {
    touchStartX.current = e.touches[0].clientX
    stopAutoPlay()
  }

  const handleTouchEnd = (e: React.TouchEvent) => {
    const dx = e.changedTouches[0].clientX - touchStartX.current
    if (Math.abs(dx) > 40) {
      if (dx < 0) goTo((activeIndex + 1) % total)
      else goTo((activeIndex - 1 + total) % total)
    }
    startAutoPlay()
  }

  const getCardStyle = (index: number): React.CSSProperties => {
    const offset = ((index - activeIndex) % total + total) % total
    const isCenter = offset === 0
    const isLeft = offset === total - 1
    const isRight = offset === 1

    if (total === 1) {
      return {
        transform: 'translateX(0) translateZ(0) rotateY(0deg) scale(1)',
        opacity: 1,
        zIndex: 3,
        transition: 'all 0.6s cubic-bezier(0.25, 0.46, 0.45, 0.94)',
      }
    }

    if (isCenter) {
      return {
        transform: 'translateX(0) translateZ(0) rotateY(0deg) scale(1)',
        opacity: 1,
        zIndex: 3,
        transition: 'all 0.6s cubic-bezier(0.25, 0.46, 0.45, 0.94)',
      }
    }
    if (isLeft) {
      return {
        transform: 'translateX(-62%) translateZ(-80px) rotateY(25deg) scale(0.82)',
        opacity: 0.6,
        zIndex: 1,
        transition: 'all 0.6s cubic-bezier(0.25, 0.46, 0.45, 0.94)',
      }
    }
    if (isRight) {
      return {
        transform: 'translateX(62%) translateZ(-80px) rotateY(-25deg) scale(0.82)',
        opacity: 0.6,
        zIndex: 1,
        transition: 'all 0.6s cubic-bezier(0.25, 0.46, 0.45, 0.94)',
      }
    }
    return {
      transform: 'translateZ(-200px) scale(0.5)',
      opacity: 0,
      zIndex: 0,
      transition: 'all 0.6s cubic-bezier(0.25, 0.46, 0.45, 0.94)',
    }
  }

  const gradients = [
    'from-primary to-primary/95',
    'from-violet-500 to-purple-600',
    'from-sky-500 to-blue-600',
  ]

  return (
    <div
      className="relative overflow-hidden"
      onTouchStart={handleTouchStart}
      onTouchEnd={handleTouchEnd}
    >
      {/* 3D 透视容器 */}
      <div className="relative h-[120px] w-full overflow-hidden" style={{ perspective: '1000px' }}>
        {books.map((book, index) => {
          const offset = ((index - activeIndex) % total + total) % total
          const isCenter = offset === 0
          const gradient = gradients[index % gradients.length]

          return (
            <div
              key={book.bookId}
              className="absolute inset-0"
              style={getCardStyle(index)}
              onClick={() => {
                if (isCenter) {
                  onReadClick(book.bookId)
                } else {
                  goTo(index)
                }
              }}
            >
              <div
                className={`relative h-full overflow-hidden rounded-2xl bg-gradient-to-r ${gradient} p-4 shadow-lg cursor-pointer active:scale-[0.98] transition-transform`}
                style={{
                  boxShadow: isCenter
                    ? '0 8px 30px rgba(99, 102, 241, 0.25)'
                    : '0 4px 15px rgba(0,0,0,0.1)',
                }}
              >
                <div className="absolute -right-6 -top-6 h-24 w-24 rounded-full bg-white/10" />
                <div className="absolute -right-2 top-8 h-16 w-16 rounded-full bg-white/5" />

                <div className="relative flex items-center gap-3.5 h-full">
                  <BookCover
                    coverUrl={book.coverUrl}
                    title={book.title}
                    author={book.author}
                    size="sm"
                    className="flex-shrink-0 h-14 w-14 rounded-xl shadow-md"
                  />
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-bold text-white/80">继续阅读</p>
                    <p className="mt-0.5 truncate text-base font-bold text-white">{book.title}</p>
                    <div className="mt-2 flex items-center gap-2.5">
                      <div className="h-1.5 flex-1 rounded-full bg-white/20">
                        <div className="h-full rounded-full bg-white/90 transition-all" style={{ width: `${Math.round(book.progress * 100)}%` }} />
                      </div>
                      <span className="text-xs font-bold text-white/90">{Math.round(book.progress * 100)}%</span>
                    </div>
                    <div className="mt-1 flex items-center gap-2 text-[10px] text-white/60">
                      <Clock className="h-2.5 w-2.5" />
                      <span>{formatRelativeTime(book.lastReadAt)}</span>
                      <span>·</span>
                      <span>{book.author || '未知作者'}</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          )
        })}
      </div>

      {/* 指示器 */}
    </div>
  )
}
/** 评分徽章 — 5分制分等级配色 */
function RatingBadge({ rating }: { rating: number | undefined | null }) {
  if (rating == null || rating < 0) return null
  const r = Number(rating.toFixed(1))

  let colorClass = ''
  let bgClass = ''
  if (r >= 5.0) {
    colorClass = 'text-red-600 dark:text-red-400'
    bgClass = 'bg-red-500/15'
  } else if (r >= 4.5) {
    colorClass = 'text-orange-600 dark:text-orange-400'
    bgClass = 'bg-orange-500/15'
  } else if (r >= 4.0) {
    colorClass = 'text-amber-600 dark:text-amber-400'
    bgClass = 'bg-amber-500/10'
  } else if (r >= 3.0) {
    colorClass = 'text-emerald-600 dark:text-emerald-400'
    bgClass = 'bg-emerald-500/10'
  } else if (r >= 2.5) {
    colorClass = 'text-teal-600 dark:text-teal-400'
    bgClass = 'bg-teal-500/10'
  } else {
    colorClass = 'text-slate-400 dark:text-slate-500'
    bgClass = 'bg-slate-400/10'
  }

  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${colorClass} ${bgClass}`}>
      <Star className="h-2.5 w-2.5" />
      {r}
    </span>
  )
}

function MatchBadge({ score }: { score: number | undefined | null }) {
  const pct = Math.round(Math.max(0, score ?? 0) * 100)

  let colorClass = ''
  let bgClass = ''
  if (pct >= 100) {
    colorClass = 'text-red-600 dark:text-red-400'
    bgClass = 'bg-red-500/15'
  } else if (pct >= 80) {
    colorClass = 'text-orange-600 dark:text-orange-400'
    bgClass = 'bg-orange-500/15'
  } else if (pct >= 60) {
    colorClass = 'text-amber-600 dark:text-amber-400'
    bgClass = 'bg-amber-500/10'
  } else if (pct >= 50) {
    colorClass = 'text-emerald-600 dark:text-emerald-400'
    bgClass = 'bg-emerald-500/10'
  } else if (pct >= 40) {
    colorClass = 'text-teal-600 dark:text-teal-400'
    bgClass = 'bg-teal-500/10'
  } else {
    colorClass = 'text-slate-400 dark:text-slate-500'
    bgClass = 'bg-slate-400/10'
  }

  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${colorClass} ${bgClass}`}>
      <Sparkles className="h-2.5 w-2.5" />
      {pct}%
    </span>
  )
}

/** 评分徽章（带中文标签） — 5分制分等级配色 */
function RatingBadgeCN({ rating }: { rating: number | undefined | null }) {
  if (rating == null || rating < 0) return null
  const r = Number(rating.toFixed(1))

  let colorClass = ''
  let bgClass = ''
  if (r >= 5.0) {
    colorClass = 'text-red-600 dark:text-red-400'
    bgClass = 'bg-red-500/15'
  } else if (r >= 4.5) {
    colorClass = 'text-orange-600 dark:text-orange-400'
    bgClass = 'bg-orange-500/15'
  } else if (r >= 4.0) {
    colorClass = 'text-amber-600 dark:text-amber-400'
    bgClass = 'bg-amber-500/10'
  } else if (r >= 3.0) {
    colorClass = 'text-emerald-600 dark:text-emerald-400'
    bgClass = 'bg-emerald-500/10'
  } else if (r >= 2.5) {
    colorClass = 'text-teal-600 dark:text-teal-400'
    bgClass = 'bg-teal-500/10'
  } else {
    colorClass = 'text-slate-400 dark:text-slate-500'
    bgClass = 'bg-slate-400/10'
  }

  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${colorClass} ${bgClass}`}>
      <Star className="h-2.5 w-2.5" />
      评分：{r}
    </span>
  )
}

/** 匹配度徽章（带中文标签） — 根据匹配度分等级配色 */
function MatchBadgeCN({ score }: { score: number | undefined | null }) {
  const pct = Math.round(Math.max(0, score ?? 0) * 100)

  let colorClass = ''
  let bgClass = ''
  if (pct >= 100) {
    colorClass = 'text-red-600 dark:text-red-400'
    bgClass = 'bg-red-500/15'
  } else if (pct >= 80) {
    colorClass = 'text-orange-600 dark:text-orange-400'
    bgClass = 'bg-orange-500/15'
  } else if (pct >= 60) {
    colorClass = 'text-amber-600 dark:text-amber-400'
    bgClass = 'bg-amber-500/10'
  } else if (pct >= 50) {
    colorClass = 'text-emerald-600 dark:text-emerald-400'
    bgClass = 'bg-emerald-500/10'
  } else if (pct >= 40) {
    colorClass = 'text-teal-600 dark:text-teal-400'
    bgClass = 'bg-teal-500/10'
  } else {
    colorClass = 'text-slate-400 dark:text-slate-500'
    bgClass = 'bg-slate-400/10'
  }

  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${colorClass} ${bgClass}`}>
      <Sparkles className="h-2.5 w-2.5" />
      匹配度：{pct}%
    </span>
  )
}
/** 格式化阅读量 */
function fmtReadCount(n: number): string {
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万次阅读`
  return `${n}次阅读`
}

/** 竖向图书列表（参考榜单，无序号） */
function VerticalBookList({ books, onBookClick, matchScores }: {
  books: (SimpleBookVO | RecommendedBook)[]
  onBookClick: (id: number) => void
  matchScores?: Record<string, number>
}) {
  return (
    <div className="space-y-2.5">
      {books.map((book) => {
        const ms = matchScores?.[String(book.id)] ?? (book as RecommendedBook).matchScore
        const tags = parseFormatTags((book as any).formatTags)
        return (
          <div
            key={book.id}
            className="rounded-2xl bg-card p-3 shadow-sm border border-border/50 cursor-pointer active:scale-[0.98] transition-all duration-150"
            onClick={() => onBookClick(book.id)}
          >
            <div className="flex gap-3">
              <div className="flex-1 min-w-0">
                <div className="flex gap-3">
                  <BookCover
                    coverUrl={book.coverUrl}
                    title={book.title}
                    author={book.author}
                    format={book.format}
                    size="md"
                    className="flex-shrink-0 h-24 w-16"
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
              </div>
            </div>
          </div>
        )
      })}
    </div>
  )
}

/** 竖向图书列表（参考榜单，保留序号） */
function RankedBookList({ books, onBookClick, matchScores }: {
  books: (SimpleBookVO | RecommendedBook)[]
  onBookClick: (id: number) => void
  matchScores?: Record<string, number>
}) {
  return (
    <div className="space-y-2.5">
      {books.map((book, index) => {
        const ms = matchScores?.[String(book.id)] ?? (book as RecommendedBook).matchScore
        const tags = parseFormatTags((book as any).formatTags)
        return (
          <div
            key={book.id}
            className="rounded-2xl bg-card p-3 shadow-sm border border-border/50 cursor-pointer active:scale-[0.98] transition-all duration-150 min-h-[112px]"
            onClick={() => onBookClick(book.id)}
          >
            <div className="flex gap-3">
              <span className={`flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-lg text-[10px] font-bold ${
                index === 0 ? 'bg-amber-400 text-white' :
                index === 1 ? 'bg-zinc-400 text-white' :
                index === 2 ? 'bg-orange-400 text-white' :
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
                      <RatingBadge rating={book.rating} />
                      <MatchBadge score={ms} />
                      <span className="text-[11px] text-muted-foreground">
                        {fmtReadCount(book.readCount)}
                      </span>
                    </div>
                  </div>
                </div>
                {/* 标签行：放在封面+信息下方，横向滑动 */}
                <div className="mt-2 flex items-center gap-1.5 overflow-x-auto scrollbar-hide" style={{ scrollbarWidth: 'none' }}>
                  {tags.length > 0 ? (
                    tags.map((tag) => (
                      <span key={tag} className="inline-flex flex-shrink-0 items-center gap-0.5 rounded-md bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary">
                        <Tag className="h-2.5 w-2.5" />
                        {tag}
                      </span>
                    ))
                  ) : (
                    <>
                      <div className="h-4 w-10 flex-shrink-0 rounded-md bg-muted animate-pulse" />
                      <div className="h-4 w-8 flex-shrink-0 rounded-md bg-muted/50 animate-pulse" />
                    </>
                  )}
                </div>
              </div>
            </div>
          </div>
        )
      })}
    </div>
  )
}

/** 双列封面背景卡片（高分佳作） */
function DualColumnBookCard({ books, onBookClick, matchScores }: {
  books: (SimpleBookVO | RecommendedBook)[]
  onBookClick: (id: number) => void
  matchScores?: Record<string, number>
}) {
  return (
    <div className="grid grid-cols-2 gap-3">
      {books.map((book) => {
        const ms = matchScores?.[String(book.id)] ?? (book as RecommendedBook).matchScore
        return (
          <div
            key={book.id}
            className="relative rounded-2xl overflow-hidden cursor-pointer active:scale-[0.98] transition-all duration-150 aspect-[3/4] shadow-sm border border-border/50"
            onClick={() => onBookClick(book.id)}
          >
            {/* 封面背景 */}
            <div
              className="absolute inset-0 bg-cover bg-center"
              style={{ backgroundImage: `url(${book.coverUrl || ''})` }}
            />
            {/* 渐变遮罩 */}
            <div className="absolute inset-0 bg-gradient-to-t from-black/90 via-black/40 to-transparent" />

            {/* 底部信息 */}
            <div className="absolute bottom-0 left-0 right-0 p-3">
              <p className="truncate text-sm font-semibold text-white">{book.title}</p>
              <p className="mt-0.5 truncate text-xs text-white/70">
                {book.author || '未知作者'}
              </p>
              <div className="mt-1.5 flex items-center gap-2">
                <RatingBadge rating={book.rating} />
                <MatchBadge score={ms} />
              </div>
              {(book as any).description && (
                <p className="mt-1.5 text-[10px] text-white/60 leading-relaxed line-clamp-2">
                  {(book as any).description}
                </p>
              )}
            </div>
          </div>
        )
      })}
    </div>
  )
}

/** 竖向列表骨架屏 */
function VerticalListSkeleton() {
  return (
    <div className="space-y-2.5">
      {Array.from({ length: 3 }, (_, i) => (
        <div key={i} className="rounded-2xl bg-card p-3 shadow-sm border border-border/50">
          <div className="flex gap-3">
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
  )
}

/** 新书速递双行横向滚动 */
function NewBooksDualRow({ books, onBookClick, matchScores }: {
  books: (SimpleBookVO | RecommendedBook)[]
  onBookClick: (id: number) => void
  matchScores?: Record<string, number>
}) {
  const row1 = books.slice(0, 6)
  const row2 = books.slice(6, 12)

  const renderRow = (rowBooks: (SimpleBookVO | RecommendedBook)[]) => (
    <div className="flex gap-3 overflow-x-auto pb-2 scrollbar-hide -mx-4 px-4" style={{ scrollbarWidth: 'none' }}>
      {rowBooks.map((book) => {
        const ms = matchScores?.[String(book.id)] ?? (book as RecommendedBook).matchScore
        return (
          <div
            key={book.id}
            className="flex w-[100px] flex-shrink-0 flex-col cursor-pointer active:scale-[0.96] transition-transform duration-150"
            onClick={() => onBookClick(book.id)}
          >
            <BookCover coverUrl={book.coverUrl} title={book.title} author={book.author} format={book.format} />
            <p className="mt-1.5 w-full truncate text-xs font-semibold">{book.title}</p>
            <div className="flex items-center justify-between px-2">
              <RatingBadge rating={book.rating} />
              <MatchBadge score={ms} />
            </div>
            {book.readCount > 0 && (
              <div className="flex items-center gap-0.5 mt-0.5 justify-end px-2">
                <BookOpen className="h-2.5 w-2.5 text-muted-foreground/60" />
                <span className="text-[10px] text-muted-foreground">{book.readCount}次阅读</span>
              </div>
            )}
          </div>
        )
      })}
    </div>
  )

  return (
    <div className="space-y-2">
      {row1.length > 0 && renderRow(row1)}
      {row2.length > 0 && renderRow(row2)}
    </div>
  )
}

/** 新书速递双行骨架屏 */
function NewBooksDualRowSkeleton() {
  return (
    <div className="space-y-2">
      {[0, 1].map((row) => (
        <div key={row} className="flex gap-3 overflow-x-auto pb-2 -mx-4 px-4" style={{ scrollbarWidth: 'none' }}>
          {Array.from({ length: 6 }, (_, i) => (
            <div key={i} className="w-[100px] flex-shrink-0 space-y-2">
              <div className="aspect-[3/4] animate-pulse rounded-xl bg-muted" />
              <div className="h-3 w-4/5 animate-pulse rounded bg-muted" />
            </div>
          ))}
        </div>
      ))}
    </div>
  )
}
/** 单个榜单 Tab 内容（用于横向滑动） */
function RankTabList({ tabKey, icon, label, onBookClick }: {
  tabKey: 'read' | 'rating' | 'new'
  icon: React.ReactNode
  label: string
  onBookClick: (id: number) => void
}) {
  const [books, setBooks] = useState<Book[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    setLoading(true)
    const fetcher = tabKey === 'read' ? getReadRank
      : tabKey === 'rating' ? getRatingRank
      : getNewBooksRank
    fetcher(1, 6)
      .then((res) => setBooks((res as any)?.list || []))
      .catch(() => setBooks([]))
      .finally(() => setLoading(false))
  }, [tabKey])

  const matchScores = useMatchScores(books.map(b => b.id))

  return (
    <div className="space-y-3">
      {/* 标题栏 */}
      <div className="flex items-center gap-3 px-1">
        <span className="text-primary">{icon}</span>
        <h3 className="text-sm font-bold">{label}</h3>
        <div className="flex-1 h-px bg-gradient-to-r from-border to-transparent" />
      </div>

      {/* 内容 */}
      {loading ? (
        <div className="space-y-2.5">
          {Array.from({ length: 3 }, (_, i) => (
            <div key={i} className="rounded-2xl bg-card p-3 shadow-sm border border-border/50">
              <div className="flex gap-3">
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
        <RankedBookList
          books={books}
          onBookClick={onBookClick}
          matchScores={matchScores}
        />
      ) : (
        <div className="flex h-32 items-center justify-center text-muted-foreground text-sm">
          暂无数据
        </div>
      )}
    </div>
  )
}

export default function HomePage() {
  const navigate = useNavigate()
  const [unreadCount, setUnreadCount] = useState(0)
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const hasFetchedRef = useRef(false)

  // 各模块独立数据与 loading 状态
  const [stats, setStats] = useState<ReadingStatsVO | null>(null)
  const [statsLoading, setStatsLoading] = useState(true)
  const [recentBooks, setRecentBooks] = useState<RecentBookVO[]>([])
  const [recentLoading, setRecentLoading] = useState(true)
  const [personalizedBooks, setPersonalizedBooks] = useState<RecommendedBook[]>([])
  const [personalizedLoading, setPersonalizedLoading] = useState(true)
  const [topRatedBooks, setTopRatedBooks] = useState<SimpleBookVO[]>([])
  const [topRatedLoading, setTopRatedLoading] = useState(true)
  const [newBooks, setNewBooks] = useState<SimpleBookVO[]>([])
  const [newBooksLoading, setNewBooksLoading] = useState(true)
  const [popularBooks, setPopularBooks] = useState<SimpleBookVO[]>([])
  const [, setPopularLoading] = useState(true)
  const [categories, setCategories] = useState<TagStat[]>([])
  const [categoriesLoading, setCategoriesLoading] = useState(true)

  useEffect(() => {
    if (!isAuthenticated || hasFetchedRef.current) {
      setStatsLoading(false)
      setRecentLoading(false)
      setPersonalizedLoading(false)
      setTopRatedLoading(false)
      setNewBooksLoading(false)
      setPopularLoading(false)
      setCategoriesLoading(false)
      return
    }
    hasFetchedRef.current = true

    // 并行请求各模块独立接口
    getHomeStats().then((res) => setStats((res as any)?.data || (res as any))).catch(() => {}).finally(() => setStatsLoading(false))
    getHomeRecent().then((res) => setRecentBooks((res as any)?.data || (res as any) || [])).catch(() => {}).finally(() => setRecentLoading(false))
    getHomePersonalized().then((res) => setPersonalizedBooks((res as any)?.data || (res as any) || [])).catch(() => {}).finally(() => setPersonalizedLoading(false))
    getHomeTopRated().then((res) => setTopRatedBooks((res as any)?.data || (res as any) || [])).catch(() => {}).finally(() => setTopRatedLoading(false))
    getHomeNewBooks().then((res) => setNewBooks((res as any)?.data || (res as any) || [])).catch(() => {}).finally(() => setNewBooksLoading(false))
    getHomePopular().then((res) => setPopularBooks((res as any)?.data || (res as any) || [])).catch(() => {}).finally(() => setPopularLoading(false))
    getHomeCategories().then((res) => setCategories((res as any)?.data || (res as any) || [])).catch(() => {}).finally(() => setCategoriesLoading(false))

    getUnreadCount().then(res => setUnreadCount((res as any)?.data || (res as any) || 0)).catch(() => {})
  }, [isAuthenticated])

  // 收集所有非个性化书籍的ID，批量获取匹配分
  const matchBookIds = useMemo(() => {
    const ids: number[] = []
    topRatedBooks.forEach(b => ids.push(b.id))
    newBooks.forEach(b => ids.push(b.id))
    popularBooks.forEach(b => ids.push(b.id))
    return ids
  }, [topRatedBooks, newBooks, popularBooks])

  const matchScores = useMatchScores(matchBookIds)

  const goToBook = (id: number) => navigate(`/book/${id}`)
  const goToBookDetail = (id: number) => navigate(`/book/${id}`)

  return (
    <div className="page-enter pb-2">
      {/* 顶部品牌栏 + 搜索框 - fixed 固定在顶部 */}
      <div className="fixed inset-x-0 top-0 z-50 bg-gradient-to-b from-background/95 via-background/80 to-background/60 pt-safe-top pb-2 backdrop-blur-xl">
        <header className="flex items-center justify-between py-3 px-4">
          <div className="flex items-center gap-2">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary">
              <BookOpen className="h-4.5 w-4.5 text-primary-foreground" strokeWidth={2.5} />
            </div>
            <h1 className="text-xl font-bold bg-gradient-to-r from-primary to-primary/70 bg-clip-text text-transparent">KBook</h1>
          </div>
          <div className="flex items-center gap-2">
            {/* 高分书评入口 */}
            <button
              onClick={() => navigate('/reviews')}
              className="flex h-8 w-8 items-center justify-center rounded-lg hover:bg-muted transition-colors"
            >
              <MessageSquareText className="h-5 w-5 text-muted-foreground" />
            </button>
            {/* 通知 */}
            <button
              onClick={() => navigate('/notifications')}
              className="relative flex h-8 w-8 items-center justify-center rounded-lg hover:bg-muted transition-colors"
            >
              <Bell className="h-5 w-5 text-muted-foreground" />
              {unreadCount > 0 && (
                <span className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-bold text-white">
                  {unreadCount > 99 ? '99+' : unreadCount}
                </span>
              )}
            </button>
          </div>
        </header>

        {/* 搜索框 */}
        <div className="mb-4 mt-1 px-4" onClick={() => navigate('/search')}>
          <div className="flex items-center gap-2.5 rounded-2xl bg-card/80 backdrop-blur-sm px-4 py-3 shadow-sm border border-border/30 text-muted-foreground">
            <Search className="h-4 w-4" />
            <span className="text-sm">搜索书籍、作者...</span>
          </div>
        </div>
      </div>

      {/* 内容区域 - 留出顶部固定头部的高度 */}
      <div className="pt-[140px] px-4 space-y-6">
        {/* 1. 阅读统计卡片 — 横向数据条 */}
        {statsLoading ? (
          <div className="rounded-2xl bg-card border border-border/50 shadow-sm p-4 space-y-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <div className="h-7 w-7 animate-pulse rounded-lg bg-muted" />
                <div className="h-4 w-16 animate-pulse rounded bg-muted" />
              </div>
              <div className="h-3 w-12 animate-pulse rounded bg-muted" />
            </div>
            <div className="space-y-3.5">
              {[0, 1, 2].map((i) => (
                <div key={i} className="space-y-1.5">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <div className="h-3.5 w-3.5 animate-pulse rounded bg-muted" />
                      <div className="h-3 w-8 animate-pulse rounded bg-muted" />
                    </div>
                    <div className="h-4 w-8 animate-pulse rounded bg-muted" />
                  </div>
                  <div className="h-2 w-full animate-pulse rounded-full bg-muted" />
                </div>
              ))}
            </div>
            <div className="pt-2 border-t border-border/50">
              <div className="flex items-center justify-between">
                <div className="h-2.5 w-8 animate-pulse rounded bg-muted" />
                <div className="flex items-center gap-2">
                  <div className="h-1.5 w-24 animate-pulse rounded-full bg-muted" />
                  <div className="h-2.5 w-10 animate-pulse rounded bg-muted" />
                </div>
              </div>
            </div>
          </div>
        ) : stats && (stats.totalBooks > 0 || stats.readingBooks > 0) ? (
          <section>
            <div className="rounded-2xl bg-card border border-border/50 shadow-sm p-4 space-y-4">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-gradient-to-br from-primary/20 to-primary/5">
                    <BarChart3 className="h-4 w-4 text-primary" />
                  </div>
                  <span className="text-sm font-bold">阅读数据</span>
                </div>
                <span className="text-[10px] text-muted-foreground font-medium">
                  完成率 {stats.totalBooks > 0 ? Math.round((stats.completedBooks / stats.totalBooks) * 100) : 0}%
                </span>
              </div>
              <div className="space-y-3.5">
                {/* 已读 */}
                <button onClick={() => navigate('/profile/history')} className="w-full text-left active:opacity-70 transition-opacity">
                  <div className="flex items-center justify-between mb-1.5">
                    <div className="flex items-center gap-2">
                      <BookOpenCheck className="h-3.5 w-3.5 text-sky-500" />
                      <span className="text-xs font-medium text-muted-foreground">已读</span>
                    </div>
                    <span className="text-sm font-bold text-foreground">{stats.totalBooks}<span className="text-[10px] text-muted-foreground font-normal ml-0.5">本</span></span>
                  </div>
                  <div className="h-2 w-full rounded-full bg-muted overflow-hidden">
                    <div className="h-full rounded-full bg-gradient-to-r from-sky-400 to-sky-500 transition-all duration-700" style={{ width: `${stats.totalBooks > 0 ? 100 : 0}%` }} />
                  </div>
                </button>
                {/* 在读 */}
                <button onClick={() => navigate('/bookshelf')} className="w-full text-left active:opacity-70 transition-opacity">
                  <div className="flex items-center justify-between mb-1.5">
                    <div className="flex items-center gap-2">
                      <BookOpen className="h-3.5 w-3.5 text-amber-500" />
                      <span className="text-xs font-medium text-muted-foreground">在读</span>
                    </div>
                    <span className="text-sm font-bold text-foreground">{stats.readingBooks}<span className="text-[10px] text-muted-foreground font-normal ml-0.5">本</span></span>
                  </div>
                  <div className="h-2 w-full rounded-full bg-muted overflow-hidden">
                    <div className="h-full rounded-full bg-gradient-to-r from-amber-400 to-amber-500 transition-all duration-700" style={{ width: `${stats.totalBooks > 0 ? (stats.readingBooks / stats.totalBooks) * 100 : 0}%` }} />
                  </div>
                </button>
                {/* 读完 */}
                <button onClick={() => navigate('/profile/history')} className="w-full text-left active:opacity-70 transition-opacity">
                  <div className="flex items-center justify-between mb-1.5">
                    <div className="flex items-center gap-2">
                      <BookCheck className="h-3.5 w-3.5 text-emerald-500" />
                      <span className="text-xs font-medium text-muted-foreground">读完</span>
                    </div>
                    <span className="text-sm font-bold text-foreground">{stats.completedBooks}<span className="text-[10px] text-muted-foreground font-normal ml-0.5">本</span></span>
                  </div>
                  <div className="h-2 w-full rounded-full bg-muted overflow-hidden">
                    <div className="h-full rounded-full bg-gradient-to-r from-emerald-400 to-emerald-500 transition-all duration-700" style={{ width: `${stats.totalBooks > 0 ? (stats.completedBooks / stats.totalBooks) * 100 : 0}%` }} />
                  </div>
                </button>
              </div>
              <div className="pt-2 border-t border-border/50">
                <div className="flex items-center justify-between">
                  <span className="text-[10px] text-muted-foreground">总进度</span>
                  <div className="flex items-center gap-2">
                    <div className="h-1.5 w-24 rounded-full bg-muted overflow-hidden">
                      <div className="h-full rounded-full bg-gradient-to-r from-primary to-primary/70 transition-all duration-700" style={{ width: `${stats.totalBooks > 0 ? (stats.completedBooks / stats.totalBooks) * 100 : 0}%` }} />
                    </div>
                    <span className="text-[10px] font-bold text-primary">{stats.completedBooks}/{stats.totalBooks}</span>
                  </div>
                </div>
              </div>
            </div>
          </section>
        ) : null}

        {/* 2. 继续阅读 */}
        {recentLoading ? (
          <div className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
            <div className="mb-3 flex items-center gap-2">
              <div className="h-7 w-7 animate-pulse rounded-lg bg-muted" />
              <div className="h-4 w-16 animate-pulse rounded bg-muted" />
            </div>
            <div className="h-[120px] animate-pulse rounded-2xl bg-muted" />
          </div>
        ) : recentBooks.length > 0 ? (
          <section className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="flex items-center gap-2 text-sm font-bold">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-gradient-to-br from-primary/20 to-primary/5">
                  <Clock className="h-4 w-4 text-primary" />
                </div>
                继续阅读
              </h2>
              <button onClick={() => navigate('/profile/history')} className="flex items-center text-xs text-primary font-medium">
                查看更多 <ChevronRight className="h-3 w-3" />
              </button>
            </div>
            <ContinueReadingCarousel books={recentBooks.slice(0, 3)} onReadClick={goToBookDetail} />
          </section>
        ) : null}

        {/* 3. 猜你喜欢 */}
        {personalizedLoading ? (
          <div className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
            <div className="mb-3 flex items-center gap-2">
              <div className="h-7 w-7 animate-pulse rounded-lg bg-muted" />
              <div className="h-4 w-16 animate-pulse rounded bg-muted" />
            </div>
            <VerticalListSkeleton />
          </div>
        ) : personalizedBooks.length > 0 ? (
          <section className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="flex items-center gap-2 text-sm font-bold">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-gradient-to-br from-amber-500/20 to-amber-500/5">
                  <Sparkles className="h-4 w-4 text-amber-500" />
                </div>
                猜你喜欢
              </h2>
              <button onClick={() => navigate('/recommend')} className="flex items-center text-xs text-primary font-medium">
                查看更多 <ChevronRight className="h-3 w-3" />
              </button>
            </div>
            <VerticalBookList books={personalizedBooks} onBookClick={goToBook} matchScores={matchScores} />
          </section>
        ) : null}

        {/* 4. 高分佳作 */}
        {topRatedLoading ? (
          <div className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
            <div className="mb-3 flex items-center gap-2">
              <div className="h-7 w-7 animate-pulse rounded-lg bg-muted" />
              <div className="h-4 w-16 animate-pulse rounded bg-muted" />
            </div>
            <div className="grid grid-cols-2 gap-3">
              {[0, 1, 2, 3].map((i) => (
                <div key={i} className="aspect-[3/4] animate-pulse rounded-2xl bg-muted" />
              ))}
            </div>
          </div>
        ) : topRatedBooks.length > 0 ? (
          <section className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="flex items-center gap-2 text-sm font-bold">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-gradient-to-br from-amber-400/20 to-amber-400/5">
                  <Star className="h-4 w-4 text-amber-400" />
                </div>
                高分佳作
              </h2>
              <button onClick={() => navigate('/rank?type=rating')} className="flex items-center text-xs text-primary font-medium">
                查看更多 <ChevronRight className="h-3 w-3" />
              </button>
            </div>
            <DualColumnBookCard books={topRatedBooks} onBookClick={goToBook} matchScores={matchScores} />
          </section>
        ) : null}

        {/* 5. 新书速递 */}
        {newBooksLoading ? (
          <div className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
            <div className="mb-3 flex items-center gap-2">
              <div className="h-7 w-7 animate-pulse rounded-lg bg-muted" />
              <div className="h-4 w-16 animate-pulse rounded bg-muted" />
            </div>
            <NewBooksDualRowSkeleton />
          </div>
        ) : newBooks.length > 0 ? (
          <section className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="flex items-center gap-2 text-sm font-bold">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-gradient-to-br from-sky-500/20 to-sky-500/5">
                  <Zap className="h-4 w-4 text-sky-500" />
                </div>
                新书速递
              </h2>
              <button onClick={() => navigate('/rank?type=new')} className="flex items-center text-xs text-primary font-medium">
                查看更多 <ChevronRight className="h-3 w-3" />
              </button>
            </div>
            <NewBooksDualRow books={newBooks} onBookClick={goToBook} matchScores={matchScores} />
          </section>
        ) : null}

        {/* 6. 热门榜单 — 横向滑动 Tabs */}
        <section className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="flex items-center gap-2 text-sm font-bold">
              <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-gradient-to-br from-rose-500/20 to-rose-500/5">
                <TrendingUp className="h-4 w-4 text-rose-500" />
              </div>
              热门榜单
            </h2>
            <button onClick={() => navigate('/rank?type=read')} className="flex items-center text-xs text-primary font-medium">
              查看更多 <ChevronRight className="h-3 w-3" />
            </button>
          </div>
          <div className="-mx-4 px-4">
            <div className="overflow-x-auto scrollbar-hide" style={{ scrollbarWidth: 'none', scrollSnapType: 'x mandatory' }}>
              <div className="flex gap-4" style={{ scrollSnapType: 'x mandatory' }}>
                <div className="w-[85%] flex-shrink-0 snap-center" style={{ scrollSnapAlign: 'center' }}>
                  <RankTabList tabKey="read" icon={<Flame className="h-4 w-4" />} label="热门阅读" onBookClick={goToBook} />
                </div>
                <div className="w-[85%] flex-shrink-0 snap-center" style={{ scrollSnapAlign: 'center' }}>
                  <RankTabList tabKey="rating" icon={<Award className="h-4 w-4" />} label="高分推荐" onBookClick={goToBook} />
                </div>
                <div className="w-[85%] flex-shrink-0 snap-center" style={{ scrollSnapAlign: 'center' }}>
                  <RankTabList tabKey="new" icon={<Clock className="h-4 w-4" />} label="新书速递" onBookClick={goToBook} />
                </div>
              </div>
            </div>
          </div>
        </section>

        {/* 7. 热门标签 */}
        {categoriesLoading ? (
          <div className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
            <div className="mb-3 flex items-center gap-2">
              <div className="h-7 w-7 animate-pulse rounded-lg bg-muted" />
              <div className="h-4 w-16 animate-pulse rounded bg-muted" />
            </div>
            <div className="flex flex-wrap gap-2">
              {Array.from({ length: 8 }, (_, i) => (
                <div key={i} className="h-9 animate-pulse rounded-xl bg-muted" style={{ width: `${60 + (i % 3) * 20}px` }} />
              ))}
            </div>
          </div>
        ) : categories.length > 0 ? (
          <section className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="flex items-center gap-2 text-sm font-bold">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-gradient-to-br from-violet-500/20 to-violet-500/5">
                  <Tag className="h-4 w-4 text-violet-500" />
                </div>
                热门标签
              </h2>
              <button onClick={() => navigate('/search')} className="flex items-center text-xs text-primary font-medium">
                搜索图书 <ChevronRight className="h-3 w-3" />
              </button>
            </div>
            <div className="flex flex-wrap gap-2">
              {categories.map((cat) => (
                <button
                  key={cat.name}
                  onClick={() => navigate(`/search?tag=${encodeURIComponent(cat.name)}`)}
                  className="flex items-center gap-1.5 rounded-xl bg-muted/50 px-3 py-2 active:scale-[0.96] transition-all duration-150"
                >
                  <Tag className="h-3.5 w-3.5 text-primary" />
                  <span className="text-xs font-medium">{cat.name}</span>
                  <span className="text-[10px] text-muted-foreground">{cat.count}本</span>
                </button>
              ))}
            </div>
          </section>
        ) : null}

        {/* 无数据 */}
        {!statsLoading && !recentLoading && !personalizedLoading && !topRatedLoading &&
          recentBooks.length === 0 && personalizedBooks.length === 0 && topRatedBooks.length === 0 && (
          <div className="flex h-60 flex-col items-center justify-center text-muted-foreground">
            <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-muted">
              <BookOpen className="h-8 w-8 text-muted-foreground/50" />
            </div>
            <p className="mt-4 text-sm">暂无内容，去书架或榜单看看吧</p>
          </div>
        )}
      </div>
    </div>
  )
}
