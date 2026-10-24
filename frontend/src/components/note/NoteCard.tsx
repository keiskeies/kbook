import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import {
  BookOpen,
  BookmarkPlus,
  Copy,
  Lightbulb,
  MessagesSquare,
  MoreHorizontal,
  Pencil,
  Pin,
  PinOff,
  Quote,
  Star,
  StarOff,
  Trash2,
} from 'lucide-react'
import type { NoteType, NoteVO } from '@/api/note'
import { formatRelativeTime } from '@/utils/time'
import { countWords } from '@/utils/text'
import { cn } from '@/utils/cn'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui'
import { NOTE_TYPE_LABEL } from './meta'
import { HighlightText } from './HighlightText'
import { NoteSourceCard } from './NoteSourceCard'

const TYPE_META: Record<NoteType, { icon: typeof Lightbulb; className: string; accent: string }> = {
  THOUGHT: { icon: Lightbulb, className: 'bg-amber-500/10 text-amber-600', accent: 'bg-amber-400/70' },
  REVIEW_CAPTURE: { icon: BookmarkPlus, className: 'bg-primary/10 text-primary', accent: 'bg-primary/60' },
  EXCERPT: { icon: Quote, className: 'bg-sky-500/10 text-sky-600', accent: 'bg-sky-400/70' },
  CONVERSATION: { icon: MessagesSquare, className: 'bg-violet-500/10 text-violet-600', accent: 'bg-violet-400/70' },
}

interface Props {
  note: NoteVO
  keyword?: string
  onPin?: (note: NoteVO) => void
  onFavorite?: (note: NoteVO) => void
  onDelete?: (note: NoteVO) => void
  onEdit?: (note: NoteVO) => void
  onTagClick?: (tag: string) => void
}

