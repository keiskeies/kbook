import { BookOpen, ChevronRight, LibraryBig } from 'lucide-react'
import type { NoteBookVO } from '@/api/note'
import { formatRelativeTime } from '@/utils/time'

interface Props {
  books: NoteBookVO[] | undefined
  isLoading: boolean
  onOpen: (book: NoteBookVO) => void
}

export function NoteBooksView({ books, isLoading, onOpen }: Props) {
  if (isLoading) {
    return (
      <div className="grid gap-3 sm:grid-cols-2">
        {[0, 1, 2, 3].map((i) => <div key={i} className="h-[86px] animate-pulse rounded-2xl bg-muted" />)}
      </div>
    )
  }

  if (!books || books.length === 0) {
    return (
      <div className="flex flex-col items-center gap-3 rounded-2xl border border-dashed border-border/70 px-6 py-14 text-muted-foreground">
        <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-muted">
          <LibraryBig className="h-7 w-7 text-muted-foreground/50" />
        </div>
        <p className="text-sm">还没有和书关联的笔记</p>
        <p className="max-w-[260px] text-center text-xs text-muted-foreground/70">
          收录书评或在书籍页记录想法后，这里会自动按书归类
        </p>
      </div>
    )
  }

  return (
    <div className="grid gap-3 sm:grid-cols-2">
      {books.map((book) => (
        <button
          key={book.bookId}
          onClick={() => onOpen(book)}
          className="group flex items-center gap-3 rounded-2xl border border-border/60 bg-card p-3 text-left transition-all hover:border-primary/35 hover:shadow-md active:scale-[0.995]"
        >
          {book.bookCover ? (
            <img
              src={book.bookCover}
              alt={book.bookTitle ?? ''}
              className="h-16 w-11 shrink-0 rounded-md object-cover shadow-sm"
            />
          ) : (
            <span className="flex h-16 w-11 shrink-0 items-center justify-center rounded-md bg-muted">
              <BookOpen className="h-5 w-5 text-muted-foreground/50" />
            </span>
          )}
          <span className="min-w-0 flex-1">
            <span className="line-clamp-2 text-sm font-semibold leading-snug">
              {book.bookTitle ? `《${book.bookTitle}》` : '未命名书籍'}
            </span>
            <span className="mt-1 block text-xs text-muted-foreground">
              {book.count} 条笔记 · {formatRelativeTime(book.lastOccurredAt)}
            </span>
          </span>
          <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground/40 transition-transform group-hover:translate-x-0.5 group-hover:text-primary" />
        </button>
      ))}
    </div>
  )
}
