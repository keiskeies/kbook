import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Search, BookOpen, ChevronRight, Clock, Star, Sparkles,
  TrendingUp, Zap, Library, BookMarked, CheckCircle2, Play,
  Bell, MessageSquareText,
} from 'lucide-react'
import { getHomeData } from '@/api/home'
import { getUnreadCount } from '@/api/notification'
import type { HomeData, RecentBookVO, RecommendedBook, SimpleBookVO, FormatCategory } from '@/api/home'
import { formatTag, formatRelativeTime } from '@/utils/time'

/** 书籍封面组件 */
function BookCover({ coverUrl, title, format, size = 'normal' }: {
  coverUrl: string | null; title: string; format?: string; size?: 'normal' | 'large'
}) {
  const h = size === 'large' ? 'h-44' : 'h-36'
  return (
    <div className={`relative aspect-[3/4] w-full ${h} overflow-hidden rounded-xl bg-muted shadow-md`}>
      {coverUrl ? (
        <img src={coverUrl} alt={title} className="h-full w-full object-cover transition-transform duration-300 hover:scale-105" loading="lazy" />
      ) : (
        <div className="flex h-full w-full items-center justify-center bg-gradient-to-br from-primary/15 to-accent">
          <BookOpen className="h-8 w-8 text-primary/30" />
        </div>
      )}
      {format && (format === 'PDF' || format === 'TXT') && (
        <span className="absolute right-1.5 top-1.5 rounded-md bg-black/50 px-1.5 py-0.5 text-[10px] font-medium text-white backdrop-blur-sm">
          {formatTag(format)}
        </span>
      )}
    </div>
  )
}

/** 最近阅读卡片 */
function RecentBookCard({ book, onClick }: { book: RecentBookVO; onClick: () => void }) {
  return (
    <div
      className="flex items-center gap-3.5 rounded-2xl bg-card p-3.5 shadow-sm border border-border/50 active:scale-[0.98] transition-all duration-150"
      onClick={onClick}
    >
      <div className="h-16 w-12 flex-shrink-0 overflow-hidden rounded-lg bg-muted shadow-sm">
        {book.coverUrl ? (
          <img src={book.coverUrl} alt={book.title} className="h-full w-full object-cover" />
        ) : (
          <div className="flex h-full w-full items-center justify-center bg-gradient-to-br from-primary/15 to-accent">
            <BookOpen className="h-4 w-4 text-primary/30" />
          </div>
        )}
      </div>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-semibold">{book.title}</p>
        <p className="mt-0.5 text-xs text-muted-foreground">{book.author || '未知作者'}</p>
        <div className="mt-2 flex items-center gap-2">
          <div className="h-1.5 flex-1 rounded-full bg-primary/10">
            <div className="h-full rounded-full bg-gradient-to-r from-primary to-primary/70 transition-all" style={{ width: `${Math.round(book.progress * 100)}%` }} />
          </div>
          <span className="text-[10px] font-bold text-primary">{Math.round(book.progress * 100)}%</span>
        </div>
      </div>
    </div>
  )
}

/** 横向书籍滚动列表 */
function BookScrollList({ books, onBookClick, renderExtra }: {
  books: (SimpleBookVO | RecommendedBook)[]
  onBookClick: (id: number) => void
  renderExtra?: (book: SimpleBookVO | RecommendedBook) => React.ReactNode
}) {
  return (
    <div className="flex gap-3 overflow-x-auto pb-2 scrollbar-hide -mx-4 px-4" style={{ scrollbarWidth: 'none' }}>
      {books.map((book) => (
        <div
          key={book.id}
          className="flex w-[100px] flex-shrink-0 flex-col cursor-pointer active:scale-[0.96] transition-transform duration-150"
          onClick={() => onBookClick(book.id)}
        >
          <BookCover coverUrl={book.coverUrl} title={book.title} format={book.format} />
          <p className="mt-1.5 w-full truncate text-xs font-semibold">{book.title}</p>
          {book.author && (
            <p className="w-full truncate text-[10px] text-muted-foreground">{book.author}</p>
          )}
          {renderExtra?.(book)}
        </div>
      ))}
    </div>
  )
}

