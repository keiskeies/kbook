import { useNavigate } from 'react-router-dom'
import { BookOpen } from 'lucide-react'
import type { NoteVO } from '@/api/note'
import { formatRelativeTime } from '@/utils/time'
import { NOTE_TYPE_LABEL } from './meta'
import { NoteSourceCard } from './NoteSourceCard'

export function NoteCard({ note }: { note: NoteVO }) {
  const navigate = useNavigate()
  const openDetail = () => navigate(`/notes/${note.id}`)

  return (
    <article
      onClick={openDetail}
      className="cursor-pointer rounded-2xl border border-border/50 bg-card p-4 transition-colors hover:border-border"
    >
      <div className="flex items-center gap-2">
        <span className="rounded-full bg-primary/10 px-2 py-0.5 text-[11px] font-medium text-primary">
          {NOTE_TYPE_LABEL[note.noteType]}
        </span>
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