export function NoteCard({ note, keyword, onPin, onFavorite, onDelete, onEdit, onTagClick }: Props) {
  const navigate = useNavigate()
  const [menuOpen, setMenuOpen] = useState(false)
  const longPressed = useRef(false)
  const pressTimer = useRef<number | null>(null)
  const meta = TYPE_META[note.noteType] ?? TYPE_META.THOUGHT
  const Icon = meta.icon
  const isExcerpt = note.noteType === 'EXCERPT' && !!note.contentMd
  const words = countWords(note.contentMd)
  const tags = note.tags ?? []

  const clearPress = () => {
    if (pressTimer.current) {
      window.clearTimeout(pressTimer.current)
      pressTimer.current = null
    }
  }

  const startPress = () => {
    longPressed.current = false
    clearPress()
    pressTimer.current = window.setTimeout(() => {
      longPressed.current = true
      setMenuOpen(true)
    }, 550)
  }

  const openDetail = () => {
    if (longPressed.current) {
      longPressed.current = false
      return
    }
    navigate(`/notes/${note.id}`)
  }

  const copyContent = async () => {
    const text = [note.title, note.contentMd].filter(Boolean).join('\n\n')
      || note.snapshot?.excerpt
      || note.snapshot?.sourceContent
      || ''
    if (!text.trim()) {
      toast.error('这条笔记没有可复制的内容')
      return
    }
    try {
      await navigator.clipboard.writeText(text)
      toast.success('已复制')
    } catch {
      toast.error('复制失败')
    }
  }

  return (
    <DropdownMenu open={menuOpen} onOpenChange={setMenuOpen}>
      <article
        onClick={openDetail}
        onContextMenu={(e) => {
          e.preventDefault()
          setMenuOpen(true)
        }}
        onTouchStart={startPress}
        onTouchEnd={clearPress}
        onTouchMove={clearPress}
        onTouchCancel={clearPress}
        className={cn(
          'group relative cursor-pointer overflow-hidden rounded-2xl border bg-card p-4 transition-all duration-200 hover:shadow-md active:scale-[0.995]',
          note.pinned ? 'border-primary/35' : 'border-border/60 hover:border-primary/35'
        )}
      >
        <span className={cn('absolute inset-y-4 left-0 w-[3px] rounded-r-full opacity-0 transition-opacity group-hover:opacity-100', meta.accent)} />

        <div className="flex items-center gap-2">
          <span className={cn('flex h-6 w-6 items-center justify-center rounded-lg', meta.className)}>
            <Icon className="h-3.5 w-3.5" />
          </span>
          <span className="text-xs font-medium text-muted-foreground">{NOTE_TYPE_LABEL[note.noteType]}</span>
          {note.pinned && <Pin className="h-3 w-3 rotate-45 text-primary" aria-label="已置顶" />}
          {note.favorited && <Star className="h-3 w-3 fill-warning text-warning" aria-label="已收藏" />}
          {note.version > 1 && (
            <span className="rounded-full bg-muted px-1.5 py-px text-[11px] font-medium text-muted-foreground">已编辑</span>
          )}
          <span className="ml-auto shrink-0 text-xs text-muted-foreground/80">{formatRelativeTime(note.occurredAt)}</span>

          <DropdownMenuTrigger asChild>
            <button
              onClick={(e) => e.stopPropagation()}
              className="-mr-2 flex h-9 w-9 shrink-0 items-center justify-center rounded-lg text-muted-foreground/60 opacity-100 transition-all hover:bg-muted hover:text-foreground md:opacity-0 md:group-hover:opacity-100"
              aria-label="笔记操作"
            >
              <MoreHorizontal className="h-4 w-4" />
            </button>
          </DropdownMenuTrigger>
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

        {note.title && (
          <h3 className="mt-2 text-base font-bold leading-snug">
            <HighlightText text={note.title} keyword={keyword} />
          </h3>
        )}

        {note.contentMd && (
          isExcerpt ? (
            <blockquote className="mt-2 rounded-r-lg border-l-2 border-sky-400/50 bg-sky-500/[0.04] py-1 pl-3">
              <p className="whitespace-pre-wrap text-sm leading-relaxed text-foreground/85 line-clamp-5">
                <HighlightText text={note.contentMd} keyword={keyword} />
              </p>
            </blockquote>
          ) : (
            <p className="mt-1.5 whitespace-pre-wrap text-sm leading-relaxed line-clamp-6">
              <HighlightText text={note.contentMd} keyword={keyword} />
            </p>
          )
        )}

        {note.noteType === 'REVIEW_CAPTURE' && (
          <div onClick={(e) => e.stopPropagation()}>
            <NoteSourceCard note={note} />
          </div>
        )}

        {tags.length > 0 && (
          <div className="mt-2.5 flex flex-wrap gap-1.5" onClick={(e) => e.stopPropagation()}>
            {tags.map((tag) => (
              <button
                key={tag}
                onClick={() => onTagClick?.(tag)}
                className="flex h-8 items-center rounded-full bg-muted px-2.5 text-xs text-muted-foreground transition-colors hover:bg-primary/10 hover:text-primary sm:h-7"
              >
                #{tag}
              </button>
            ))}
          </div>
        )}

        {(words > 0 || note.version > 1) && (
          <div className="mt-3 flex items-center gap-2 text-xs text-muted-foreground/70">
            {words > 0 && <span>{words} 字</span>}
            {words > 0 && note.version > 1 && <span className="h-2.5 w-px bg-border" />}
            {note.version > 1 && <span>第 {note.version} 版</span>}
          </div>
        )}
      </article>

      <DropdownMenuContent align="end" className="w-40" onClick={(e) => e.stopPropagation()}>
        <DropdownMenuItem onClick={() => onPin?.(note)}>
          {note.pinned ? <PinOff className="mr-2 h-4 w-4" /> : <Pin className="mr-2 h-4 w-4" />}
          {note.pinned ? '取消置顶' : '置顶'}
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => onFavorite?.(note)}>
          {note.favorited ? <StarOff className="mr-2 h-4 w-4" /> : <Star className="mr-2 h-4 w-4" />}
          {note.favorited ? '取消收藏' : '收藏'}
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => onEdit?.(note)}>
          <Pencil className="mr-2 h-4 w-4" />
          编辑
        </DropdownMenuItem>
        <DropdownMenuItem onClick={copyContent}>
          <Copy className="mr-2 h-4 w-4" />
          复制正文
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem
          onClick={() => onDelete?.(note)}
          className="text-destructive focus:bg-destructive/10 focus:text-destructive"
        >
          <Trash2 className="mr-2 h-4 w-4" />
          删除
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
