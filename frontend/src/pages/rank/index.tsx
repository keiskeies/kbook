import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { BookOpen, TrendingUp, Star, Sparkles } from 'lucide-react'
import { getReadRank, getRatingRank, getNewBooksRank } from '@/api/book'
import type { Book } from '@/types/book'
import { parseFormatTags } from '@/types/book'
import { formatTag } from '@/utils/time'

type RankType = 'read' | 'rating' | 'new'

const RANK_TABS: { key: RankType; label: string; icon: React.ReactNode }[] = [
  { key: 'read', label: '阅读榜', icon: <TrendingUp className="h-4 w-4" /> },
  { key: 'rating', label: '评分榜', icon: <Star className="h-4 w-4" /> },
  { key: 'new', label: '新书榜', icon: <Sparkles className="h-4 w-4" /> },
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

  return (
    <div className="page-enter">
      {/* 顶部头部 + Tab - 固定在顶部 */}
      <div className="fixed top-0 left-0 right-0 z-50 bg-gradient-to-b from-primary/8 via-primary/3 to-transparent pt-safe-top backdrop-blur-sm border-b border-border/30">
        <div className="px-4">
          <header className="py-4">
            <h1 className="text-xl font-bold">榜单</h1>
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
          {books.map((book, index) => (
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
              <div className="relative h-16 w-12 flex-shrink-0 overflow-hidden rounded bg-muted">
                {book.coverUrl ? (
                  <img src={book.coverUrl} alt={book.title} className="h-full w-full object-cover" />
                ) : (
                  <div className="flex h-full w-full items-center justify-center bg-gradient-to-br from-primary/20 to-primary/5">
                    <BookOpen className="h-4 w-4 text-primary/40" />
                  </div>
                )}
                {(book.format === 'PDF' || book.format === 'TXT') && (
                  <span className="absolute right-0.5 top-0.5 rounded bg-black/60 px-1 py-0.5 text-[8px] font-medium text-white">
                    {formatTag(book.format)}
                  </span>
                )}
              </div>
              <div className="flex-1 min-w-0">
                <p className="truncate text-sm font-medium">{book.title}</p>
                <p className="mt-0.5 text-xs text-muted-foreground">{book.author || '未知作者'}</p>
                <div className="mt-1 flex items-center gap-2">
                  <span className="flex items-center gap-0.5 text-[10px] text-muted-foreground">
                    {getStatIcon()}
                    {getStatLabel(book)}
                  </span>
                </div>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="flex h-[50vh] flex-col items-center justify-center">
          <TrendingUp className="mb-4 h-12 w-12 text-muted-foreground" />
          <p className="text-sm text-muted-foreground">暂无排行数据</p>
        </div>
      )}
      </div>
    </div>
  )
}
