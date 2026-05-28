import { useEffect, useState, useMemo, useRef, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Search, BookOpen, ChevronRight, Clock, Star, Sparkles,
  Bell, MessageSquareText, Tag, BookCheck, BookOpenCheck,
  Target, Frown,
} from 'lucide-react'
import {
  getHomeStats, getHomeRecent, getHomePersonalized,
  getHomeCategories,
} from '@/api/home'
import type { RecentBookVO, RecommendedBook, ReadingStatsVO, TagStat, SimpleBookVO } from '@/api/home'
import { formatRelativeTime } from '@/utils/time'
import { parseFormatTags } from '@/types/book'
import BookCover from '@/components/book/BookCover'
import MoodQuickSwitch from '@/components/home/MoodQuickSwitch'
import { useMatchScores } from '@/hooks/useMatchScores'
import { useAuthStore } from '@/store/auth'

/** 固定在顶部的 Logo 区域 */
function Header() {
  const navigate = useNavigate()
  return (
    <header className="sticky top-0 z-50 -mx-4 px-4 pt-safe-top pb-2 bg-background/80 backdrop-blur-xl border-b border-border/30">
      <div className="flex items-center justify-between py-3">
        <div className="flex items-center gap-2">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary">
            <BookOpen className="h-4.5 w-4.5 text-primary-foreground" strokeWidth={2.5} />
          </div>
          <h1 className="text-xl font-bold bg-gradient-to-r from-primary to-primary/70 bg-clip-text text-transparent">KBook</h1>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={() => navigate('/reviews')} className="flex h-8 w-8 items-center justify-center rounded-lg hover:bg-muted transition-colors">
            <MessageSquareText className="h-5 w-5 text-muted-foreground" />
          </button>
          <button onClick={() => navigate('/notifications')} className="relative flex h-8 w-8 items-center justify-center rounded-lg hover:bg-muted transition-colors">
            <Bell className="h-5 w-5 text-muted-foreground" />
          </button>
        </div>
      </div>
    </header>
  )
}

/** Hero 区域 */
function HeroSection({ onSearchClick }: {
  onSearchClick: () => void
}) {
  const nickname = useAuthStore((s) => s.userInfo?.nickname)
  const hour = new Date().getHours()
  const greeting = hour < 6 ? '夜深了' : hour < 12 ? '早上好' : hour < 18 ? '下午好' : '晚上好'

  return (
    <div className="mb-3">
      <div className="mb-3">
        <p className="text-xs text-muted-foreground font-medium">{greeting}</p>
        <h2 className="text-lg font-bold mt-0.5">
          {nickname || '你好'}，今天想探索什么？
        </h2>
      </div>
      <div onClick={onSearchClick}>
        <div className="flex items-center gap-2.5 rounded-2xl bg-card/80 backdrop-blur-sm px-4 py-3 shadow-sm border border-border/30 text-muted-foreground cursor-pointer hover:border-brand-300/50 transition-colors">
          <Search className="h-4 w-4" />
          <span className="text-sm">搜索书籍、作者...</span>
        </div>
      </div>
    </div>
  )
}

/** 为你推荐 — 空状态 */
function EmptyRecommendState({ onGoProfile }: { onGoProfile: () => void }) {
  return (
    <div className="rounded-2xl bg-card border border-border/50 p-5 text-center shadow-sm">
      <div className="flex justify-center mb-3">
        <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-brand-100">
          <Frown className="h-6 w-6 text-brand-400" />
        </div>
      </div>
      <p className="text-sm font-semibold text-foreground">还没有为你生成推荐</p>
      <p className="text-xs text-muted-foreground mt-1">完善画像后，我们会为你推荐更适合的书</p>
      <button
        onClick={onGoProfile}
        className="mt-3 inline-flex items-center gap-1 rounded-xl bg-primary px-4 py-2 text-xs font-medium text-primary-foreground hover:bg-primary/90 transition-colors btn-press"
      >
        <Target className="h-3.5 w-3.5" />
        去完善画像
      </button>
    </div>
  )
}

