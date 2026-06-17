import { useState } from 'react'
import { Tag, Clock } from 'lucide-react'
import DOMPurify from 'dompurify'
import { parseFormatTags } from '@/types/book'
import BookCover from './BookCover'
import { BookActionButtons } from './BookActionButtons'
import { ReadingStatusButtons } from './ReadingStatusButtons'

interface BookCardProps {
  book: { id: number | string; title?: string; author?: string | null; coverUrl?: string | null; format?: string; rating?: number | null; matchScore?: number; readCount?: number | null; formatTags?: string | null; description?: string | null }
  onClick?: () => void
  highlight?: (text: string) => string
  readingStatus?: string | null
  onStatusChange?: (status: string) => void
  activeTag?: string
  lastReadAt?: string
  variant?: 'card' | 'list'
}

function fmtReadCount(n: number): string {
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万次阅读`
  return `${n}次阅读`
}

function RatingBadge({ rating }: { rating: number | undefined | null }) {
  if (rating == null || rating < 0) return null
  const r = Number(rating.toFixed(1))
  let colorClass = ''
  if (r >= 5.0) colorClass = 'text-danger'
  else if (r >= 4.5) colorClass = 'text-warning'
  else if (r >= 4.0) colorClass = 'text-warning'
  else if (r >= 3.0) colorClass = 'text-success'
  else if (r >= 2.5) colorClass = 'text-success'
  else colorClass = 'text-muted-foreground'
  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-xs font-semibold ${colorClass}`}>
      <svg className="h-2.5 w-2.5" viewBox="0 0 20 20" fill="currentColor">
        <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
      </svg>
      {r}
    </span>
  )
}

