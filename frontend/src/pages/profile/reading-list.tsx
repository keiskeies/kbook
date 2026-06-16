import { useState, useCallback, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useGoBack } from '@/hooks/useGoBack'
import { ArrowLeft, BookOpen, Loader2 } from 'lucide-react'
import { getReadingHistory } from '@/api/progress'
import { getMatchScores } from '@/api/book'
import { BookCard } from '@/components/book/BookCard'

interface ReadingListItem {
  bookId: number
  title: string
  author: string | null
  coverUrl: string | null
  format: string | null
  progress: number
  updatedAt: string
  rating?: number
  readCount?: number
  description?: string
}

export default function ReadingListPage() {
  const navigate = useNavigate()
  const goBack = useGoBack()
  const [items, setItems] = useState<ReadingListItem[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(true)
  const [matchScores, setMatchScores] = useState<Record<string, number>>({})

  const loadItems = useCallback(async (p: number) => {
    setLoading(true)
    try {
      const res = await getReadingHistory(p, 18)
      const data = (res as any)?.data || (res as any)
      const list: ReadingListItem[] = (data?.list || data?.content || []).map((item: any) => ({
        bookId: item.bookId ?? item.book_id,
        title: item.title,
        author: item.author,
        coverUrl: item.coverUrl,
        format: item.format,
        progress: item.progress ?? 0,
        updatedAt: item.updatedAt,
        rating: item.rating,
        readCount: item.readCount,
        description: item.description,
      }))
      if (p === 0) setTotal(data?.total ?? 0)
      setItems(prev => p === 0 ? list : [...prev, ...list])
      setHasMore(list.length >= 18)
    } catch {
      // ignore
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    setPage(0)
    loadItems(0)
  }, [loadItems])

  // 加载完阅读记录后获取匹配度分数
  useEffect(() => {
    if (items.length === 0) { setMatchScores({}); return }
    const ids = items.map(item => item.bookId)
    getMatchScores(ids).then(res => {
      const data = (res as any)?.data || (res as any) || {}
      setMatchScores(prev => ({ ...prev, ...data }))
    }).catch(() => {})
  }, [items])

  return (
    <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col overflow-hidden bg-background page-enter">
      {/* Header */}
      <header className="shrink-0 flex items-center gap-3 border-b border-border/50 bg-background/80 px-4 py-3 backdrop-blur-xl z-20">
        <button onClick={() => goBack()} className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl hover:bg-muted transition-colors">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <div className="flex items-center gap-2 flex-1">
          <h1 className="truncate text-base font-bold">阅读记录</h1>
          {total > 0 && (
            <span className="text-xs text-muted-foreground shrink-0">共{total}本</span>
          )}
        </div>
      </header>

      {/* Content */}
      <div className="flex-1 overflow-y-auto overscroll-contain px-4 py-4 pb-20 md:pb-4">
        {loading && items.length === 0 ? (
          <div className="flex items-center justify-center py-20">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        ) : items.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-20 text-muted-foreground">
            <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-muted mb-4">
              <BookOpen className="h-7 w-7 text-muted-foreground/50" />
            </div>
            <p className="text-sm">还没有阅读记录</p>
          </div>
        ) : (
          <>
            <div className="columns-1 sm:columns-2 lg:columns-3 gap-3 space-y-3 [&>*]:break-inside-avoid">
              {items.map((book) => (
                <BookCard
                  key={book.bookId}
                  book={{
                    id: book.bookId,
                    title: book.title,
                    author: book.author,
                    coverUrl: book.coverUrl,
                    format: book.format ?? undefined,
                    rating: book.rating ?? null,
                    readCount: book.readCount ?? null,
                    description: book.description ?? null,
                    matchScore: matchScores[book.bookId],
                  }}
                  lastReadAt={book.updatedAt}
                  onClick={() => navigate(`/book/${book.bookId}`)}
                />
              ))}
            </div>
            <div className="mt-4 pb-4">
              {hasMore ? (
                <button
                  onClick={() => { const next = page + 1; setPage(next); loadItems(next) }}
                  className="w-full rounded-xl bg-muted py-3 text-sm font-medium text-muted-foreground hover:bg-muted/80 transition-colors"
                >
                  加载更多
                </button>
              ) : (
                <p className="text-center text-xs text-muted-foreground">已加载全部阅读记录</p>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  )
}