/** 继续阅读 3D Carousel */
function ContinueReadingCarousel({ books, onReadClick }: {
  books: RecentBookVO[]
  onReadClick: (bookId: number) => void
}) {
  const [activeIndex, setActiveIndex] = useState(0)
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const touchStartX = useRef(0)
  const totalRef = useRef(books.length)
  const total = books.length

  useEffect(() => { totalRef.current = total }, [total])

  const startAutoPlay = useCallback(() => {
    if (timerRef.current) clearInterval(timerRef.current)
    timerRef.current = setInterval(() => {
      setActiveIndex((prev) => (prev + 1) % totalRef.current)
    }, 3000)
  }, [])

  const stopAutoPlay = useCallback(() => {
    if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = null }
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
      return { transform: 'translateX(0) translateZ(0) rotateY(0deg) scale(1)', opacity: 1, zIndex: 3, transition: 'all 0.6s cubic-bezier(0.25, 0.46, 0.45, 0.94)' }
    }
    if (isCenter) return { transform: 'translateX(0) translateZ(0) rotateY(0deg) scale(1)', opacity: 1, zIndex: 3, transition: 'all 0.6s cubic-bezier(0.25, 0.46, 0.45, 0.94)' }
    if (isLeft) return { transform: 'translateX(-62%) translateZ(-80px) rotateY(25deg) scale(0.82)', opacity: 0.6, zIndex: 1, transition: 'all 0.6s cubic-bezier(0.25, 0.46, 0.45, 0.94)' }
    if (isRight) return { transform: 'translateX(62%) translateZ(-80px) rotateY(-25deg) scale(0.82)', opacity: 0.6, zIndex: 1, transition: 'all 0.6s cubic-bezier(0.25, 0.46, 0.45, 0.94)' }
    return { transform: 'translateZ(-200px) scale(0.5)', opacity: 0, zIndex: 0, transition: 'all 0.6s cubic-bezier(0.25, 0.46, 0.45, 0.94)' }
  }

  const gradients = [
    'from-brand-400 to-brand-500',
    'from-brand-300 to-brand-400',
    'from-brand-500 to-brand-600',
  ]

  return (
    <div className="relative overflow-hidden" onTouchStart={handleTouchStart} onTouchEnd={handleTouchEnd}>
      <div className="relative h-[120px] w-full overflow-hidden" style={{ perspective: '1000px' }}>
        {books.map((book, index) => {
          const offset = ((index - activeIndex) % total + total) % total
          const isCenter = offset === 0
          const gradient = gradients[index % gradients.length]
          return (
            <div key={book.bookId} className="absolute inset-0" style={getCardStyle(index)} onClick={() => { if (isCenter) onReadClick(book.bookId); else goTo(index) }}>
              <div className={`relative h-full overflow-hidden rounded-2xl bg-gradient-to-r ${gradient} p-4 shadow-lg cursor-pointer btn-press`}>
                <div className="absolute -right-6 -top-6 h-24 w-24 rounded-full bg-white/10" />
                <div className="absolute -right-2 top-8 h-16 w-16 rounded-full bg-white/5" />
                <div className="relative flex items-center gap-3.5 h-full">
                  <BookCover coverUrl={book.coverUrl} title={book.title} author={book.author} size="sm" className="flex-shrink-0 h-14 w-14 rounded-xl shadow-md" />
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
    </div>
  )
}

/** 评分徽章 */
function RatingBadge({ rating }: { rating: number | undefined | null }) {
  if (rating == null || rating < 0) return null
  const r = Number(rating.toFixed(1))
  let colorClass = '', bgClass = ''
  if (r >= 5.0) { colorClass = 'text-danger'; bgClass = 'bg-danger/10' }
  else if (r >= 4.5) { colorClass = 'text-warning'; bgClass = 'bg-warning/10' }
  else if (r >= 4.0) { colorClass = 'text-warning'; bgClass = 'bg-warning/10' }
  else if (r >= 3.0) { colorClass = 'text-success'; bgClass = 'bg-success/10' }
  else { colorClass = 'text-gray-400'; bgClass = 'bg-gray-200/50' }
  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${colorClass} ${bgClass}`}>
      <Star className="h-2.5 w-2.5" />
      {r}
    </span>
  )
}

function MatchBadge({ score }: { score: number | undefined | null }) {
  const pct = Math.round(Math.max(0, score ?? 0) * 100)
  let colorClass = '', bgClass = ''
  if (pct >= 80) { colorClass = 'text-warning'; bgClass = 'bg-warning/10' }
  else if (pct >= 60) { colorClass = 'text-success'; bgClass = 'bg-success/10' }
  else if (pct >= 40) { colorClass = 'text-info'; bgClass = 'bg-info/10' }
  else { colorClass = 'text-gray-400'; bgClass = 'bg-gray-200/50' }
  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${colorClass} ${bgClass}`}>
      <Sparkles className="h-2.5 w-2.5" />
      {pct}%
    </span>
  )
}

function fmtReadCount(n: number): string {
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万次阅读`
  return `${n}次阅读`
}

/** 竖向图书列表 */
function VerticalBookList({ books, onBookClick, matchScores }: {
  books: (SimpleBookVO | RecommendedBook)[]
  onBookClick: (id: number) => void
  matchScores?: Record<string, number>
}) {
  const [expandedDesc, setExpandedDesc] = useState<Set<number>>(new Set())

  const toggleDesc = (e: any, bookId: number) => {
    e.stopPropagation()
    setExpandedDesc((prev) => {
      const next = new Set(prev)
      if (next.has(bookId)) {
        next.delete(bookId)
      } else {
        next.add(bookId)
      }
      return next
    })
  }

  return (
    <div className="space-y-2.5">
      {books.map((book, index) => {
        const ms = matchScores?.[String(book.id)] ?? (book as RecommendedBook).matchScore
        const tags = parseFormatTags((book as RecommendedBook).formatTags)
        const desc = (book as RecommendedBook).description
        const descExpanded = expandedDesc.has(book.id)
        return (
          <div
            key={book.id}
            className="rounded-2xl bg-card/90 shadow-sm border border-border/50 cursor-pointer btn-press list-item-enter"
            style={{ animationDelay: `${index * 50}ms` }}
            onClick={() => onBookClick(book.id)}
          >
            <div className="flex gap-3 p-3 pb-2">
              <BookCover coverUrl={book.coverUrl} title={book.title} author={book.author} format={book.format} size="md" className="flex-shrink-0 h-24 w-16" />
              <div className="flex-1 min-w-0 flex flex-col justify-between">
                <div>
                  <p className="truncate text-sm font-semibold">{book.title}</p>
                  <p className="mt-0.5 truncate text-xs text-muted-foreground">{book.author || '未知作者'}</p>
                  {tags.length > 0 && (
                    <div className="mt-1.5 flex items-center gap-1 flex-wrap">
                      {tags.slice(0, 3).map((tag) => (
                        <span key={tag} className="inline-flex items-center gap-0.5 rounded-md bg-brand-100 px-1.5 py-0.5 text-[10px] font-medium text-brand-500">
                          <Tag className="h-2.5 w-2.5" />
                          {tag}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
                <div className="mt-1.5 flex items-center gap-2 flex-wrap">
                  <RatingBadge rating={book.rating} />
                  <MatchBadge score={ms} />
                  <span className="text-[11px] text-muted-foreground">{fmtReadCount(book.readCount)}</span>
                </div>
              </div>
            </div>

            {desc && (
              <button
                onClick={(e) => toggleDesc(e, book.id)}
                className="w-full border-t border-border/30 px-3 pb-2.5 pt-1.5 text-left"
              >
                <p
                  className={`text-[11px] text-foreground/70 leading-snug transition-all duration-200 ${
                    descExpanded ? '' : 'line-clamp-2'
                  }`}
                >
                  {desc}
                </p>
                <span className="mt-1 block text-[10px] text-brand-400 hover:text-brand-500 transition-colors">
                  {descExpanded ? '收起' : '展开'}
                </span>
              </button>
            )}
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
        <div key={i} className="rounded-2xl bg-card shadow-sm border border-border/50">
          <div className="flex gap-3 p-3 pb-2">
            <div className="h-24 w-16 flex-shrink-0 rounded-lg skeleton" />
            <div className="flex-1 space-y-2">
              <div className="h-4 w-3/4 skeleton rounded" />
              <div className="h-3 w-1/2 skeleton rounded" />
              <div className="h-5 w-1/3 skeleton rounded" />
            </div>
          </div>
          <div className="border-t border-border/30 px-3 pb-2.5 pt-1.5">
            <div className="h-3 w-full skeleton rounded" />
          </div>
        </div>
      ))}
    </div>
  )
}

/** 阅读统计 — 弱化为一行 */
function ReadingStatsCompact({ stats }: { stats: ReadingStatsVO }) {
  const navigate = useNavigate()
  if (!stats || (stats.totalBooks === 0 && stats.readingBooks === 0)) return null
  return (
    <section className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
      <button
        onClick={() => navigate('/profile/history')}
        className="w-full flex items-center justify-between"
      >
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-1.5">
            <BookOpenCheck className="h-3.5 w-3.5 text-brand-400" />
            <span className="text-xs text-muted-foreground">已读</span>
            <span className="text-sm font-bold">{stats.totalBooks}</span>
          </div>
          <div className="w-px h-4 bg-border" />
          <div className="flex items-center gap-1.5">
            <BookOpen className="h-3.5 w-3.5 text-warning" />
            <span className="text-xs text-muted-foreground">在读</span>
            <span className="text-sm font-bold">{stats.readingBooks}</span>
          </div>
          <div className="w-px h-4 bg-border" />
          <div className="flex items-center gap-1.5">
            <BookCheck className="h-3.5 w-3.5 text-success" />
            <span className="text-xs text-muted-foreground">读完</span>
            <span className="text-sm font-bold">{stats.completedBooks}</span>
          </div>
        </div>
        <ChevronRight className="h-4 w-4 text-muted-foreground" />
      </button>
    </section>
  )
}

export default function HomePage() {
  const navigate = useNavigate()
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const hasFetchedRef = useRef(false)

  const [stats, setStats] = useState<ReadingStatsVO | null>(null)
  const [statsLoading, setStatsLoading] = useState(true)
  const [recentBooks, setRecentBooks] = useState<RecentBookVO[]>([])
  const [recentLoading, setRecentLoading] = useState(true)
  const [personalizedBooks, setPersonalizedBooks] = useState<RecommendedBook[]>([])
  const [personalizedLoading, setPersonalizedLoading] = useState(true)
  const [categories, setCategories] = useState<TagStat[]>([])
  const [categoriesLoading, setCategoriesLoading] = useState(true)

  useEffect(() => {
    if (!isAuthenticated || hasFetchedRef.current) {
      setStatsLoading(false); setRecentLoading(false); setPersonalizedLoading(false)
      setCategoriesLoading(false)
      return
    }
    hasFetchedRef.current = true
    getHomeStats().then((res) => setStats((res as any)?.data || (res as any))).catch(() => {}).finally(() => setStatsLoading(false))
    getHomeRecent().then((res) => setRecentBooks((res as any)?.data || (res as any) || [])).catch(() => {}).finally(() => setRecentLoading(false))
    getHomePersonalized().then((res) => setPersonalizedBooks((res as any)?.data || (res as any) || [])).catch(() => {}).finally(() => setPersonalizedLoading(false))
    getHomeCategories().then((res) => setCategories((res as any)?.data || (res as any) || [])).catch(() => {}).finally(() => setCategoriesLoading(false))
  }, [isAuthenticated])

  const matchBookIds = useMemo(() => {
    return personalizedBooks.map(b => b.id)
  }, [personalizedBooks])
  const matchScores = useMatchScores(matchBookIds)

  const goToBook = (id: number) => navigate(`/book/${id}`)

  return (
    <div className="min-h-screen px-4 page-enter pb-20">
      <Header />

      <div className="space-y-6">
        <HeroSection onSearchClick={() => navigate('/search')} />
        <MoodQuickSwitch />

        {recentLoading ? (
          <div className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
            <div className="mb-3 flex items-center gap-2">
              <div className="h-7 w-7 skeleton rounded-lg" />
              <div className="h-4 w-16 skeleton rounded" />
            </div>
            <div className="h-[120px] skeleton rounded-2xl" />
          </div>
        ) : recentBooks.length > 0 ? (
          <section className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="flex items-center gap-2 text-sm font-bold">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-brand-100">
                  <Clock className="h-4 w-4 text-brand-500" />
                </div>
                继续阅读
              </h2>
              <button onClick={() => navigate('/profile/history')} className="flex items-center text-xs text-primary font-medium">
                查看更多 <ChevronRight className="h-3 w-3" />
              </button>
            </div>
            <ContinueReadingCarousel books={recentBooks.slice(0, 3)} onReadClick={goToBook} />
          </section>
        ) : null}

        {personalizedLoading ? (
          <div className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
            <div className="mb-3 flex items-center gap-2">
              <div className="h-7 w-7 skeleton rounded-lg" />
              <div className="h-4 w-16 skeleton rounded" />
            </div>
            <VerticalListSkeleton />
          </div>
        ) : personalizedBooks.length > 0 ? (
          <section className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="flex items-center gap-2 text-sm font-bold">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-brand-100">
                  <Target className="h-4 w-4 text-brand-500" />
                </div>
                为你推荐
                <span className="text-[10px] font-normal text-muted-foreground ml-1">基于你的画像</span>
              </h2>
              <button onClick={() => navigate('/recommend')} className="flex items-center text-xs text-primary font-medium hover:underline">
                查看更多 <ChevronRight className="h-3 w-3" />
              </button>
            </div>
            <VerticalBookList books={personalizedBooks.slice(0, 10)} onBookClick={goToBook} matchScores={matchScores} />
            <button
              onClick={() => navigate('/recommend')}
              className="mt-3 flex w-full items-center justify-center gap-1 rounded-xl bg-muted/50 py-2.5 text-xs font-medium text-muted-foreground hover:bg-muted/80 transition-colors btn-press"
            >
              查看更多推荐 <ChevronRight className="h-3.5 w-3.5" />
            </button>
          </section>
        ) : (
          <EmptyRecommendState onGoProfile={() => navigate('/profile')} />
        )}

        {statsLoading ? (
          <div className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-4">
                <div className="h-4 w-16 skeleton rounded" />
                <div className="h-4 w-16 skeleton rounded" />
                <div className="h-4 w-16 skeleton rounded" />
              </div>
              <div className="h-4 w-4 skeleton rounded" />
            </div>
          </div>
        ) : stats && (stats.totalBooks > 0 || stats.readingBooks > 0) ? (
          <ReadingStatsCompact stats={stats} />
        ) : null}

        {categoriesLoading ? (
          <div className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
            <div className="mb-3 flex items-center gap-2">
              <div className="h-7 w-7 skeleton rounded-lg" />
              <div className="h-4 w-16 skeleton rounded" />
            </div>
            <div className="flex flex-wrap gap-2">
              {Array.from({ length: 8 }, (_, i) => (
                <div key={i} className="h-9 skeleton rounded-xl" style={{ width: `${60 + (i % 3) * 20}px` }} />
              ))}
            </div>
          </div>
        ) : categories.length > 0 ? (
          <section className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="flex items-center gap-2 text-sm font-bold">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-brand-100">
                  <Tag className="h-4 w-4 text-brand-500" />
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
                  className="flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-xl bg-muted/50 px-3 py-2 btn-press transition-all duration-150"
                >
                  <Tag className="h-3.5 w-3.5 text-primary" />
                  <span className="text-xs font-medium">{cat.name}</span>
                  <span className="text-[10px] text-muted-foreground">{cat.count}</span>
                </button>
              ))}
            </div>
          </section>
        ) : null}

        {!statsLoading && !recentLoading && !personalizedLoading &&
          recentBooks.length === 0 && personalizedBooks.length === 0 && (
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
