import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useInfiniteQuery } from '@tanstack/react-query'
import { PenLine } from 'lucide-react'
import { getBookReviews, type ReviewSort, type ReviewVO } from '@/api/review'
import { ReviewList } from './ReviewList'
import { cn } from '@/utils/cn'

const SORTS: { value: ReviewSort; label: string }[] = [
  { value: 'latest', label: '最新' },
  { value: 'rating', label: '评分最高' },
]

/**
 * 书籍详情页的书评区块 — 移动端与 PC 端共用同一份实现，自带排序状态
 */
export function ReviewSection({ bookId }: { bookId: number }) {
  const navigate = useNavigate()
  const [sort, setSort] = useState<ReviewSort>('latest')

  const { data, fetchNextPage, hasNextPage, isFetchingNextPage, isLoading } = useInfiniteQuery({
    queryKey: ['book', 'reviews', bookId, sort],
    queryFn: ({ pageParam }) => getBookReviews(bookId, { sort, page: pageParam, size: 10 }),
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
    <section className="rounded-2xl border border-border/50 bg-card p-4">
      <div className="mb-3 flex items-center gap-2">
        <h3 className="text-sm font-bold">书评</h3>
        {total > 0 && <span className="text-xs text-muted-foreground">{total} 篇</span>}
        <button
          onClick={() => navigate(`/book/${bookId}/reviews/new`)}
          className="ml-auto flex items-center gap-1 rounded-lg bg-primary/10 px-2.5 py-1.5 text-xs font-semibold text-primary transition-colors hover:bg-primary/15"
        >
          <PenLine className="h-3.5 w-3.5" />
          写书评
        </button>
      </div>

      {total > 1 && (
        <div className="mb-3 flex gap-1.5">
          {SORTS.map((item) => (
            <button
              key={item.value}
              onClick={() => setSort(item.value)}
              className={cn(
                'rounded-full px-2.5 py-1 text-xs font-medium transition-colors',
                sort === item.value ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:text-foreground',
              )}
            >
              {item.label}
            </button>
          ))}
        </div>
      )}

      {isLoading ? (
        <div className="space-y-3">
          {[0, 1].map((i) => (
            <div key={i} className="h-28 animate-pulse rounded-xl bg-muted" />
          ))}
        </div>
      ) : (
        <ReviewList
          reviews={reviews}
          hasMore={hasNextPage}
          loadingMore={isFetchingNextPage}
          onLoadMore={() => fetchNextPage()}
          invalidateKeys={[['book', 'reviews', bookId]]}
        />
      )}
    </section>
  )
}
