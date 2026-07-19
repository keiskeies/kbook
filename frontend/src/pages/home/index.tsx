import { useEffect } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import {
  Search, BookOpen, ChevronRight, Tag,
  Target, Frown, History,
} from 'lucide-react'
import {
  getHomePersonalized,
  getHomeCategories,
  getHomeRecent,
} from '@/api/home'
import type { RecommendedBook, RecentBookVO } from '@/api/home'
import { Card } from '@/components/ui/card'
import { BookCard } from '@/components/book/BookCard'
import MoodQuickSwitch from '@/components/home/MoodQuickSwitch'
import { useAuthStore } from '@/store/auth'
import { useUiStore } from '@/store/ui'
import { ROUTES } from '@/constants'

/** 固定在顶部的 Logo 区域 */
function Header() {
  const navigate = useNavigate()
  const nickname = useAuthStore((s) => s.userInfo?.nickname)
  const hour = new Date().getHours()
  const greeting = hour < 6 ? '夜深了' : hour < 12 ? '早上好' : hour < 18 ? '下午好' : '晚上好'

  return (
    <header className="sticky top-0 z-50 -mx-4 md:-mx-6 lg:-mx-8 px-4 md:px-6 lg:px-8 pt-safe-top pb-2 bg-navbar/95 backdrop-blur-xl border-b border-border/30">
      <div className="flex items-center justify-between py-3">
        <div className="flex items-center gap-2 min-w-0">
          <div className="md:hidden flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-primary">
            <BookOpen className="h-4.5 w-4.5 text-primary-foreground" strokeWidth={2.5} />
          </div>
          <h1 className="md:hidden text-xl font-bold bg-gradient-to-r from-primary to-primary/70 bg-clip-text text-transparent">KBook</h1>
          <div className="hidden md:block min-w-0">
            <p className="text-xs text-muted-foreground font-medium">{greeting}</p>
            <h1 className="text-base font-bold truncate">{nickname || '你好'}，今天想探讨什么？</h1>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={() => navigate(`${ROUTES.DISCOVER}?tab=books`)} className="flex h-8 w-8 items-center justify-center rounded-lg hover:bg-muted transition-colors">
            <Search className="h-5 w-5 text-muted-foreground" />
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
    <div>
      <div className="lg:hidden mb-2">
        <p className="text-xs text-muted-foreground font-medium">{greeting}</p>
        <h2 className="text-h3 font-bold mt-0.5">
          {nickname || '你好'}，今天想探讨什么？
        </h2>
      </div>
      <div onClick={onSearchClick}>
        <Card className="flex items-center gap-2.5 border border-border px-4 py-3 text-muted-foreground cursor-pointer hover:border-primary/50 transition-colors">
          <Search className="h-4 w-4" />
          <span className="text-sm">搜索书籍、作者...</span>
        </Card>
      </div>
    </div>
  )
}

/** 为你推荐 — 空状态 */
function EmptyRecommendState({ onGoProfile }: { onGoProfile: () => void }) {
  return (
    <Card padding="lg" className="text-center">
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
    </Card>
  )
}

/** 竖向图书列表 — PC 双列，带 AI 快捷操作 */
function VerticalBookList({ books, onBookClick }: {
  books: RecommendedBook[]
  onBookClick: (id: number) => void
}) {
  return (
    <div className="space-y-2.5 md:grid md:grid-cols-2 md:gap-3 md:space-y-0">
      {books.map((book) => (
        <BookCard
          key={book.id}
          book={book}
          variant="list"
          onClick={() => onBookClick(book.id)}
        />
      ))}
    </div>
  )
}

/** 竖向列表骨架屏 */
function VerticalListSkeleton() {
  return (
    <div className="space-y-2.5 md:grid md:grid-cols-2 md:gap-3 md:space-y-0">
      {Array.from({ length: 3 }, (_, i) => (
        <div key={i} className="rounded-xl bg-transparent border-b border-border/30 last:border-b-0 p-3">
          <div className="flex gap-3">
            <div className="h-24 w-16 flex-shrink-0 rounded-lg skeleton" />
            <div className="flex-1 space-y-2">
              <div className="h-4 w-3/4 skeleton rounded" />
              <div className="h-3 w-1/2 skeleton rounded" />
              <div className="h-5 w-1/3 skeleton rounded" />
            </div>
          </div>
        </div>
      ))}
    </div>
  )
}

