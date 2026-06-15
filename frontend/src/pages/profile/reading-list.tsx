import { useState, useCallback, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useGoBack } from '@/hooks/useGoBack'
import { ArrowLeft, BookOpen, Loader2, Star, Tag, ChevronDown, ChevronUp, Clock } from 'lucide-react'
import { getReadingHistory } from '@/api/progress'
import BookCover from '@/components/book/BookCover'

interface ReadingListItem {
  bookId: number
  title: string
  author: string | null
  coverUrl: string | null
  format: string | null
  progress: number
  updatedAt: string
  fileSize?: number
  rating?: number
  readCount?: number
  description?: string
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

function RatingBadgeCN({ rating }: { rating: number | undefined | null }) {
  if (rating == null || rating < 0) return null
  const r = Number(rating.toFixed(1))

  let colorClass = ''
  if (r >= 5.0) {
    colorClass = 'text-danger dark:text-danger'
  } else if (r >= 4.5) {
    colorClass = 'text-warning dark:text-warning'
  } else if (r >= 4.0) {
    colorClass = 'text-warning dark:text-warning'
  } else if (r >= 3.0) {
    colorClass = 'text-success dark:text-success'
  } else if (r >= 2.5) {
    colorClass = 'text-success dark:text-success'
  } else {
    colorClass = 'text-muted-foreground dark:text-muted-foreground'
  }

  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-xs font-semibold ${colorClass}`}>
      <Star className="h-2.5 w-2.5" />
      {r}
    </span>
  )
}

function fmtReadCount(n: number): string {
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万次阅读`
  return `${n}次阅读`
}

function fmtFileSize(bytes: number | null | undefined): string {
  if (bytes == null || bytes <= 0) return ''
  if (bytes < 1024) return `${bytes}B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)}MB`
}

function BookDescription({ description }: { description: string }) {
  const [expanded, setExpanded] = useState(false)
  const isLong = description.length > 80

  return (
    <div className="mt-2 border-t border-border/30 pt-2">
      <p
        className={`text-xs text-muted-foreground/70 leading-relaxed transition-all duration-200 ${
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
          className="mt-1 flex items-center gap-0.5 text-xs text-primary/80 hover:text-primary font-medium"
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

export default function ReadingListPage() {
  const navigate = useNavigate()
  const goBack = useGoBack()
  const [items, setItems] = useState<ReadingListItem[]>([])
  const [loading, setLoading] = useState(true)
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(true)

  const loadItems = useCallback(async (p: number) => {
    setLoading(true)
    try {
      const res = await getReadingHistory(p, 20)
      const data = (res as any)?.data || (res as any)
      const list: ReadingListItem[] = (data?.list || data?.content || []).map((item: any) => ({
        bookId: item.bookId ?? item.book_id,
        title: item.title,
        author: item.author,
        coverUrl: item.coverUrl,
        format: item.format,
        progress: item.progress ?? 0,
        updatedAt: item.updatedAt,
        fileSize: item.fileSize,
        rating: item.rating,
        readCount: item.readCount,
        description: item.description,
      }))
      setItems(prev => p === 0 ? list : [...prev, ...list])
      setHasMore(list.length >= 20)
    } catch {
      // ignore
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    setPage(0)
    loadItems(0)
  }, [loadItems])

  return (
    <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col overflow-hidden bg-background page-enter">
      {/* Header */}
      <header className="shrink-0 flex items-center gap-3 border-b border-border/50 bg-background/80 px-4 py-3 backdrop-blur-xl z-20">
        <button onClick={() => goBack()} className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl hover:bg-muted transition-colors">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="flex-1 truncate text-base font-bold">阅读记录</h1>
      </header>

      {/* Content */}
      <div className="flex-1 overflow-y-auto overscroll-contain px-4 py-4 pb-20 md:pb-4">
        {loading && items.length === 0 ? (
          <div className="flex items-center justify-center py-20">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        ) : items.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-20 text-muted-foreground">
            <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-muted mb-4">
              <BookOpen className="h-7 w-7 text-muted-foreground/50" />
            </div>
            <p className="text-sm">还没有阅读记录</p>
          </div>
        ) : (
          <div className="columns-1 sm:columns-2 lg:columns-3 gap-3 space-y-3">
            {items.map((book) => {
              const formatTag = book.format ? [book.format] : []
              return (
                <div
                  key={book.bookId}
                  className="rounded-2xl bg-card p-3 shadow-sm border border-border/50 cursor-pointer active:scale-[0.98] transition-all duration-150 break-inside-avoid"
                  onClick={() => navigate(`/book/${book.bookId}`)}
                >
                  <div className="flex gap-3">
                    {/* 封面 + 信息 + 简介 */}
                    <div className="flex-1 min-w-0">
                      <div className="flex gap-3">
                        {/* 封面 */}
                        <BookCover
                          coverUrl={book.coverUrl}
                          title={book.title}
                          author={book.author}
                          format={book.format ?? undefined}
                          size="md"
                          className="flex-shrink-0"
                        />

                        {/* 信息区 */}
                        <div className="flex-1 min-w-0 flex flex-col justify-between">
                          <div>
                            <p className="truncate text-sm font-semibold">{book.title}</p>
                            <p className="mt-0.5 truncate text-xs text-muted-foreground">
                              {book.author || '未知作者'}
                            </p>
                          </div>

                          {/* 评分 + 阅读量 + 文件大小 + 阅读时间 */}
                          <div className="mt-1.5 flex items-center gap-1.5 flex-wrap">
                            <RatingBadgeCN rating={book.rating} />
                            {book.readCount != null && book.readCount > 0 && (
                              <span className="text-xs text-muted-foreground">
                                {fmtReadCount(book.readCount)}
                              </span>
                            )}
                            {fmtFileSize(book.fileSize) && (
                              <span className="text-xs text-muted-foreground">
                                {fmtFileSize(book.fileSize)}
                              </span>
                            )}
                            <span className="inline-flex items-center gap-0.5 text-xs text-muted-foreground">
                              <Clock className="h-2.5 w-2.5" />
                              {formatTimeAgo(book.updatedAt)}
                            </span>
                          </div>

                          {/* 标签 */}
                          {formatTag.length > 0 && (
                            <div className="mt-1.5 flex items-center gap-1.5 overflow-x-auto scrollbar-hide">
                              {formatTag.map((t) => (
                                <span
                                  key={t}
                                  className="inline-flex items-center gap-0.5 rounded-md bg-primary/10 px-1.5 py-0.5 text-xs font-medium text-primary flex-shrink-0"
                                >
                                  <Tag className="h-2.5 w-2.5" />
                                  {t}
                                </span>
                              ))}
                            </div>
                          )}
                        </div>
                      </div>

                      {/* 简介 — 点击展开/收起 */}
                      {book.description && (
                        <BookDescription description={book.description} />
                      )}

                      {/* 快捷操作：AI问答 / 圆桌 / 辩论 */}
                      <div className="flex items-center gap-1.5 mt-2">
                        <button onClick={(e) => { e.stopPropagation(); navigate(`/book/${book.bookId}`) }} className="flex items-center gap-1 rounded-lg bg-primary/10 px-2 py-1 text-xs font-medium text-primary hover:bg-primary/20">
                          AI问答
                        </button>
                        <button onClick={(e) => { e.stopPropagation(); navigate(`/book/${book.bookId}/round-table`) }} className="flex items-center gap-1 rounded-lg bg-violet-500/10 px-2 py-1 text-xs font-medium text-violet-600 hover:bg-violet-500/20">
                          圆桌
                        </button>
                        <button onClick={(e) => { e.stopPropagation(); navigate(`/book/${book.bookId}/debate`) }} className="flex items-center gap-1 rounded-lg bg-brand-400/10 px-2 py-1 text-xs font-medium text-brand-500 hover:bg-brand-400/20">
                          辩论
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              )
            })}
            {hasMore && (
              <button
                onClick={() => { const next = page + 1; setPage(next); loadItems(next) }}
                className="w-full rounded-xl bg-muted py-2.5 text-sm font-medium text-muted-foreground hover:bg-muted/80 transition-colors"
              >
                加载更多
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
