import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { Star, BookOpen } from 'lucide-react'
import { getBook } from '@/api/book'
import type { Book } from '@/types/book'
import { parseFormatTags } from '@/types/book'

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
  const fetchedRef = useRef(false)

  // 解析标签
  const tags = bookDetail?.formatTags ? parseFormatTags(bookDetail.formatTags) : []

  // 推荐理由 / 匹配度
  const reason = book.description && book.description !== '暂无'
    ? book.description
    : null
  const isMatchScore = reason && /^匹配度/.test(reason)

  useEffect(() => {
    if (fetchedRef.current) return
    fetchedRef.current = true

    // 先查缓存
    const cached = bookCache.get(book.bookId)
    if (cached) {
      setBookDetail(cached)
      return
    }

    // 从接口获取
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
  }, [book.bookId])

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

          {/* 评分 / 匹配度 / 阅读量 */}
          <div className="mt-1.5 flex items-center gap-2 flex-wrap">
            {displayRating > 0 && (
              <span className="inline-flex items-center gap-0.5 text-[11px] font-medium text-amber-600 dark:text-amber-400">
                <Star className="h-3 w-3 fill-amber-400 text-amber-400" />
                {displayRating.toFixed(1)}
              </span>
            )}
            {isMatchScore && reason && (
              <span className="inline-flex items-center gap-0.5 rounded bg-primary/10 px-1.5 py-0.5 text-[11px] font-medium text-primary">
                ✨ {reason}
              </span>
            )}
            {displayReadCount > 0 && (
              <span className="text-[11px] text-muted-foreground">
                {displayReadCount >= 10000
                  ? `${(displayReadCount / 10000).toFixed(1)}万次阅读`
                  : `${displayReadCount}次阅读`}
              </span>
            )}
            {bookDetail?.format && (
              <span className="inline-flex items-center gap-0.5 rounded bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">
                {bookDetail.format}
              </span>
            )}
          </div>

          {/* 推荐理由 */}
          {reason && !isMatchScore && (
            <p className="mt-1.5 text-[11px] text-muted-foreground leading-relaxed">
              {reason}
            </p>
          )}
        </div>

        {/* 箭头 */}
        <div className="flex-shrink-0 self-center text-muted-foreground/40">
          <svg className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
          </svg>
        </div>
      </div>

      {/* 底部标签栏：全宽 */}
      {tags.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-2 border-t border-border/30 pt-2">
          {tags.map((tag) => (
            <span key={tag} className="rounded bg-primary/10 px-2 py-0.5 text-[10px] text-primary">
              {tag}
            </span>
          ))}
        </div>
      )}
    </div>
  )
}
