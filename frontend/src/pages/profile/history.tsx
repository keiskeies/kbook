import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ArrowLeft, Clock, CheckCircle2, BookOpen } from 'lucide-react'
import { getUserProgresses } from '@/api/progress'
import { getBook } from '@/api/book'
import type { ReadingProgress } from '@/types/book'
import type { Book } from '@/types/book'
import { formatProgress } from '@/types/book'
import { formatRelativeTime } from '@/utils/time'
import BookCover from '@/components/book/BookCover'

interface HistoryItem {
  progress: ReadingProgress
  book: Book | null
}

export default function ReadingHistoryPage() {
  const navigate = useNavigate()
  const [items, setItems] = useState<HistoryItem[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    async function load() {
      try {
        const res = await getUserProgresses()
        const progresses = (res as any)?.data || (res as any) || []
        const list: ReadingProgress[] = Array.isArray(progresses) ? progresses : []

        // 批量获取图书信息
        const bookPromises = list.map((p) =>
          getBook(p.bookId).catch(() => null)
        )
        const bookResults = await Promise.all(bookPromises)

        const historyItems: HistoryItem[] = list.map((p, i) => ({
          progress: p,
          book: bookResults[i] ? (bookResults[i] as any)?.data || bookResults[i] as unknown as Book : null,
        }))

        setItems(historyItems)
      } catch {
        // ignore
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [])

  const isCompleted = (p: ReadingProgress) => p.progress >= 1.0

  // 计算读完和在读数量
  const completedCount = items.filter((item) => isCompleted(item.progress)).length
  const readingCount = items.length - completedCount

  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent" />
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-background page-enter">
      {/* 顶部导航 */}
      <header className="sticky top-0 z-10 flex items-center gap-3 border-b border-border/50 bg-background/80 px-4 py-3 backdrop-blur-xl">
        <button onClick={() => navigate(-1)} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted transition-colors">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="flex-1 text-base font-bold">阅读历史</h1>
        <span className="text-xs text-muted-foreground">{items.length} 本</span>
      </header>

      {items.length === 0 ? (
        <div className="flex h-60 flex-col items-center justify-center text-muted-foreground">
          <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-muted">
            <BookOpen className="h-8 w-8 text-muted-foreground/50" />
          </div>
          <p className="mt-4 text-sm">暂无阅读记录</p>
        </div>
      ) : (
        <div className="px-4 py-3 space-y-2.5">
          {items.map((item) => {
            const book = item.book
            const completed = isCompleted(item.progress)
            return (
              <div
                key={item.progress.id}
                className="flex items-center gap-3.5 rounded-2xl bg-card p-3.5 shadow-sm border border-border/50 active:scale-[0.98] transition-all duration-150 cursor-pointer"
                onClick={() => book && navigate(completed ? `/book/${book.id}` : `/reader/${book.id}`)}
              >
                {/* 封面 */}
                <BookCover coverUrl={book?.coverUrl ?? null} title={book?.title ?? '未知图书'} author={book?.author} size="sm" className="flex-shrink-0 shadow-sm" />

                {/* 信息 */}
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-semibold">{book?.title || '未知图书'}</p>
                  <p className="mt-0.5 text-xs text-muted-foreground">{book?.author || '未知作者'}</p>
                  <div className="mt-2 flex items-center gap-2">
                    {completed ? (
                      <span className="flex items-center gap-1 text-[10px] font-semibold text-emerald-500">
                        <CheckCircle2 className="h-3 w-3" />
                        已读完
                      </span>
                    ) : (
                      <>
                        <div className="h-1.5 flex-1 rounded-full bg-primary/10">
                          <div
                            className="h-full rounded-full bg-gradient-to-r from-primary to-primary/70 transition-all"
                            style={{ width: `${Math.round(item.progress.progress * 100)}%` }}
                          />
                        </div>
                        <span className="text-[10px] font-bold text-primary">{Math.round(item.progress.progress * 100)}%</span>
                      </>
                    )}
                  </div>
                </div>

                {/* 时间 */}
                <div className="flex flex-shrink-0 flex-col items-end gap-1">
                  <span className="flex items-center gap-1 text-[10px] text-muted-foreground">
                    <Clock className="h-2.5 w-2.5" />
                    {formatRelativeTime(item.progress.updatedAt)}
                  </span>
                  {book?.format && (
                    <span className="rounded-md bg-primary/8 px-1.5 py-0.5 text-[9px] font-medium text-primary">
                      {formatTag(book.format)}
                    </span>
                  )}
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
