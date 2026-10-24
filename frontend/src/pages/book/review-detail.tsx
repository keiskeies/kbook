import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, BookmarkPlus, EyeOff, Pencil, Star, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { deleteReview, getReview } from '@/api/review'
import { formatRelativeTime } from '@/utils/time'
import { useGoBack } from '@/hooks/useGoBack'
import { cn } from '@/utils/cn'
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

export default function ReviewDetailPage() {
  const { bookId, reviewId } = useParams<{ bookId: string; reviewId: string }>()
  const navigate = useNavigate()
  const goBack = useGoBack()
  const queryClient = useQueryClient()
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [revealed, setRevealed] = useState(false)
  const [captureOpen, setCaptureOpen] = useState(false)

  const { data: review, isLoading } = useQuery({
    queryKey: ['review', 'detail', reviewId],
    queryFn: () => getReview(Number(reviewId)),
    enabled: !!reviewId,
  })

  const deleteMutation = useMutation({
    mutationFn: () => deleteReview(Number(reviewId)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['book', 'reviews', Number(bookId)] })
      queryClient.invalidateQueries({ queryKey: ['profile', 'reviews'] })
      toast.success('书评已删除')
      goBack()
    },
    onError: (err: Error) => toast.error(err.message || '删除失败'),
  })

  if (isLoading) {
    return (
      <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col overflow-hidden bg-background">
        <div className="shrink-0 border-b border-border/50 px-4 py-3">
          <div className="h-5 w-24 animate-pulse rounded bg-muted" />
        </div>
        <div className="flex-1 space-y-3 px-4 py-4">
          <div className="h-4 w-1/2 animate-pulse rounded bg-muted" />
          <div className="h-3 w-full animate-pulse rounded bg-muted" />
          <div className="h-3 w-5/6 animate-pulse rounded bg-muted" />
          <div className="h-3 w-4/6 animate-pulse rounded bg-muted" />
        </div>
      </div>
    )
  }

  if (!review) {
    return (
      <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col items-center justify-center gap-3 bg-background text-muted-foreground">
        <p className="text-sm">书评不存在或已被删除</p>
        <button onClick={() => goBack()} className="text-sm font-medium text-primary">返回</button>
      </div>
    )
  }

  const showContent = !review.isSpoiler || revealed

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
        <h1 className="flex-1 truncate text-base font-bold">书评</h1>
        <div className="flex shrink-0 items-center gap-1">
          <button
            onClick={() => setCaptureOpen(true)}
            className="flex h-9 w-9 items-center justify-center rounded-xl text-muted-foreground transition-colors hover:bg-primary/10 hover:text-primary"
            aria-label="收录进笔记"
            title="收录进笔记"
          >
            <BookmarkPlus className="h-4 w-4" />
          </button>
          {review.mine && (
            <>
              <button
                onClick={() => navigate(`/book/${review.bookId}/reviews/${review.id}/edit`)}
                className="flex h-9 w-9 items-center justify-center rounded-xl text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                aria-label="编辑"
              >
                <Pencil className="h-4 w-4" />
              </button>
              <button
                onClick={() => setConfirmOpen(true)}
                className="flex h-9 w-9 items-center justify-center rounded-xl text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive"
                aria-label="删除"
              >
                <Trash2 className="h-4 w-4" />
              </button>
            </>
          )}
        </div>
      </header>

      <div className="flex-1 overflow-y-auto overscroll-contain px-4 py-4 pb-20 md:pb-8">
        <div className="mx-auto w-full max-w-2xl">
          <button
            onClick={() => navigate(`/book/${review.bookId}`)}
            className="mb-4 flex w-full items-center gap-3 rounded-2xl border border-border/50 bg-card p-3 text-left transition-colors hover:border-border"
          >
            {review.bookCoverUrl ? (
              <img src={review.bookCoverUrl} alt={review.bookTitle ?? ''} className="h-14 w-10 shrink-0 rounded-md object-cover" />
            ) : (
              <div className="h-14 w-10 shrink-0 rounded-md bg-muted" />
            )}
            <div className="min-w-0">
              <p className="truncate text-sm font-semibold">{review.bookTitle}</p>
              {review.bookAuthor && <p className="truncate text-xs text-muted-foreground">{review.bookAuthor}</p>}
            </div>
          </button>

          <div className="flex items-center gap-2.5">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center overflow-hidden rounded-full bg-primary/10 text-sm font-bold text-primary">
              {review.avatar ? (
                <img src={review.avatar} alt={review.nickname ?? ''} className="h-full w-full object-cover" />
              ) : (
                review.nickname?.[0] ?? 'U'
              )}
            </div>
            <div className="min-w-0">
              <p className="truncate text-sm font-semibold">{review.nickname ?? '匿名读者'}</p>
              <p className="text-xs text-muted-foreground">
                {formatRelativeTime(review.createdAt)}
                {review.progressPercent != null && ` · 读到 ${review.progressPercent}% 时写的`}
              </p>
            </div>
          </div>

          {review.rating != null && (
            <div className="mt-4 flex items-center gap-0.5">
              {[1, 2, 3, 4, 5].map((star) => (
                <Star
                  key={star}
                  className={cn('h-4 w-4', (review.rating ?? 0) >= star ? 'fill-warning text-warning' : 'text-muted-foreground/25')}
                />
              ))}
              <span className="ml-1.5 text-xs text-muted-foreground">{review.rating} 星</span>
            </div>
          )}

          {review.title && <h2 className="mt-3 text-xl font-bold leading-snug">{review.title}</h2>}

          <div className="relative mt-3">
            {showContent ? (
              <p className="whitespace-pre-wrap text-body leading-relaxed">{review.contentMd}</p>
            ) : (
              <div className="flex flex-col items-center gap-3 rounded-2xl bg-muted/40 py-12">
                <EyeOff className="h-6 w-6 text-muted-foreground" />
                <p className="text-sm text-muted-foreground">这篇书评含有剧透</p>
                <button
                  onClick={() => setRevealed(true)}
                  className="rounded-xl bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground"
                >
                  仍要查看
                </button>
              </div>
            )}
          </div>

          {review.updatedAt && review.updatedAt !== review.createdAt && (
            <p className="mt-6 text-xs text-muted-foreground">最后编辑于 {formatRelativeTime(review.updatedAt)}</p>
          )}

          {review.captureCount > 0 && (
            <p className="mt-2 flex items-center gap-1 text-xs text-muted-foreground">
              <BookmarkPlus className="h-3 w-3" />
              已被收录 {review.captureCount} 次
            </p>
          )}
        </div>
      </div>

      <AlertDialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除这篇书评？</AlertDialogTitle>
            <AlertDialogDescription>删除后无法恢复，确定要删除吗？</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => deleteMutation.mutate()}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              {deleteMutation.isPending ? '删除中...' : '删除'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <NoteCaptureSheet open={captureOpen} onOpenChange={setCaptureOpen} review={review} />
    </div>
  )
}
