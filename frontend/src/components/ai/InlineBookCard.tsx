import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { Star, BookOpen, Sparkles, Tag } from 'lucide-react'
import { getBook, getMatchScores } from '@/api/book'
import type { Book } from '@/types/book'
import { parseFormatTags } from '@/types/book'

/** 评分徽章 — 与榜单页面一致 */
function RatingBadge({ rating }: { rating: number | undefined | null }) {
  if (rating == null || rating < 0) return null
  const r = Number(rating.toFixed(1))

  let colorClass = ''
  let bgClass = ''
  if (r >= 5.0) {
    colorClass = 'text-red-600 dark:text-red-400'
    bgClass = 'bg-red-500/15'
  } else if (r >= 4.5) {
    colorClass = 'text-orange-600 dark:text-orange-400'
    bgClass = 'bg-orange-500/15'
  } else if (r >= 4.0) {
    colorClass = 'text-amber-600 dark:text-amber-400'
    bgClass = 'bg-amber-500/10'
  } else if (r >= 3.0) {
    colorClass = 'text-emerald-600 dark:text-emerald-400'
    bgClass = 'bg-emerald-500/10'
  } else if (r >= 2.5) {
    colorClass = 'text-teal-600 dark:text-teal-400'
    bgClass = 'bg-teal-500/10'
  } else {
    colorClass = 'text-slate-400 dark:text-slate-500'
    bgClass = 'bg-slate-400/10'
  }

  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${colorClass} ${bgClass}`}>
      <Star className="h-2.5 w-2.5" />
      {r}
    </span>
  )
}

/** 匹配度徽章 — 与榜单页面一致 */
function MatchBadge({ score }: { score: number | undefined | null }) {
  const pct = Math.round(Math.max(0, score ?? 0) * 100)

  let colorClass = ''
  let bgClass = ''
  if (pct >= 100) {
    colorClass = 'text-red-600 dark:text-red-400'
    bgClass = 'bg-red-500/15'
  } else if (pct >= 80) {
    colorClass = 'text-orange-600 dark:text-orange-400'
    bgClass = 'bg-orange-500/15'
  } else if (pct >= 60) {
    colorClass = 'text-amber-600 dark:text-amber-400'
    bgClass = 'bg-amber-500/10'
  } else if (pct >= 50) {
    colorClass = 'text-emerald-600 dark:text-emerald-400'
    bgClass = 'bg-emerald-500/10'
  } else if (pct >= 40) {
    colorClass = 'text-teal-600 dark:text-teal-400'
    bgClass = 'bg-teal-500/10'
  } else {
    colorClass = 'text-slate-400 dark:text-slate-500'
    bgClass = 'bg-slate-400/10'
  }

  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${colorClass} ${bgClass}`}>
      <Sparkles className="h-2.5 w-2.5" />
      {pct}%
    </span>
  )
}

/** 预定义渐变色 */
const GRADIENTS = [
  'from-rose-400 to-pink-500',
  'from-violet-500 to-purple-600',
  'from-blue-400 to-indigo-500',
  'from-cyan-400 to-teal-500',
  'from-emerald-400 to-green-500',
  'from-amber-400 to-orange-500',
]

/** 全局图书详情缓存，避免同一会话中重复请求 */
const bookCache = new Map<number, Book>()

function hashStr(s: string): number {
  let h = 0
  for (let i = 0; i < s.length; i++) {
    h = ((h << 5) - h + s.charCodeAt(i)) | 0
  }
  return Math.abs(h)
}

export interface InlineBookCardData {
  bookId: number
  title: string
  author: string | null
  format: string
  rating: number
  readCount: number
  description: string | null
  /** 匹配度百分比，如 85 */
  matchScore?: number
}

/**
 * AI 对话中的内嵌图书卡片
 * 根据 bookId 从接口获取图书详情，展示封面、标题、作者、评分、阅读量、匹配度
 */
