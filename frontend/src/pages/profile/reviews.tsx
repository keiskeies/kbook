import { useMemo } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { ArrowLeft, PenLine } from 'lucide-react'
import { getMyReviews, type ReviewVO } from '@/api/review'
import { ReviewList } from '@/components/review/ReviewList'
import { useGoBack } from '@/hooks/useGoBack'

export default function MyReviewsPage() {
  const goBack = useGoBack()

  const { data, fetchNextPage, hasNextPage, isFetchingNextPage, isLoading } = useInfiniteQuery({
    queryKey: ['profile', 'reviews'],
    queryFn: ({ pageParam }) => getMyReviews(pageParam, 10),
    initialPageParam: 1,
    getNextPageParam: (lastPage) => {
      const loaded = lastPage.page * lastPage.size
      return loaded < lastPage.total ? lastPage.page + 1 : undefined
    },
  })

  const reviews = useMemo<ReviewVO[]>(
    () => data?.pages.flatMap((page) => page.list) ?? [],
    [data],
  )
  const total = data?.pages[0]?.total ?? 0

  return (
    <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col overflow-hidden bg-background page-enter">
      <header className="shrink-0 flex items-center gap-3 border-b border-border/50 bg-navbar/95 px-4 py-3 backdrop-blur-xl z-20">
        <button
          onClick={() => goBack()}
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl transition-colors hover:bg-muted"
          aria-label="返回"
        >
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="truncate text-base font-bold">我的书评</h1>
        {total > 0 && <span className="text-xs text-muted-foreground">共 {total} 篇</span>}
      </header>

      <div className="flex-1 overflow-y-auto overscroll-contain px-4 py-4 pb-20 md:pb-4">
        {isLoading ? (
          <div className="space-y-3">
            {[0, 1, 2].map((i) => (
              <div key={i} className="h-32 animate-pulse rounded-2xl bg-muted" />
            ))}
          </div>
        ) : (
          <>
            <ReviewList
              reviews={reviews}
              showBook
              hasMore={hasNextPage}
              loadingMore={isFetchingNextPage}
              onLoadMore={() => fetchNextPage()}
              emptyText="还没有写过书评"
              invalidateKeys={[['profile', 'reviews']]}
            />
            {reviews.length === 0 && (
              <div className="flex flex-col items-center gap-3 pt-6 text-muted-foreground">
                <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-muted">
                  <PenLine className="h-7 w-7 text-muted-foreground/50" />
                </div>
                <p className="text-sm">读完一本书后，来记下当时的想法</p>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
