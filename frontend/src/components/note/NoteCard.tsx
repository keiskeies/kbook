import { useNavigate } from 'react-router-dom'
import { BookOpen, BookmarkPlus, Lightbulb, MessagesSquare, Quote } from 'lucide-react'
import type { NoteType, NoteVO } from '@/api/note'
import { formatRelativeTime } from '@/utils/time'
import { cn } from '@/utils/cn'
import { NOTE_TYPE_LABEL } from './meta'
import { NoteSourceCard } from './NoteSourceCard'

const TYPE_META: Record<NoteType, { icon: typeof Lightbulb; className: string }> = {
  THOUGHT: { icon: Lightbulb, className: 'bg-amber-500/10 text-amber-600' },
  REVIEW_CAPTURE: { icon: BookmarkPlus, className: 'bg-primary/10 text-primary' },
  EXCERPT: { icon: Quote, className: 'bg-sky-500/10 text-sky-600' },
  CONVERSATION: { icon: MessagesSquare, className: 'bg-violet-500/10 text-violet-600' },
}

export function NoteCard({ note }: { note: NoteVO }) {
  const navigate = useNavigate()
  const meta = TYPE_META[note.noteType] ?? TYPE_META.THOUGHT
  const Icon = meta.icon

  return (
    <article
      onClick={() => navigate(`/notes/${note.id}`)}
      className="cursor-pointer rounded-2xl border border-border/50 bg-card p-4 transition-colors hover:border-border"
    >
      <div className="flex items-center gap-2">
        <span className={cn('flex h-6 w-6 items-center justify-center rounded-lg', meta.className)}>
          <Icon className="h-3.5 w-3.5" />
        </span>
        <span className="text-xs font-medium text-muted-foreground">{NOTE_TYPE_LABEL[note.noteType]}</span>
        <span className="ml-auto text-xs text-muted-foreground">{formatRelativeTime(note.occurredAt)}</span>
      </div>

      {note.bookId != null && (
        <button
          onClick={(e) => { e.stopPropagation(); navigate(`/book/${note.bookId}`) }}
          className="mt-2.5 flex w-full items-center gap-2 text-left"
        >
          {note.bookCover ? (
            <img src={note.bookCover} alt={note.bookTitle ?? ''} className="h-8 w-6 shrink-0 rounded object-cover" />
          ) : (
            <span className="flex h-8 w-6 shrink-0 items-center justify-center rounded bg-muted">
              <BookOpen className="h-3.5 w-3.5 text-muted-foreground/50" />
            </span>
          )}
          <span className="truncate text-xs text-muted-foreground">《{note.bookTitle}》</span>
        </button>
      )}

      {note.title && <h3 className="mt-2 text-base font-bold leading-snug">{note.title}</h3>}

      {note.contentMd && (
        <p className="mt-1.5 whitespace-pre-wrap text-sm leading-relaxed line-clamp-6">{note.contentMd}</p>
      )}

      {note.noteType === 'REVIEW_CAPTURE' && (
        <div onClick={(e) => e.stopPropagation()}>
          <NoteSourceCard note={note} />
        </div>
      )}
    </article>
  )
}