function MatchBadge({ score }: { score: number | undefined | null }) {
  const pct = Math.round(Math.max(0, score ?? 0) * 100)
  let colorClass = ''
  if (pct >= 100) colorClass = 'text-danger'
  else if (pct >= 80) colorClass = 'text-warning'
  else if (pct >= 60) colorClass = 'text-warning'
  else if (pct >= 50) colorClass = 'text-success'
  else if (pct >= 40) colorClass = 'text-success'
  else colorClass = 'text-muted-foreground'
  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-xs font-semibold ${colorClass}`}>
      <svg className="h-2.5 w-2.5" viewBox="0 0 20 20" fill="currentColor">
        <path fillRule="evenodd" d="M5 2a1 1 0 011 1v1h1a1 1 0 010 2H6v1a1 1 0 01-2 0V6H3a1 1 0 010-2h1V3a1 1 0 011-1zm0 10a1 1 0 011 1v1h1a1 1 0 110 2H6v1a1 1 0 11-2 0v-1H3a1 1 0 110-2h1v-1a1 1 0 011-1zM12 2a1 1 0 01.967.744L14.146 7.2 17.5 9.134a1 1 0 010 1.732l-3.354 1.935-1.18 4.455a1 1 0 01-1.933 0L9.854 12.8 6.5 10.866a1 1 0 010-1.732l3.354-1.935 1.18-4.455A1 1 0 0112 2z" clipRule="evenodd" />
      </svg>
      {pct}%
    </span>
  )
}

function formatTimeAgo(dateStr: string): string {
  const date = new Date(dateStr)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMin = Math.floor(diffMs / 60000)
  if (diffMin < 1) return '刚刚'
  if (diffMin < 60) return `${diffMin}分钟前`
  const diffHr = Math.floor(diffMin / 60)
  if (diffHr < 24) return `${diffHr}小时前`
  const diffDay = Math.floor(diffHr / 24)
  if (diffDay < 30) return `${diffDay}天前`
  return `${Math.floor(diffDay / 30)}个月前`
}

export function BookCard({ book, onClick, highlight, readingStatus, onStatusChange, activeTag, lastReadAt, variant = 'card' }: BookCardProps) {
  const isList = variant === 'list'
  const [expandedDesc, setExpandedDesc] = useState(false)
  const rawTags = parseFormatTags(book.formatTags || '')
  const desc = book.description

  // 去重，如果有 activeTag 则将其移到第一个位置
  const tags = (() => {
    const unique = [...new Set(rawTags)]
    if (!activeTag) return unique
    const matched = unique.filter(t => t === activeTag)
    const rest = unique.filter(t => t !== activeTag)
    return [...matched, ...rest]
  })()

  const toggleDesc = (e: React.MouseEvent) => {
    e.stopPropagation()
    setExpandedDesc(!expandedDesc)
  }

  const renderTitle = () => {
    const title = book.title || ''
    if (highlight) {
      return <span dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(highlight(title), { ADD_TAGS: ['mark'] }) }} />
    }
    return title || '未知书名'
  }

  const renderAuthor = () => {
    const author = book.author || '未知作者'
    if (highlight && book.author) {
      return <span dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(highlight(book.author), { ADD_TAGS: ['mark'] }) }} />
    }
    return author
  }

  return (
    <div
      className={`cursor-pointer btn-press ${
        isList
          ? 'rounded-xl bg-transparent hover:bg-muted/50 border-b border-border/30 last:border-b-0'
          : 'rounded-2xl bg-card shadow-sm border border-border/50'
      }`}
      onClick={onClick}
    >
      <div className={`flex gap-3 p-3 pb-2 ${isList ? 'pb-3' : ''}`}>
        <BookCover
          coverUrl={book.coverUrl || null}
          title={book.title || ''}
          author={book.author ?? undefined}
          format={book.format}
          size="md"
          className="flex-shrink-0 h-24 w-16"
        />
        <div className="flex-1 min-w-0 flex flex-col justify-between">
          <div>
            <p className="truncate text-sm font-semibold">{renderTitle()}</p>
            <p className="mt-0.5 truncate text-xs text-muted-foreground">
              {renderAuthor()}
            </p>
            {tags.length > 0 && (
              <div className="mt-1.5 flex items-center gap-1 flex-wrap">
                {tags.slice(0, 3).map((tag) => (
                  <span
                    key={tag}
                    className="inline-flex items-center gap-0.5 rounded-md bg-brand-100 px-1.5 py-0.5 text-xs font-medium text-brand-500"
                  >
                    <Tag className="h-2.5 w-2.5" />
                    {tag}
                  </span>
                ))}
              </div>
            )}
          </div>
          <div className="mt-1.5 flex items-center gap-2 flex-wrap">
            <RatingBadge rating={book.rating} />
            {book.matchScore != null && <MatchBadge score={book.matchScore} />}
            {book.readCount != null && book.readCount > 0 && (
              <span className="text-xs text-muted-foreground">
                {fmtReadCount(book.readCount)}
              </span>
            )}
          </div>
        </div>
      </div>

      {desc && (
        <button
          onClick={toggleDesc}
          className={`w-full px-3 pb-2.5 pt-1.5 text-left ${isList ? '' : 'border-t border-border/30'}`}
        >
          <p
            className={`text-xs text-foreground/70 leading-snug transition-all duration-200 ${
              expandedDesc ? '' : 'line-clamp-2'
            }`}
          >
            {desc}
          </p>
          <span className="mt-1 block text-xs text-brand-400 hover:text-brand-500 transition-colors">
            {expandedDesc ? '收起' : '展开'}
          </span>
        </button>
      )}

      {readingStatus != null && onStatusChange && (
        <div className={`px-3 py-2 ${isList ? '' : 'border-t border-border/30'}`}>
          <ReadingStatusButtons
            currentStatus={readingStatus}
            onStatusChange={(status) => {
              onStatusChange(status)
            }}
          />
        </div>
      )}

      <div className={`flex items-center justify-between px-3 py-2 ${isList ? '' : 'border-t border-border/30'}`}>
        {lastReadAt && (
          <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
            <Clock className="h-3 w-3" />
            {formatTimeAgo(lastReadAt)}
          </span>
        )}
        <div className="flex items-center gap-1.5 ml-auto">
          <BookActionButtons bookId={book.id} />
        </div>
      </div>
    </div>
  )
}