export default function InlineBookCard({ book }: { book: InlineBookCardData }) {
  const navigate = useNavigate()
  const gradient = GRADIENTS[hashStr(book.title) % GRADIENTS.length]
  const [bookDetail, setBookDetail] = useState<Book | null>(null)
  const [loading, setLoading] = useState(false)
  const [imgError, setImgError] = useState(false)
  const [matchScore, setMatchScore] = useState<number | undefined>(book.matchScore)
  const fetchedRef = useRef(false)

  // 解析标签
  const tags = bookDetail?.formatTags ? parseFormatTags(bookDetail.formatTags) : []

  // 推荐理由 / 匹配度
  const reason = book.description && book.description !== '暂无'
    ? book.description
    : null

  useEffect(() => {
    if (fetchedRef.current) return
    fetchedRef.current = true

    // 先查缓存
    const cached = bookCache.get(book.bookId)
    if (cached) {
      setBookDetail(cached)
      return
    }

    // 从接口获取图书详情
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

    // 获取匹配度（如果传入的数据中没有）
    if (book.matchScore === undefined) {
      getMatchScores([book.bookId])
        .then((res) => {
          // request.ts 拦截器已 unwrap Result，res 直接是 Map<bookId, score>
          const data = res as unknown as Record<string, number>
          if (data && typeof data[book.bookId] === 'number') {
            setMatchScore(Math.round(data[book.bookId] * 100))
          }
        })
        .catch(() => {})
    }
  }, [book.bookId, book.matchScore])

  // 优先使用接口返回的数据，回退到传入的数据
  const displayTitle = bookDetail?.title || book.title
  const displayAuthor = bookDetail?.author || book.author
  const displayRating = bookDetail?.rating ?? book.rating
  const displayReadCount = bookDetail?.readCount ?? book.readCount
  const displayCoverUrl = bookDetail?.coverUrl || null

  const handleClick = () => {
    navigate(`/book/${book.bookId}`)
  }

  // 骨架屏
  if (loading) {
    return (
      <div className="my-2 flex flex-col rounded-xl border border-border/60 bg-card/60 p-3 shadow-sm">
        {/* 上半部分：封面 + 信息 */}
        <div className="flex items-start gap-3">
          <div className="h-24 w-[72px] flex-shrink-0 overflow-hidden rounded-lg bg-muted/80 animate-pulse" />
          <div className="flex-1 min-w-0 space-y-2 py-0.5">
            <div className="space-y-1.5">
              <div className="h-4 w-full rounded bg-muted/80 animate-pulse" />
              <div className="h-4 w-3/4 rounded bg-muted/80 animate-pulse" />
            </div>
            <div className="h-3 w-1/2 rounded bg-muted/80 animate-pulse" />
            <div className="flex gap-2">
              <div className="h-3 w-8 rounded bg-muted/80 animate-pulse" />
              <div className="h-3 w-16 rounded bg-muted/80 animate-pulse" />
            </div>
          </div>
          <div className="h-4 w-4 flex-shrink-0 self-center rounded bg-muted/80 animate-pulse" />
        </div>
        {/* 底部标签骨架 */}
        <div className="mt-3 flex gap-2 border-t border-border/30 pt-2">
          <div className="h-4 w-10 rounded bg-muted/80 animate-pulse" />
          <div className="h-4 w-8 rounded bg-muted/80 animate-pulse" />
          <div className="h-4 w-12 rounded bg-muted/80 animate-pulse" />
        </div>
      </div>
    )
  }

  return (
    <div
      onClick={handleClick}
      className="my-2 flex flex-col cursor-pointer rounded-xl border border-border/60 bg-card/60 p-3 shadow-sm transition-colors hover:bg-card active:bg-muted"
    >
      {/* 上半部分：封面 + 信息 + 箭头 */}
      <div className="flex items-start gap-3">
        {/* 封面 */}
        <div className="h-24 w-[72px] flex-shrink-0 overflow-hidden rounded-lg bg-muted shadow-sm">
          {displayCoverUrl && !imgError ? (
            <img
              src={displayCoverUrl}
              alt={displayTitle}
              className="h-full w-full object-cover"
              loading="lazy"
              onError={() => setImgError(true)}
            />
          ) : (
            <div
              className={`flex h-full w-full items-center justify-center bg-gradient-to-br ${gradient} p-2`}
            >
              <BookOpen className="h-6 w-6 text-white/60" />
            </div>
          )}
        </div>

        {/* 信息 */}
        <div className="flex-1 min-w-0">
          {/* 书名：固定两行高度 */}
          <div className="text-xs font-semibold text-foreground leading-5 line-clamp-2" style={{ minHeight: '40px' }}>
            {displayTitle}
          </div>

          {/* 作者 */}
          <p className="mt-1 truncate text-[11px] text-muted-foreground">
            {displayAuthor || '未知作者'}
          </p>

          {/* 评分 / 匹配度 / 阅读量 — 与榜单页面一致 */}
          <div className="mt-1.5 flex items-center gap-2 flex-wrap">
            <RatingBadge rating={displayRating} />
            <MatchBadge score={matchScore !== undefined ? matchScore / 100 : undefined} />
            {displayReadCount != null && displayReadCount >= 0 && (
              <span className="inline-flex items-center gap-0.5 text-[10px] text-muted-foreground">
                <BookOpen className="h-2.5 w-2.5 text-muted-foreground/60" />
                {displayReadCount >= 10000
                  ? `${(displayReadCount / 10000).toFixed(1)}万次阅读`
                  : `${displayReadCount}次阅读`}
              </span>
            )}
          </div>

          {/* 推荐理由 */}
          {reason && (
            <p className="mt-1.5 text-[11px] text-muted-foreground leading-relaxed">
              {reason}
            </p>
          )}
        </div>

      </div>

      {/* 底部标签栏：全宽 — 与榜单页面一致 */}
      {tags.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-1.5 border-t border-border/30 pt-2">
          {tags.slice(0, 3).map((tag) => (
            <span key={tag} className="inline-flex items-center gap-0.5 rounded-md bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary">
              <Tag className="h-2.5 w-2.5" />
              {tag}
            </span>
          ))}
        </div>
      )}
    </div>
  )
}
