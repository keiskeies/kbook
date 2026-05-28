import { useEffect, useState, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { ArrowLeft, Trash2, RotateCcw, Star } from 'lucide-react'
import { getTrashList, removeFromTrash } from '@/api/bookTrash'
import type { BookTrashItem } from '@/api/bookTrash'
import BookCover from '@/components/book/BookCover'
import { parseFormatTags, formatFileSize } from '@/types/book'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog'
import { toast } from 'sonner'

function RatingBadgeCN({ rating }: { rating: number | undefined | null }) {
  if (rating == null || rating < 0) return null
  const r = Number(rating.toFixed(1))
  let colorClass = ''
  if (r >= 5.0) colorClass = 'text-danger dark:text-danger'
  else if (r >= 4.5) colorClass = 'text-warning dark:text-warning'
  else if (r >= 4.0) colorClass = 'text-warning dark:text-warning'
  else if (r >= 3.0) colorClass = 'text-success dark:text-success'
  else if (r >= 2.5) colorClass = 'text-success dark:text-success'
  else colorClass = 'text-muted-foreground dark:text-muted-foreground'
  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${colorClass}`}>
      <Star className="h-2.5 w-2.5" />
      评分：{r}
    </span>
  )
}

export default function BookTrashPage() {
  const navigate = useNavigate()
  const [books, setBooks] = useState<BookTrashItem[]>([])
  const [loading, setLoading] = useState(true)
  const [restoreDialogOpen, setRestoreDialogOpen] = useState(false)
  const [restoreTarget, setRestoreTarget] = useState<BookTrashItem | null>(null)
  const [restoring, setRestoring] = useState(false)

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const res = await getTrashList()
      const data = (res as any)?.data || (res as any) || []
      setBooks(Array.isArray(data) ? data : [])
    } catch {
      setBooks([])
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  const handleRestoreClick = (book: BookTrashItem) => {
    setRestoreTarget(book)
    setRestoreDialogOpen(true)
  }

  const handleConfirmRestore = async () => {
    if (!restoreTarget) return
    setRestoring(true)
    try {
      await removeFromTrash(restoreTarget.bookId)
      toast.success('已从垃圾桶移出，图书将重新出现在推荐列表中')
      setBooks(prev => prev.filter(b => b.bookId !== restoreTarget.bookId))
    } catch (err: any) {
      toast.error(err?.message || '操作失败')
    } finally {
      setRestoring(false)
      setRestoreDialogOpen(false)
      setRestoreTarget(null)
    }
  }

  return (
    <div className="fixed inset-0 flex flex-col overflow-hidden bg-background page-enter overscroll-contain">
      <div className="shrink-0 z-20 bg-gradient-to-b from-background/95 via-background/80 to-background/60 pt-safe-top backdrop-blur-xl border-b border-border/30">
        <header className="flex items-center gap-3 px-4 py-3">
          <button onClick={() => navigate(-1)} className="flex h-8 w-8 items-center justify-center rounded-lg hover:bg-muted transition-colors">
            <ArrowLeft className="h-5 w-5" />
          </button>
          <div className="flex items-center gap-2 flex-1">
            <Trash2 className="h-5 w-5 text-red-500" />
            <h1 className="text-lg font-bold">垃圾桶</h1>
            {books.length > 0 && (
              <span className="text-xs text-muted-foreground">共{books.length}本</span>
            )}
          </div>
        </header>
      </div>

      <div className="flex-1 overflow-y-auto overscroll-contain px-4 py-3">
        {loading ? (
          <div className="space-y-3">
            {Array.from({ length: 3 }, (_, i) => (
              <div key={i} className="rounded-2xl bg-card p-3 shadow-sm border border-border/50">
                <div className="flex gap-3">
                  <div className="h-24 w-16 flex-shrink-0 rounded-lg bg-muted animate-pulse" />
                  <div className="flex-1 space-y-2">
                    <div className="h-4 w-3/4 rounded bg-muted animate-pulse" />
                    <div className="h-3 w-1/2 rounded bg-muted animate-pulse" />
                  </div>
                </div>
              </div>
            ))}
          </div>
        ) : books.length > 0 ? (
          <div className="space-y-2.5">
            {books.map((book) => {
              const tags = parseFormatTags(book.formatTags || '')
              return (
                <div
                  key={book.trashId}
                  className="rounded-2xl bg-card p-3 shadow-sm border border-border/50"
                >
                  <div className="flex gap-3">
                    <BookCover
                      coverUrl={book.coverUrl}
                      title={book.title}
                      author={book.author}
                      format={book.format}
                      size="md"
                      className="flex-shrink-0"
                    />
                    <div className="flex-1 min-w-0 flex flex-col justify-between">
                      <div>
                        <p className="truncate text-sm font-semibold">{book.title}</p>
                        <p className="mt-0.5 truncate text-xs text-muted-foreground">
                          {book.author || '未知作者'}
                        </p>
                      </div>

                      <div className="mt-1.5 flex items-center gap-1.5 flex-wrap">
                        <RatingBadgeCN rating={book.rating} />
                        {book.fileSize && (
                          <span className="text-[11px] text-muted-foreground">
                            {formatFileSize(book.fileSize)}
                          </span>
                        )}
                      </div>

                      {tags.length > 0 && (
                        <div className="mt-1.5 flex items-center gap-1.5 overflow-x-auto scrollbar-hide">
                          {tags.map((t) => (
                            <span
                              key={t}
                              className="inline-flex shrink-0 items-center gap-0.5 whitespace-nowrap rounded-md bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary"
                            >
                              {t}
                            </span>
                          ))}
                        </div>
                      )}
                    </div>
                  </div>

                  <div className="mt-2 flex justify-end">
                    <button
                      onClick={() => handleRestoreClick(book)}
                      className="flex items-center gap-1.5 rounded-lg bg-primary/10 px-3 py-1.5 text-xs font-medium text-primary hover:bg-primary/20 transition-colors active:scale-95"
                    >
                      <RotateCcw className="h-3.5 w-3.5" />
                      移出垃圾桶
                    </button>
                  </div>
                </div>
              )
            })}
          </div>
        ) : (
          <div className="flex h-[50vh] flex-col items-center justify-center">
            <Trash2 className="mb-4 h-12 w-12 text-muted-foreground/30" />
            <p className="text-sm text-muted-foreground">垃圾桶是空的</p>
            <p className="mt-1 text-xs text-muted-foreground/60">在推荐列表中左滑图书可将其丢入垃圾桶</p>
          </div>
        )}
      </div>

      <Dialog open={restoreDialogOpen} onOpenChange={setRestoreDialogOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>移出垃圾桶</DialogTitle>
          </DialogHeader>
          <p className="text-sm text-muted-foreground">
            确定将「{restoreTarget?.title}」移出垃圾桶吗？移出后，该图书将重新出现在推荐列表中。
          </p>
          <DialogFooter>
            <button
              onClick={() => setRestoreDialogOpen(false)}
              className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground hover:bg-muted transition-colors"
            >
              取消
            </button>
            <button
              onClick={handleConfirmRestore}
              disabled={restoring}
              className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
            >
              {restoring ? '处理中...' : '移出垃圾桶'}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
