import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Star, BookOpen, FileText } from 'lucide-react'

/** 预定义渐变色 */
const GRADIENTS = [
  'from-rose-400 to-pink-500',
  'from-violet-500 to-purple-600',
  'from-blue-400 to-indigo-500',
  'from-cyan-400 to-teal-500',
  'from-emerald-400 to-green-500',
  'from-amber-400 to-orange-500',
]

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
 * 展示封面（或渐变色占位）、书名、作者、评分，点击跳转图书详情
 */
export default function InlineBookCard({ book }: { book: InlineBookCardData }) {
  const navigate = useNavigate()
  const gradient = GRADIENTS[hashStr(book.title) % GRADIENTS.length]
  const [imgError, setImgError] = useState(false)

  // 尝试构造封面 URL（基于 bookId 推测，实际封面由后端 /api/books/cover/ 返回）
  const coverUrl = `/api/books/cover/book_${book.bookId}_cover.jpg`

  const handleClick = () => {
    navigate(`/book/${book.bookId}`)
  }

  // 推荐理由 / 匹配度 / 推荐原因
  const reason = book.description && book.description !== '暂无'
    ? book.description
    : null
  const isMatchScore = reason && /^匹配度/.test(reason)

  return (
    <div
      onClick={handleClick}
      className="my-2 flex cursor-pointer items-start gap-3 rounded-xl border border-border/60 bg-card/60 p-3 shadow-sm transition-colors hover:bg-card active:bg-muted"
    >
      {/* 封面 */}
      <div className="h-16 w-11 flex-shrink-0 overflow-hidden rounded-lg bg-muted shadow-sm">
        {!imgError ? (
          <img
            src={coverUrl}
            alt={book.title}
            className="h-full w-full object-cover"
            loading="lazy"
            onError={() => setImgError(true)}
          />
        ) : (
          <div
            className={`flex h-full w-full items-center justify-center bg-gradient-to-br ${gradient} p-1`}
          >
            <BookOpen className="h-4 w-4 text-white/60" />
          </div>
        )}
      </div>

      {/* 信息 */}
      <div className="flex-1 min-w-0">
        <p className="truncate text-sm font-semibold text-foreground">
          《{book.title}》
        </p>
        {book.author && (
          <p className="mt-0.5 truncate text-xs text-muted-foreground">
            {book.author}
          </p>
        )}
        <div className="mt-1 flex items-center gap-2 flex-wrap">
          {book.rating > 0 && (
            <span className="inline-flex items-center gap-0.5 text-xs font-medium text-amber-600 dark:text-amber-400">
              <Star className="h-3 w-3 fill-amber-400 text-amber-400" />
              {book.rating.toFixed(1)}
            </span>
          )}
          {book.format && (
            <span className="inline-flex items-center gap-0.5 rounded bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">
              <FileText className="h-2.5 w-2.5" />
              {book.format}
            </span>
          )}
          {book.readCount > 0 && (
            <span className="text-[10px] text-muted-foreground">
              {book.readCount >= 10000
                ? `${(book.readCount / 10000).toFixed(1)}万次阅读`
                : `${book.readCount}次阅读`}
            </span>
          )}
          {isMatchScore && reason && (
            <span className="inline-flex items-center gap-0.5 rounded bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary">
              ✨ {reason}
            </span>
          )}
        </div>
        {reason && !isMatchScore && (
          <p className="mt-1 text-[11px] text-muted-foreground leading-relaxed">
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
  )
}
