import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Star } from 'lucide-react'
import { toast } from 'sonner'
import { createReview, getReview, updateReview } from '@/api/review'
import { getBook } from '@/api/book'
import { STORAGE_KEYS } from '@/constants'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { Switch } from '@/components/ui/switch'
import { useGoBack } from '@/hooks/useGoBack'
import { cn } from '@/utils/cn'

const MAX_CONTENT = 20000
const MAX_TITLE = 100

export default function ReviewEditorPage() {
  const { bookId, reviewId } = useParams<{ bookId: string; reviewId: string }>()
  const navigate = useNavigate()
  const goBack = useGoBack()
  const queryClient = useQueryClient()

  const id = Number(bookId)
  const isEdit = !!reviewId
  const draftKey = `${STORAGE_KEYS.REVIEW_DRAFT_PREFIX}${bookId}`

  const [rating, setRating] = useState<number | null>(null)
  const [hoverStar, setHoverStar] = useState(0)
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [isSpoiler, setIsSpoiler] = useState(false)

  const { data: book } = useQuery({
    queryKey: ['book', 'info', bookId],
    queryFn: () => getBook(id),
    enabled: !!bookId,
    staleTime: 5 * 60 * 1000,
  })

  const reviewQuery = useQuery({
    queryKey: ['review', 'edit', reviewId],
    queryFn: () => getReview(Number(reviewId)),
    enabled: isEdit,
  })

  useEffect(() => {
    const review = reviewQuery.data
    if (!review) return
    if (!review.mine) {
      toast.error('只能编辑自己的书评')
      navigate(`/book/${review.bookId}/reviews/${review.id}`, { replace: true })
      return
    }
    setRating(review.rating)
    setTitle(review.title ?? '')
    setContent(review.contentMd)
    setIsSpoiler(review.isSpoiler)
  }, [reviewQuery.data, navigate])

  // 新建模式：恢复本地草稿
  useEffect(() => {
    if (isEdit) return
    const draft = localStorage.getItem(draftKey)
    if (!draft) return
    try {
      const parsed = JSON.parse(draft) as { rating?: number | null; title?: string; content?: string; isSpoiler?: boolean }
      if (!parsed.content) return
      setRating(parsed.rating ?? null)
      setTitle(parsed.title ?? '')
      setContent(parsed.content)
      setIsSpoiler(!!parsed.isSpoiler)
      toast.info('已恢复未发布的草稿')
    } catch {
      localStorage.removeItem(draftKey)
    }
  }, [isEdit, draftKey])

  // 新建模式：自动存草稿
  useEffect(() => {
    if (isEdit) return
    if (!content.trim() && !title.trim()) {
      localStorage.removeItem(draftKey)
      return
    }
    localStorage.setItem(draftKey, JSON.stringify({ rating, title, content, isSpoiler }))
  }, [isEdit, draftKey, rating, title, content, isSpoiler])

  const submitMutation = useMutation({
    mutationFn: () => {
      const payload = { rating, title: title.trim() || null, contentMd: content, isSpoiler }
      return isEdit ? updateReview(Number(reviewId), payload) : createReview(id, payload)
    },
    onSuccess: (review) => {
      localStorage.removeItem(draftKey)
      queryClient.invalidateQueries({ queryKey: ['book', 'reviews', id] })
      queryClient.invalidateQueries({ queryKey: ['profile', 'reviews'] })
      queryClient.invalidateQueries({ queryKey: ['review'] })
      toast.success(isEdit ? '书评已更新' : '书评已发布')
      navigate(`/book/${review.bookId}/reviews/${review.id}`, { replace: true })
    },
    onError: (err: Error) => toast.error(err.message || '发布失败'),
  })

  const canSubmit = content.trim().length > 0 && !submitMutation.isPending

  const handleSubmit = () => {
    if (!canSubmit) {
      toast.error('先写点内容再发布')
      return
    }
    submitMutation.mutate()
  }

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
        <h1 className="flex-1 truncate text-base font-bold">{isEdit ? '编辑书评' : '写书评'}</h1>
        <Button size="sm" onClick={handleSubmit} disabled={!canSubmit}>
          {submitMutation.isPending ? '发布中...' : isEdit ? '保存' : '发布'}
        </Button>
      </header>

      <div className="flex-1 overflow-y-auto overscroll-contain px-4 py-4 pb-24 md:pb-8">
        <div className="mx-auto w-full max-w-2xl space-y-4">
          {book && (
            <div className="flex items-center gap-3 rounded-2xl border border-border/50 bg-card p-3">
              {book.coverUrl ? (
                <img src={book.coverUrl} alt={book.title} className="h-14 w-10 shrink-0 rounded-md object-cover" />
              ) : (
                <div className="h-14 w-10 shrink-0 rounded-md bg-muted" />
              )}
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold">{book.title}</p>
                {book.author && <p className="truncate text-xs text-muted-foreground">{book.author}</p>}
              </div>
            </div>
          )}

          <div className="rounded-2xl border border-border/50 bg-card p-4">
            <p className="mb-2 text-xs font-medium text-muted-foreground">这篇书评的评分（可不填）</p>
            <div className="flex items-center gap-1">
              {[1, 2, 3, 4, 5].map((star) => (
                <button
                  key={star}
                  onClick={() => setRating(rating === star ? null : star)}
                  onMouseEnter={() => setHoverStar(star)}
                  onMouseLeave={() => setHoverStar(0)}
                  className="transition-transform active:scale-90"
                  aria-label={`${star} 星`}
                >
                  <Star
                    className={cn(
                      'h-7 w-7 transition-colors',
                      (hoverStar || rating || 0) >= star ? 'fill-warning text-warning' : 'text-muted-foreground/30',
                    )}
                  />
                </button>
              ))}
              {(hoverStar || rating) && (
                <span className="ml-2 text-xs text-muted-foreground">{hoverStar || rating} 星</span>
              )}
            </div>
          </div>

          <input
            value={title}
            onChange={(e) => setTitle(e.target.value.slice(0, MAX_TITLE))}
            placeholder="给这篇书评起个标题（可不填）"
            maxLength={MAX_TITLE}
            className="w-full rounded-2xl border border-border/50 bg-card px-4 py-3 text-base font-semibold outline-none transition-colors placeholder:font-normal placeholder:text-muted-foreground/60 focus:border-primary/50"
          />

          <div className="rounded-2xl border border-border/50 bg-card p-4">
            <Textarea
              value={content}
              onChange={(e) => setContent(e.target.value.slice(0, MAX_CONTENT))}
              placeholder="这本书让你想到了什么？写作时的阅读进度会自动记录下来。"
              className="min-h-[280px] resize-y border-0 bg-transparent p-0 text-base leading-relaxed shadow-none focus-visible:ring-0"
            />
            <div className="mt-2 flex items-center justify-between border-t border-border/50 pt-2">
              <label className="flex cursor-pointer items-center gap-2 text-xs text-muted-foreground">
                <Switch checked={isSpoiler} onCheckedChange={setIsSpoiler} />
                含剧透
              </label>
              <span className="text-xs text-muted-foreground">{content.length} / {MAX_CONTENT}</span>
            </div>
          </div>

          {!isEdit && content.trim() && (
            <p className="text-center text-xs text-muted-foreground">草稿已自动保存在本机</p>
          )}
        </div>
      </div>
    </div>
  )
}
