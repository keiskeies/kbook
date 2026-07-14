import { useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { useGoBack } from '@/hooks/useGoBack'
import { ArrowLeft, BookOpen, Loader2 } from 'lucide-react'
import { useQuery, useInfiniteQuery } from '@tanstack/react-query'
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

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function mapReadingListItem(item: any): ReadingListItem {
  return {
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
  }
}

export default function ReadingListPage() {
  const navigate = useNavigate()
  const goBack = useGoBack()

  const {
    data: infiniteData,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
    isLoading,
  } = useInfiniteQuery({
    queryKey: ['profile', 'reading-list'],
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    queryFn: ({ pageParam = 0 }: { pageParam: number }) => getReadingHistory(pageParam, 18),
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    getNextPageParam: (lastPage: any, allPages: any[]) => {
      const list = lastPage?.list || lastPage?.content || []
      return list.length >= 18 ? allPages.length : undefined
    },
    initialPageParam: 0,
  })

  const items = useMemo(
    () =>
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      infiniteData?.pages.flatMap((page: any) => {
        const list = page?.list || page?.content || []
        return list.map(mapReadingListItem)
      }) ?? [],
    [infiniteData],
  )

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const total = (infiniteData?.pages[0] as any)?.total ?? 0

  const bookIds = useMemo(() => items.map((item) => item.bookId), [items])

  const { data: matchScores = {} } = useQuery<Record<string, number>>({
    queryKey: ['profile', 'reading-list-match-scores', bookIds.join(',')],
    queryFn: () => getMatchScores(bookIds),
    enabled: bookIds.length > 0,
  })

  if (isLoading) {
    return (
      <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col overflow-hidden bg-background page-enter">
        <Header total={0} goBack={goBack} />
        <div className="flex-1 flex items-center justify-center">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      </div>
    )
  }

  if (items.length === 0) {
    return (
      <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col overflow-hidden bg-background page-enter">
        <Header total={0} goBack={goBack} />
        <div className="flex-1 flex flex-col items-center justify-center text-muted-foreground">
          <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-muted mb-4">
            <BookOpen className="h-7 w-7 text-muted-foreground/50" />
          </div>
          <p className="text-sm">还没有阅读记录</p>
        </div>
      </div>
    )
  }

  return (
    <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col overflow-hidden bg-background page-enter">
      <Header total={total} goBack={goBack} />
      <div className="flex-1 overflow-y-auto overscroll-contain px-4 py-4 pb-20 md:pb-4">
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
          {hasNextPage ? (
            <button
              onClick={() => fetchNextPage()}
              disabled={isFetchingNextPage}
              className="w-full rounded-xl bg-muted py-3 text-sm font-medium text-muted-foreground hover:bg-muted/80 transition-colors disabled:opacity-50"
            >
              {isFetchingNextPage ? '加载中...' : '加载更多'}
            </button>
          ) : (
            <p className="text-center text-xs text-muted-foreground">已加载全部阅读记录</p>
          )}
        </div>
      </div>
    </div>
  )
}

function Header({ total, goBack }: { total: number; goBack: () => void }) {
  return (
    <header className="shrink-0 flex items-center gap-3 border-b border-border/50 bg-navbar/95 px-4 py-3 backdrop-blur-xl z-20">
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
  )
}
