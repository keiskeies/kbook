import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { Star, Sparkles, Tag, ChevronDown, ChevronUp } from 'lucide-react'
import { getBook, getMatchScores } from '@/api/book'
import type { Book } from '@/types/book'
import { parseFormatTags } from '@/types/book'
import BookCover from '@/components/book/BookCover'

function RatingBadgeCN({ rating }: { rating: number | undefined | null }) {
  if (rating == null || rating < 0) return null
  const r = Number(rating.toFixed(1))

  let colorClass = ''
  if (r >= 5.0) {
    colorClass = 'text-red-600 dark:text-red-400'
  } else if (r >= 4.5) {
    colorClass = 'text-orange-600 dark:text-orange-400'
  } else if (r >= 4.0) {
    colorClass = 'text-amber-600 dark:text-amber-400'
  } else if (r >= 3.0) {
    colorClass = 'text-emerald-600 dark:text-emerald-400'
  } else if (r >= 2.5) {
    colorClass = 'text-teal-600 dark:text-teal-400'
  } else {
    colorClass = 'text-slate-400 dark:text-slate-500'
  }

  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${colorClass}`}>
      <Star className="h-2.5 w-2.5" />
      评分：{r}
    </span>
  )
}

function MatchBadgeCN({ score }: { score: number | undefined | null }) {
  const pct = Math.round(Math.max(0, score ?? 0) * 100)

  let colorClass = ''
  if (pct >= 100) {
    colorClass = 'text-red-600 dark:text-red-400'
  } else if (pct >= 80) {
    colorClass = 'text-orange-600 dark:text-orange-400'
  } else if (pct >= 60) {
    colorClass = 'text-amber-600 dark:text-amber-400'
  } else if (pct >= 50) {
    colorClass = 'text-emerald-600 dark:text-emerald-400'
  } else if (pct >= 40) {
    colorClass = 'text-teal-600 dark:text-teal-400'
  } else {
    colorClass = 'text-slate-400 dark:text-slate-500'
  }

  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${colorClass}`}>
      <Sparkles className="h-2.5 w-2.5" />
      匹配度：{pct}%
    </span>
  )
}

function BookDescription({ description }: { description: string }) {
  const [expanded, setExpanded] = useState(false)
  const isLong = description.length > 80

  return (
    <div className="mt-2 border-t border-border/30 pt-2">
      <p
        className={`text-[11px] text-muted-foreground/70 leading-relaxed transition-all duration-200 ${
          expanded ? '' : 'line-clamp-2'
        }`}
      >
        {description}
      </p>
      {isLong && (
        <button
          onClick={(e) => {
            e.stopPropagation()
            setExpanded(!expanded)
          }}
          className="mt-1 flex items-center gap-0.5 text-[10px] text-primary/80 hover:text-primary font-medium"
        >
          {expanded ? (
            <>
              <ChevronUp className="h-3 w-3" />
              收起
            </>
          ) : (
            <>
              <ChevronDown className="h-3 w-3" />
              展开
            </>
          )}
        </button>
      )}
    </div>
  )
}