/** 最近阅读 — 横向卡片轮播 */
function RecentReading({
  books,
  onBookClick,
}: {
  books: RecentBookVO[]
  onBookClick: (id: number) => void
}) {
  if (books.length === 0) return null

  return (
    <Card asChild>
      <section>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="flex items-center gap-2 text-sm font-bold">
            <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-brand-100">
              <History className="h-4 w-4 text-brand-500" />
            </div>
            最近阅读
          </h2>
          <button
            onClick={() => onBookClick(-1)}
            className="flex items-center text-xs text-primary font-medium hover:underline"
          >
            查看全部 <ChevronRight className="h-3 w-3" />
          </button>
        </div>
        {/* 横向滚动（移动端） / 网格（PC端） */}
        <div className="flex gap-3 overflow-x-auto scrollbar-hide -mx-1 px-1 pb-1 md:grid md:grid-cols-4 md:overflow-visible">
          {books.map((book) => (
            <button
              key={book.bookId}
              onClick={() => onBookClick(book.bookId)}
              className="group flex shrink-0 w-32 md:w-auto flex-col gap-1.5 text-left active:scale-[0.98] transition-transform"
            >
              <div className="relative aspect-[3/4] w-full overflow-hidden rounded-lg bg-muted shadow-sm">
                {book.coverUrl ? (
                  <img
                    src={book.coverUrl}
                    alt={book.title}
                    className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-105"
                    loading="lazy"
                  />
                ) : (
                  <div className="flex h-full w-full items-center justify-center">
                    <BookOpen className="h-6 w-6 text-muted-foreground/40" />
                  </div>
                )}
              </div>
              <p className="text-xs font-semibold truncate">{book.title}</p>
              <p className="text-[10px] text-muted-foreground truncate">{book.author || '未知作者'}</p>
            </button>
          ))}
        </div>
      </section>
    </Card>
  )
}

