import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { TrendingUp, Star, Sparkles } from 'lucide-react'
import { getReadRank, getRatingRank, getNewBooksRank } from '@/api/book'
import type { Book } from '@/types/book'
import { parseFormatTags } from '@/types/book'
import { formatTag } from '@/utils/time'
import BookCover from '@/components/book/BookCover'
import { useMatchScores } from '@/hooks/useMatchScores'

type RankType = 'read' | 'rating' | 'new'

const RANK_TABS: { key: RankType; label: string; icon: React.ReactNode }[] = [
  { key: 'read', label: '热门阅读', icon: <TrendingUp className="h-4 w-4" /> },
  { key: 'rating', label: '高分推荐', icon: <Star className="h-4 w-4" /> },
  { key: 'new', label: '新书速递', icon: <Sparkles className="h-4 w-4" /> },
]

export default function RankPage() {
  const navigate = useNavigate()
  const [type, setType] = useState<RankType>('read')
  const [books, setBooks] = useState<Book[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    setLoading(true)
    const fetcher = type === 'read' ? getReadRank
      : type === 'rating' ? getRatingRank
      : getNewBooksRank
    fetcher(1, 50)
      .then((res) => setBooks((res as any)?.list || []))
      .catch(() => setBooks([]))
      .finally(() => setLoading(false))
  }, [type])

  const getStatLabel = (book: Book) => {
    if (type === 'read') return `${book.readCount} 次阅读`
    if (type === 'rating') return book.rating > 0 ? `${book.rating.toFixed(1)} 分` : '-'
    return formatTag(book.format)
  }

  const getStatIcon = () => {
    if (type === 'rating') return <Star className="h-2.5 w-2.5 text-yellow-500" />
    return null
  }

  // 书籍的匹配分
  const matchScores = useMatchScores(books.map(b => b.id))

  return (
    <div className="page-enter">
      {/* 顶部头部 + Tab - 固定在顶部 */}
      <div className="fixed top-0 left-0 right-0 z-50 bg-gradient-to-b from-primary/8 via-primary/3 to-transparent pt-safe-top backdrop-blur-xl border-b border-border/30">
        <div className="px-4">
          <header className="py-4">
            <h1 className="text-xl font-bold">发现好书</h1>
          </header>

          <div className="mb-4 flex gap-2">
            {RANK_TABS.map((tab) => (
              <button
                key={tab.key}
                onClick={() => setType(tab.key)}
                className={`flex items-center gap-1.5 rounded-full px-4 py-2 text-sm font-medium transition-colors ${
                  type === tab.key ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground'
                }`}
              >
                {tab.icon}
                {tab.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* 占位元素,防止内容被固定头部遮挡 */}
      <div className="h-[140px]" />

      <div className="px-4">

      {loading ? (
        <div className="space-y-3">
          {Array.from({ length: 10 }, (_, i) => (
            <div key={i} className="flex items-center gap-3">
              <div className="h-6 w-6 rounded bg-muted animate-pulse" />
              <div className="h-16 w-12 flex-shrink-0 rounded bg-muted animate-pulse" />
              <div className="flex-1 space-y-2">
                <div className="h-4 w-3/4 rounded bg-muted animate-pulse" />
                <div className="h-3 w-1/2 rounded bg-muted animate-pulse" />
              </div>
            </div>
          ))}
        </div>
      ) : books.length > 0 ? (
        <div className="space-y-2">
          {books.map((book, index) => {
            const ms = matchScores?.[String(book.id)]
            return (
            <div
              key={book.id}
              className="flex items-center gap-3 rounded-2xl bg-card p-3 shadow-sm border border-border/50"
              onClick={() => navigate(`/book/${book.id}`)}
            >
              <span className={`flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-xl text-xs font-bold ${
                index < 3 ? 'bg-primary text-primary-foreground shadow-sm' : 'bg-muted text-muted-foreground'
              }`}>
                {index + 1}
              </span>
              <BookCover coverUrl={book.coverUrl} title={book.title} author={book.author} format={book.format} size="sm" className="flex-shrink-0" />
              <div className="flex-1 min-w-0">
                <p className="truncate text-sm font-medium">{book.title}</p>
                <p className="mt-0.5 text-xs text-muted-foreground">{book.author || '未知作者'}</p>
                <div className="mt-1 flex items-center gap-2">
                  {book.rating > 0 && (
                    <span className="flex items-center gap-0.5 text-[10px]">
                      <Star className="h-2.5 w-2.5 fill-amber-400 text-amber-400" />
                      <span className="font-semibold text-amber-600 dark:text-amber-400">{book.rating.toFixed(1)}</span>
                    </span>
                  )}
                  {ms != null && ms > 0 && (
                    <span className="flex items-center gap-0.5 text-[10px]">
                      <Sparkles className="h-2.5 w-2.5 text-amber-500" />
                      <span className="font-semibold text-amber-600 dark:text-amber-400">{Math.round(ms * 100)}%</span>
                    </span>
                  )}
                  <span className="flex items-center gap-0.5 text-[10px] text-muted-foreground">
                    {getStatIcon()}
                    {getStatLabel(book)}
                  </span>
                </div>
              </div>
            </div>
            )
          })}
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
