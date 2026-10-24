import { useState } from 'react'
import { BookmarkPlus, Eye, EyeOff, Pencil, Star, Trash2 } from 'lucide-react'
import type { ReviewVO } from '@/api/review'
import { formatRelativeTime } from '@/utils/time'
import { cn } from '@/utils/cn'

interface ReviewCardProps {
  review: ReviewVO
  /** 跨书列表（我的书评）需要显示书名，书籍详情页内不需要 */
  showBook?: boolean
  onClick?: () => void
  onEdit?: () => void
  onDelete?: () => void
  onCapture?: () => void
}

export function ReviewCard({ review, showBook, onClick, onEdit, onDelete, onCapture }: ReviewCardProps) {
  const [revealed, setRevealed] = useState(!review.isSpoiler)

  return (
    <article
      onClick={onClick}
      className="rounded-2xl border border-border/50 bg-card p-4 transition-colors hover:border-border"
    >
      <header className="flex items-center gap-2.5">
        <div className="flex h-9 w-9 shrink-0 items-center justify-center overflow-hidden rounded-full bg-primary/10 text-sm font-bold text-primary">
          {review.avatar ? (
            <img src={review.avatar} alt={review.nickname ?? ''} className="h-full w-full object-cover" />
          ) : (
            review.nickname?.[0] ?? 'U'
          )}
        </div>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-semibold">{review.nickname ?? '匿名读者'}</p>
          <p className="truncate text-xs text-muted-foreground">
            {formatRelativeTime(review.createdAt)}
            {review.progressPercent != null && (
              <span className="ml-1.5 rounded bg-muted px-1.5 py-0.5 font-medium">
                读到 {review.progressPercent}%
              </span>
            )}
            {review.captureCount > 0 && (
              <span className="ml-1.5 inline-flex items-center gap-0.5 text-muted-foreground/80">
                <BookmarkPlus className="h-3 w-3" />
                {review.captureCount}
              </span>
            )}
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-1">
          <button
            onClick={(e) => { e.stopPropagation(); onCapture?.() }}
            className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-primary/10 hover:text-primary"
            aria-label="收录进笔记"
            title="收录进笔记"
          >
            <BookmarkPlus className="h-4 w-4" />
          </button>
          {review.mine && (
            <>
              <button
                onClick={(e) => { e.stopPropagation(); onEdit?.() }}
                className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                aria-label="编辑书评"
              >
                <Pencil className="h-4 w-4" />
              </button>
              <button
                onClick={(e) => { e.stopPropagation(); onDelete?.() }}
                className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive"
                aria-label="删除书评"
              >
                <Trash2 className="h-4 w-4" />
              </button>
            </>
          )}
        </div>
      </header>

      {showBook && review.bookTitle && (
        <p className="mt-2.5 truncate text-xs text-muted-foreground">《{review.bookTitle}》{review.bookAuthor ? ` · ${review.bookAuthor}` : ''}</p>
      )}

      {review.rating != null && (
        <div className="mt-2.5 flex items-center gap-0.5">
          {[1, 2, 3, 4, 5].map((star) => (
            <Star
              key={star}
              className={cn('h-3.5 w-3.5', (review.rating ?? 0) >= star ? 'fill-warning text-warning' : 'text-muted-foreground/25')}
            />
          ))}
        </div>
      )}

      {review.title && <h3 className="mt-2 text-base font-bold leading-snug">{review.title}</h3>}

      <div className="relative mt-1.5">
        <p className={cn('whitespace-pre-wrap text-sm leading-relaxed text-muted-foreground line-clamp-4', !revealed && 'select-none blur-[5px]')}>
          {review.contentMd}
        </p>
        {!revealed && (
          <button
            onClick={(e) => { e.stopPropagation(); setRevealed(true) }}
            className="absolute inset-0 flex items-center justify-center gap-1.5 rounded-lg bg-muted/60 text-xs font-medium text-foreground"
          >
            <EyeOff className="h-3.5 w-3.5" />
            含剧透，点击显示
          </button>
        )}
      </div>

      {review.isSpoiler && revealed && (
        <button
          onClick={(e) => { e.stopPropagation(); setRevealed(false) }}
          className="mt-1.5 flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
        >
          <Eye className="h-3 w-3" />
          收起剧透
        </button>
      )}
    </article>
  )
}