export default function HomePage() {
  const navigate = useNavigate()
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const fetchUserInfo = useAuthStore((s) => s.fetchUserInfo)
  const queryClient = useQueryClient()

  const { data: personalizedBooks = [], isLoading: personalizedLoading } = useQuery({
    queryKey: ['home', 'personalized'],
    queryFn: () => getHomePersonalized().then(res => res ?? []),
    enabled: isAuthenticated,
  })

  const { data: categories = [], isLoading: categoriesLoading } = useQuery({
    queryKey: ['home', 'categories'],
    queryFn: () => getHomeCategories().then(res => res ?? []),
    enabled: isAuthenticated,
  })

  const { data: recentBooks = [] } = useQuery({
    queryKey: ['home', 'recent'],
    queryFn: () => getHomeRecent().then(res => res ?? []),
    enabled: isAuthenticated,
  })

  useEffect(() => {
    if (isAuthenticated) {
      fetchUserInfo()
    }
  }, [isAuthenticated, fetchUserInfo])

  const recommendRefreshKey = useUiStore((s) => s.recommendRefreshKey)
  useEffect(() => {
    if (!isAuthenticated || recommendRefreshKey === 0) return
    queryClient.invalidateQueries({ queryKey: ['home', 'personalized'] })
  }, [recommendRefreshKey, isAuthenticated, queryClient])

  const goToBook = (id: number) => navigate(`/book/${id}`)

  return (
    <div className="px-4 md:px-6 lg:px-8 page-enter md:pb-6">
      <Header />

      {/* 移动端：Hero + MoodQuickSwitch */}
      <div className="lg:hidden mt-4 space-y-6">
        <HeroSection onSearchClick={() => navigate(`${ROUTES.DISCOVER}?tab=books`)} />
        <MoodQuickSwitch />
      </div>

      {/* PC端：左右双栏；移动端：单列 */}
      <div className="mt-6 lg:mt-4 lg:grid lg:grid-cols-[1fr_300px] xl:grid-cols-[1fr_340px] lg:gap-6">
        {/* 左栏：主内容 */}
        <div className="space-y-6">
          {/* PC端：Hero 在左栏顶部 */}
          <div className="hidden lg:block">
            <HeroSection onSearchClick={() => navigate(`${ROUTES.DISCOVER}?tab=books`)} />
          </div>

          {/* 最近阅读 */}
          <RecentReading books={recentBooks} onBookClick={(id) => id === -1 ? navigate(ROUTES.READING_LIST) : goToBook(id)} />

          {personalizedLoading ? (
            <Card>
              <div className="mb-3 flex items-center gap-2">
                <div className="h-7 w-7 skeleton rounded-lg" />
                <div className="h-4 w-16 skeleton rounded" />
              </div>
              <VerticalListSkeleton />
            </Card>
          ) : personalizedBooks.length > 0 ? (
            <Card asChild>
              <section>
                <div className="mb-3 flex items-center justify-between">
                  <h2 className="flex items-center gap-2 text-sm font-bold">
                    <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-brand-100">
                      <Target className="h-4 w-4 text-brand-500" />
                    </div>
                    为你推荐
                    <span className="text-xs font-normal text-muted-foreground ml-1">基于你的画像</span>
                  </h2>
                  <button onClick={() => navigate(ROUTES.RECOMMEND)} className="flex items-center text-xs text-primary font-medium hover:underline">
                    查看更多 <ChevronRight className="h-3 w-3" />
                  </button>
                </div>
                <VerticalBookList books={personalizedBooks.slice(0, 10)} onBookClick={goToBook} />
                <button
                  onClick={() => navigate(ROUTES.RECOMMEND)}
                  className="mt-3 flex w-full items-center justify-center gap-1 rounded-xl bg-muted border border-border/50 py-2.5 text-xs font-medium text-muted-foreground hover:bg-muted/80 transition-colors btn-press"
                >
                  查看更多推荐 <ChevronRight className="h-3.5 w-3.5" />
                </button>
              </section>
            </Card>
          ) : (
            <EmptyRecommendState onGoProfile={() => navigate(ROUTES.PROFILE)} />
          )}
        </div>

        {/* 右栏：侧边栏 — 仅 PC 端显示 */}
        <div className="hidden lg:flex lg:flex-col lg:gap-4">
          <MoodQuickSwitch />
          {categoriesLoading ? (
            <Card>
              <div className="mb-3 flex items-center gap-2">
                <div className="h-7 w-7 skeleton rounded-lg" />
                <div className="h-4 w-16 skeleton rounded" />
              </div>
              <div className="flex flex-wrap gap-2">
                {Array.from({ length: 8 }, (_, i) => (
                  <div key={i} className="h-9 skeleton rounded-xl" style={{ width: `${60 + (i % 3) * 20}px` }} />
                ))}
              </div>
            </Card>
          ) : categories.length > 0 ? (
            <Card asChild>
              <section>
                <div className="mb-3 flex items-center justify-between">
                  <h2 className="flex items-center gap-2 text-sm font-bold">
                    <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-brand-100">
                      <Tag className="h-4 w-4 text-brand-500" />
                    </div>
                    热门标签
                  </h2>
                  <button onClick={() => navigate(`${ROUTES.DISCOVER}?tab=books`)} className="flex items-center text-xs text-primary font-medium">
                    搜索 <ChevronRight className="h-3 w-3" />
                  </button>
                </div>
                <div className="flex flex-wrap gap-2">
                  {categories.map((cat) => (
                    <button
                      key={cat.name}
                      onClick={() => navigate(`${ROUTES.DISCOVER}?tab=books&tag=${encodeURIComponent(cat.name)}`)}
                      className="flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-xl bg-muted px-3 py-2 btn-press transition-all duration-150 hover:bg-muted/80"
                    >
                      <Tag className="h-3.5 w-3.5 text-primary" />
                      <span className="text-xs font-medium">{cat.name}</span>
                      <span className="text-xs text-muted-foreground">{cat.count}</span>
                    </button>
                  ))}
                </div>
              </section>
            </Card>
          ) : null}
        </div>
      </div>

      {/* 移动端：统计和标签在底部 */}
      <div className="lg:hidden mt-6 space-y-6">
        {categoriesLoading ? (
          <Card>
            <div className="mb-3 flex items-center gap-2">
              <div className="h-7 w-7 skeleton rounded-lg" />
              <div className="h-4 w-16 skeleton rounded" />
            </div>
            <div className="flex flex-wrap gap-2">
              {Array.from({ length: 8 }, (_, i) => (
                <div key={i} className="h-9 skeleton rounded-xl" style={{ width: `${60 + (i % 3) * 20}px` }} />
              ))}
            </div>
          </Card>
        ) : categories.length > 0 ? (
          <Card asChild>
            <section>
              <div className="mb-3 flex items-center justify-between">
                <h2 className="flex items-center gap-2 text-sm font-bold">
                  <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-brand-100">
                    <Tag className="h-4 w-4 text-brand-500" />
                  </div>
                  热门标签
                </h2>
                <button onClick={() => navigate(`${ROUTES.DISCOVER}?tab=books`)} className="flex items-center text-xs text-primary font-medium">
                  搜索 <ChevronRight className="h-3 w-3" />
                </button>
              </div>
              <div className="flex flex-wrap gap-2">
                {categories.map((cat) => (
                  <button
                    key={cat.name}
                    onClick={() => navigate(`${ROUTES.DISCOVER}?tab=books&tag=${encodeURIComponent(cat.name)}`)}
                    className="flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-xl bg-muted px-3 py-2 btn-press transition-all duration-150 hover:bg-muted/80"
                  >
                    <Tag className="h-3.5 w-3.5 text-primary" />
                    <span className="text-xs font-medium">{cat.name}</span>
                    <span className="text-xs text-muted-foreground">{cat.count}</span>
                  </button>
                ))}
              </div>
            </section>
          </Card>
        ) : null}
      </div>

      {/* 空状态 */}
      {!personalizedLoading &&
        personalizedBooks.length === 0 && (
        <div className="flex h-60 flex-col items-center justify-center text-muted-foreground">
          <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-muted">
            <BookOpen className="h-8 w-8 text-muted-foreground/50" />
          </div>
          <p className="mt-4 text-sm">暂无推荐，完善画像获取个性化推荐</p>
        </div>
      )}
    </div>
  )
}