/** 分类发现卡片 */
function CategoryCard({ category, onClick }: { category: FormatCategory; onClick: () => void }) {
  return (
    <div
      className="flex flex-col items-center gap-1.5 rounded-2xl bg-card p-4 shadow-sm border border-border/50 cursor-pointer active:scale-[0.95] transition-all duration-150 min-w-[88px]"
      onClick={onClick}
    >
      <span className="text-2xl">{category.icon}</span>
      <span className="text-xs font-semibold">{category.label}</span>
      <span className="text-[10px] text-primary font-medium">{category.count}本</span>
    </div>
  )
}

/** 区块标题 */
function SectionHeader({ icon, title, extra }: { icon: React.ReactNode; title: string; extra?: React.ReactNode }) {
  return (
    <div className="mb-3 flex items-center justify-between">
      <h2 className="flex items-center gap-2 text-base font-bold">
        {icon}
        {title}
      </h2>
      {extra}
    </div>
  )
}

export default function HomePage() {
  const navigate = useNavigate()
  const [homeData, setHomeData] = useState<HomeData | null>(null)
  const [loading, setLoading] = useState(true)
  const [unreadCount, setUnreadCount] = useState(0)

  useEffect(() => {
    getHomeData()
      .then((res) => setHomeData((res as any)?.data || (res as any)))
      .catch(() => {})
      .finally(() => setLoading(false))
    getUnreadCount().then(res => setUnreadCount((res as any)?.data || (res as any) || 0)).catch(() => {})
  }, [])

  const goToBook = (id: number) => navigate(`/book/${id}`)
  const goToReader = (id: number) => navigate(`/reader/${id}`)

  if (loading) {
    return (
      <div className="page-enter px-4 pt-safe-top">
        {/* 顶部品牌栏骨架 */}
        <div className="flex items-center justify-between py-3">
          <div className="flex items-center gap-2">
            <div className="h-8 w-8 animate-pulse rounded-lg bg-muted" />
            <div className="h-6 w-16 animate-pulse rounded bg-muted" />
          </div>
          <div className="flex gap-2">
            <div className="h-8 w-8 animate-pulse rounded-lg bg-muted" />
            <div className="h-8 w-8 animate-pulse rounded-lg bg-muted" />
          </div>
        </div>
        {/* 搜索框骨架 */}
        <div className="mb-4 mt-1 h-11 animate-pulse rounded-2xl bg-muted" />

        <div className="space-y-6">
          {/* 继续阅读骨架 */}
          <div className="h-28 animate-pulse rounded-2xl bg-muted" />
          {/* 统计卡片骨架 */}
          <div className="grid grid-cols-3 gap-2.5">
            <div className="h-20 animate-pulse rounded-2xl bg-muted" />
            <div className="h-20 animate-pulse rounded-2xl bg-muted" />
            <div className="h-20 animate-pulse rounded-2xl bg-muted" />
          </div>
          {/* 最近阅读骨架 */}
          <div className="space-y-2.5">
            <div className="h-5 w-24 animate-pulse rounded bg-muted" />
            <div className="h-20 animate-pulse rounded-2xl bg-muted" />
            <div className="h-20 animate-pulse rounded-2xl bg-muted" />
          </div>
          {/* 横向列表骨架 */}
          <div className="space-y-2.5">
            <div className="h-5 w-20 animate-pulse rounded bg-muted" />
            <div className="flex gap-3">
              {Array.from({ length: 4 }, (_, i) => (
                <div key={i} className="w-[100px] flex-shrink-0 space-y-2">
                  <div className="aspect-[3/4] animate-pulse rounded-xl bg-muted" />
                  <div className="h-3 w-4/5 animate-pulse rounded bg-muted" />
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    )
  }

  const d = homeData
  // 取第一本最近阅读的书作为"继续阅读"
  const lastReadBook = d?.recentBooks?.[0]

  return (
    <div className="page-enter pb-2">
      {/* 顶部品牌栏 + 搜索框 - fixed 固定在顶部 */}
      <div className="fixed inset-x-0 top-0 z-50 bg-background/95 backdrop-blur-xl pt-safe-top pb-2">
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
        {/* 0. 继续上次阅读 */}
        {lastReadBook && (
          <section>
            <div
              className="relative overflow-hidden rounded-2xl bg-gradient-to-r from-primary to-primary/80 p-4 shadow-lg shadow-primary/20 cursor-pointer active:scale-[0.98] transition-transform"
              onClick={() => goToReader(lastReadBook.bookId)}
            >
              {/* 装饰性背景 */}
              <div className="absolute -right-6 -top-6 h-24 w-24 rounded-full bg-white/10" />
              <div className="absolute -right-2 top-8 h-16 w-16 rounded-full bg-white/5" />

              <div className="relative flex items-center gap-3.5">
                <div className="flex h-14 w-14 flex-shrink-0 items-center justify-center rounded-xl bg-white/20 backdrop-blur-sm">
                  <Play className="h-7 w-7 text-white" fill="white" />
                </div>
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-bold text-white/80">继续阅读</p>
                  <p className="mt-0.5 truncate text-base font-bold text-white">{lastReadBook.title}</p>
                  <div className="mt-2 flex items-center gap-2.5">
                    <div className="h-1.5 flex-1 rounded-full bg-white/20">
                      <div className="h-full rounded-full bg-white/90 transition-all" style={{ width: `${Math.round(lastReadBook.progress * 100)}%` }} />
                    </div>
                    <span className="text-xs font-bold text-white/90">{Math.round(lastReadBook.progress * 100)}%</span>
                  </div>
                  <div className="mt-1 flex items-center gap-2 text-[10px] text-white/60">
                    <Clock className="h-2.5 w-2.5" />
                    <span>{formatRelativeTime(lastReadBook.lastReadAt)}</span>
                    <span>·</span>
                    <span>{lastReadBook.author || '未知作者'}</span>
                  </div>
                </div>
              </div>
            </div>
          </section>
        )}

        {/* 1. 阅读统计卡片 */}
        {d && d.stats && (d.stats.totalBooks > 0 || d.stats.readingBooks > 0) && (
          <section>
            <div className="grid grid-cols-3 gap-2.5">
              <button
                onClick={() => navigate('/profile/history')}
                className="flex flex-col items-center rounded-2xl bg-primary/8 border border-primary/15 p-3.5 active:scale-[0.95] transition-transform"
              >
                <BookMarked className="h-5 w-5 text-primary" />
                <span className="mt-1.5 text-xl font-bold text-primary">{d.stats.totalBooks}</span>
                <span className="text-[10px] text-muted-foreground font-medium">已读书籍</span>
              </button>
              <button
                onClick={() => navigate('/profile/history')}
                className="flex flex-col items-center rounded-2xl bg-emerald-500/8 border border-emerald-500/15 p-3.5 active:scale-[0.95] transition-transform"
              >
                <CheckCircle2 className="h-5 w-5 text-emerald-500" />
                <span className="mt-1.5 text-xl font-bold text-emerald-600 dark:text-emerald-400">{d.stats.completedBooks}</span>
                <span className="text-[10px] text-muted-foreground font-medium">读完</span>
              </button>
              <button
                onClick={() => navigate('/bookshelf')}
                className="flex flex-col items-center rounded-2xl bg-amber-500/8 border border-amber-500/15 p-3.5 active:scale-[0.95] transition-transform"
              >
                <Zap className="h-5 w-5 text-amber-500" />
                <span className="mt-1.5 text-xl font-bold text-amber-600 dark:text-amber-400">{d.stats.readingBooks}</span>
                <span className="text-[10px] text-muted-foreground font-medium">在读</span>
              </button>
            </div>
          </section>
        )}

        {/* 2. 最近阅读 */}
        {d && d.recentBooks.length > 0 && (
          <section>
            <SectionHeader
              icon={<Clock className="h-4 w-4 text-primary" />}
              title="最近阅读"
              extra={
                <button onClick={() => navigate('/profile/history')} className="flex items-center text-xs text-primary font-medium">
                  查看更多 <ChevronRight className="h-3 w-3" />
                </button>
              }
            />
            <div className="space-y-2.5">
              {d.recentBooks.map((book) => (
                <RecentBookCard key={book.bookId} book={book} onClick={() => goToReader(book.bookId)} />
              ))}
            </div>
          </section>
        )}

        {/* 3. 猜你喜欢 */}
        {d && d.personalizedBooks.length > 0 && (
          <section>
            <SectionHeader
              icon={<Sparkles className="h-4 w-4 text-amber-500" />}
              title="猜你喜欢"
            />
            <BookScrollList
              books={d.personalizedBooks}
              onBookClick={goToBook}
              renderExtra={(book) => {
                const rb = book as RecommendedBook
                if (rb.matchScore > 0) {
                  return (
                    <div className="flex items-center gap-1 mt-0.5">
                      <Sparkles className="h-2.5 w-2.5 text-amber-500" />
                      <span className="text-[10px] font-semibold text-amber-600 dark:text-amber-400">匹配 {Math.round(rb.matchScore * 100)}%</span>
                    </div>
                  )
                }
                return null
              }}
            />
          </section>
        )}

        {/* 4. 高分佳作 */}
        {d && d.topRatedBooks.length > 0 && (
          <section>
            <SectionHeader
              icon={<Star className="h-4 w-4 text-amber-400" />}
              title="高分佳作"
              extra={
                <button onClick={() => navigate('/rank')} className="flex items-center text-xs text-primary font-medium">
                  查看更多 <ChevronRight className="h-3 w-3" />
                </button>
              }
            />
            <BookScrollList
              books={d.topRatedBooks}
              onBookClick={goToBook}
              renderExtra={(book) => {
                const sb = book as SimpleBookVO
                if (sb.rating > 0) {
                  return (
                    <div className="flex items-center gap-0.5 mt-0.5">
                      <Star className="h-2.5 w-2.5 fill-amber-400 text-amber-400" />
                      <span className="text-[10px] font-semibold text-amber-600 dark:text-amber-400">{sb.rating.toFixed(1)}</span>
                    </div>
                  )
                }
                return null
              }}
            />
          </section>
        )}

        {/* 5. 新书速递 */}
        {d && d.newBooks.length > 0 && (
          <section>
            <SectionHeader
              icon={<Zap className="h-4 w-4 text-sky-500" />}
              title="新书速递"
              extra={
                <button onClick={() => navigate('/rank')} className="flex items-center text-xs text-primary font-medium">
                  查看更多 <ChevronRight className="h-3 w-3" />
                </button>
              }
            />
            <BookScrollList books={d.newBooks} onBookClick={goToBook} />
          </section>
        )}

        {/* 6. 热门榜单 */}
        {d && d.popularBooks.length > 0 && (
          <section>
            <SectionHeader
              icon={<TrendingUp className="h-4 w-4 text-rose-500" />}
              title="热门榜单"
              extra={
                <button onClick={() => navigate('/rank')} className="flex items-center text-xs text-primary font-medium">
                  查看更多 <ChevronRight className="h-3 w-3" />
                </button>
              }
            />
            <BookScrollList
              books={d.popularBooks}
              onBookClick={goToBook}
              renderExtra={(book) => {
                const sb = book as SimpleBookVO
                return (
                  <div className="flex items-center gap-1 mt-0.5">
                    <TrendingUp className="h-2.5 w-2.5 text-rose-400" />
                    <span className="text-[10px] text-muted-foreground">{sb.readCount}次阅读</span>
                  </div>
                )
              }}
            />
          </section>
        )}

        {/* 7. 分类发现 */}
        {d && d.categories.length > 0 && (
          <section>
            <SectionHeader
              icon={<Library className="h-4 w-4 text-violet-500" />}
              title="分类发现"
            />
            <div className="flex gap-3 overflow-x-auto pb-2 -mx-4 px-4 scrollbar-hide" style={{ scrollbarWidth: 'none' }}>
              {d.categories.map((cat) => (
                <CategoryCard
                  key={cat.format}
                  category={cat}
                  onClick={() => navigate(`/search?format=${cat.format}`)}
                />
              ))}
            </div>
          </section>
        )}

        {/* 无数据 */}
        {(!d || (d.recentBooks.length === 0 && d.personalizedBooks.length === 0 && d.topRatedBooks.length === 0)) && (
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