function fmtReadCount(n: number): string {
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万次阅读`
  return `${n}次阅读`
}

const bookCache = new Map<number, Book>()

export interface InlineBookCardData {
  bookId: number
  title: string
  author: string | null
  format: string
  rating: number
  readCount: number
  description: string | null
  matchScore?: number
}

export default function InlineBookCard({ book }: { book: InlineBookCardData }) {
  const navigate = useNavigate()
  const [bookDetail, setBookDetail] = useState<Book | null>(null)
  const [loading, setLoading] = useState(false)
  const [matchScore, setMatchScore] = useState<number | undefined>(book.matchScore)
  const fetchedRef = useRef(false)

  const tags = bookDetail?.formatTags ? parseFormatTags(bookDetail.formatTags) : []

  useEffect(() => {
    if (fetchedRef.current) return
    fetchedRef.current = true

    const cached = bookCache.get(book.bookId)
    if (cached) {
      setBookDetail(cached)
      return
    }

    setLoading(true)
    getBook(book.bookId)
      .then((data) => {
        const b = data as unknown as Book
        bookCache.set(book.bookId, b)
        setBookDetail(b)
      })
      .catch(() => {
        setBookDetail(null)
      })
      .finally(() => {
        setLoading(false)
      })

    if (book.matchScore === undefined) {
      getMatchScores([book.bookId])
        .then((res) => {
          const data = res as unknown as Record<string, number>
          if (data && typeof data[book.bookId] === 'number') {
            setMatchScore(Math.round(data[book.bookId] * 100))
          }
        })
        .catch(() => {})
    }
  }, [book.bookId, book.matchScore])

  const displayTitle = bookDetail?.title || book.title
  const displayAuthor = bookDetail?.author || book.author
  const displayRating = bookDetail?.rating ?? book.rating
  const displayReadCount = bookDetail?.readCount ?? book.readCount
  const displayCoverUrl = bookDetail?.coverUrl || null
  const displayFormat = bookDetail?.format || book.format
  const displayDescription = bookDetail?.description || book.description

  const handleClick = () => {
    navigate(`/book/${book.bookId}`)
  }

  if (loading) {
    return (
      <div className="my-2 rounded-2xl bg-card p-3 shadow-lg shadow-black/5 dark:shadow-black/60 border border-border/50">
        <div className="flex gap-3">
          <div className="flex-1 min-w-0">
            <div className="flex gap-3">
              <div className="h-20 w-14 flex-shrink-0 rounded-lg bg-muted animate-pulse" />
              <div className="flex-1 space-y-2">
                <div className="h-4 w-3/4 rounded bg-muted animate-pulse" />
                <div className="h-3 w-1/2 rounded bg-muted animate-pulse" />
                <div className="h-3 w-full rounded bg-muted animate-pulse" />
              </div>
            </div>
            <div className="mt-2 h-4 w-full rounded bg-muted animate-pulse" />
            <div className="mt-1 h-4 w-4/5 rounded bg-muted animate-pulse" />
          </div>
        </div>
      </div>
    )
  }

  return (
    <div
      onClick={handleClick}
      className="my-2 rounded-2xl bg-card p-3 shadow-lg shadow-black/5 dark:shadow-black/60 border border-border/50 cursor-pointer active:scale-[0.98] transition-all duration-150"
    >
      <div className="flex gap-3">
        <div className="flex-1 min-w-0">
          <div className="flex gap-3">
            <BookCover
              coverUrl={displayCoverUrl}
              title={displayTitle}
              author={displayAuthor}
              format={displayFormat}
              size="md"
              className="flex-shrink-0"
            />

            <div className="flex-1 min-w-0 flex flex-col justify-between">
              <div>
                <p className="truncate text-sm font-semibold">
                  {displayTitle}
                </p>
                <p className="mt-0.5 truncate text-xs text-muted-foreground">
                  {displayAuthor || '未知作者'}
                </p>
              </div>

              <div className="mt-1.5 flex items-center gap-1.5 flex-wrap">
                <RatingBadgeCN rating={displayRating} />
                <MatchBadgeCN score={matchScore !== undefined ? matchScore / 100 : undefined} />
                <span className="text-[11px] text-muted-foreground">
                  {fmtReadCount(displayReadCount)}
                </span>
              </div>

              {tags.length > 0 && (
                <div className="mt-1.5 flex items-center gap-1.5 overflow-x-auto scrollbar-hide">
                  {tags.slice(0, 3).map((t) => (
                    <span
                      key={t}
                      className="inline-flex items-center gap-0.5 rounded-md bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary flex-shrink-0"
                    >
                      <Tag className="h-2.5 w-2.5" />
                      {t}
                    </span>
                  ))}
                </div>
              )}
            </div>
          </div>

          {displayDescription && displayDescription !== '暂无' && (
            <BookDescription description={displayDescription} />
          )}
        </div>
      </div>
    </div>
  )
}
