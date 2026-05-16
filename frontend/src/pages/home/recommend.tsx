import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ArrowLeft, Sparkles, Star } from 'lucide-react'
import { getRecommendations } from '@/api/book'
import type { RecommendedItem } from '@/api/book'
import BookCover from '@/components/book/BookCover'

export default function RecommendPage() {
  const navigate = useNavigate()
  const [books, setBooks] = useState<RecommendedItem[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    getRecommendations(50)
      .then((res) => setBooks((res as any) || []))
      .catch(() => setBooks([]))
      .finally(() => setLoading(false))
  }, [])

  return (
    <div className="page-enter">
      {/* 头部 */}
      <div className="fixed top-0 left-0 right-0 z-50 bg-gradient-to-b from-background/95 via-background/80 to-background/60 pt-safe-top backdrop-blur-xl border-b border-border/30">
        <header className="flex items-center gap-3 px-4 py-3">
          <button onClick={() => navigate(-1)} className="flex h-8 w-8 items-center justify-center rounded-lg hover:bg-muted transition-colors">
            <ArrowLeft className="h-5 w-5" />
          </button>
          <div className="flex items-center gap-2">
            <Sparkles className="h-5 w-5 text-amber-500" />
            <h1 className="text-lg font-bold">为你推荐</h1>
          </div>
        </header>
      </div>

      {/* 占位 */}
      <div className="h-[60px]" />

      <div className="px-4 py-4">
        {loading ? (
          <div className="grid grid-cols-3 gap-3">
            {Array.from({ length: 9 }, (_, i) => (
              <div key={i} className="flex flex-col items-center gap-2">
                <div className="aspect-[3/4] w-full animate-pulse rounded-xl bg-muted" />
                <div className="h-3 w-3/4 animate-pulse rounded bg-muted" />
              </div>
            ))}
          </div>
        ) : books.length > 0 ? (
          <div className="grid grid-cols-3 gap-3">
            {books.map((book) => (
              <div
                key={book.bookId}
                className="flex flex-col cursor-pointer active:scale-[0.96] transition-transform duration-150"
                onClick={() => navigate(`/book/${book.bookId}`)}
              >
                <BookCover coverUrl={book.coverUrl} title={book.title} author={book.author} format={book.format} />
                <p className="mt-1.5 w-full truncate text-xs font-semibold">{book.title}</p>
                <div className="flex items-center gap-1.5 mt-0.5">
                  {book.rating != null && book.rating > 0 && (
                    <div className="flex items-center gap-0.5">
                      <Star className="h-2.5 w-2.5 fill-amber-400 text-amber-400" />
                      <span className="text-[10px] font-semibold text-amber-600 dark:text-amber-400">{book.rating.toFixed(1)}</span>
                    </div>
                  )}
                  {book.matchScore > 0 && (
                    <div className="flex items-center gap-0.5">
                      <Sparkles className="h-2.5 w-2.5 text-amber-500" />
                      <span className="text-[10px] font-semibold text-amber-600 dark:text-amber-400">{Math.round(book.matchScore * 100)}%</span>
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="flex h-[50vh] flex-col items-center justify-center">
            <Sparkles className="mb-4 h-12 w-12 text-muted-foreground" />
            <p className="text-sm text-muted-foreground">暂无推荐，多读几本书试试吧</p>
          </div>
        )}
      </div>
    </div>
  )
}
