import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { deleteReview, type ReviewVO } from '@/api/review'
import { ReviewCard } from './ReviewCard'
import { NoteCaptureSheet } from '@/components/note/NoteCaptureSheet'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui'

interface ReviewListProps {
  reviews: ReviewVO[]
  showBook?: boolean
  hasMore?: boolean
  loadingMore?: boolean
  onLoadMore?: () => void
  emptyText?: string
  /** 删除成功后需要失效的查询键 */
  invalidateKeys?: unknown[][]
}

export function ReviewList({
  reviews,
  showBook,
  hasMore,
  loadingMore,
  onLoadMore,
  emptyText = '还没有书评，来写第一篇',
  invalidateKeys = [],
}: ReviewListProps) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [pendingDelete, setPendingDelete] = useState<ReviewVO | null>(null)
  const [captureTarget, setCaptureTarget] = useState<ReviewVO | null>(null)

  const deleteMutation = useMutation({
    mutationFn: (reviewId: number) => deleteReview(reviewId),
    onSuccess: () => {
      invalidateKeys.forEach((key) => queryClient.invalidateQueries({ queryKey: key }))
      toast.success('书评已删除')
      setPendingDelete(null)
    },
    onError: (err: Error) => toast.error(err.message || '删除失败'),
  })

  if (reviews.length === 0) {
    return <p className="py-8 text-center text-sm text-muted-foreground">{emptyText}</p>
  }

  return (
    <>
      <div className="space-y-3">
        {reviews.map((review) => (
          <ReviewCard
            key={review.id}
            review={review}
            showBook={showBook}
            onClick={() => navigate(`/book/${review.bookId}/reviews/${review.id}`)}
            onEdit={() => navigate(`/book/${review.bookId}/reviews/${review.id}/edit`)}
            onDelete={() => setPendingDelete(review)}
            onCapture={() => setCaptureTarget(review)}
          />
        ))}
      </div>

      {hasMore && (
        <button
          onClick={onLoadMore}
          disabled={loadingMore}
          className="mt-3 w-full rounded-xl bg-muted py-2.5 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted/80 disabled:opacity-50"
        >
          {loadingMore ? '加载中...' : '加载更多'}
        </button>
      )}

      <AlertDialog open={!!pendingDelete} onOpenChange={(open) => { if (!open) setPendingDelete(null) }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除这篇书评？</AlertDialogTitle>
            <AlertDialogDescription>
              删除后无法恢复，确定要删除吗？
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => pendingDelete && deleteMutation.mutate(pendingDelete.id)}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              {deleteMutation.isPending ? '删除中...' : '删除'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <NoteCaptureSheet
        open={!!captureTarget}
        onOpenChange={(open) => { if (!open) setCaptureTarget(null) }}
        review={captureTarget}
      />
    </>
  )
}
