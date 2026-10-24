import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  ArrowLeft,
  BookOpen,
  ChevronLeft,
  ChevronRight,
  Copy,
  History,
  Pencil,
  Pin,
  Star,
  Trash2,
} from 'lucide-react'
import {
  deleteNote,
  getNote,
  getNoteNeighbors,
  getRevisions,
  setNoteFavorited,
  setNotePinned,
  updateNote,
} from '@/api/note'
import { formatRelativeTime } from '@/utils/time'
import { countWords } from '@/utils/text'
import { cn } from '@/utils/cn'
import { useGoBack } from '@/hooks/useGoBack'
import MarkdownRenderer from '@/components/ui/markdown-renderer'
import { NoteSourceCard } from '@/components/note/NoteSourceCard'
import { TagInput } from '@/components/note/TagInput'
import { NOTE_TYPE_LABEL } from '@/components/note/meta'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  Textarea,
} from '@/components/ui'

/** 后端 LocalDateTime(ISO) → datetime-local 输入值(YYYY-MM-DDTHH:mm) */
function toLocalInput(iso: string | null | undefined) {
  return iso ? iso.slice(0, 16) : ''
}

export default function NoteDetailPage() {
  const { noteId } = useParams<{ noteId: string }>()
  const navigate = useNavigate()
  const goBack = useGoBack()
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [occurredAt, setOccurredAt] = useState('')
  const [tags, setTags] = useState<string[]>([])
  const autoEditDone = useRef(false)

  const { data: note, isLoading } = useQuery({
    queryKey: ['notes', 'detail', noteId],
    queryFn: () => getNote(Number(noteId)),
    enabled: !!noteId,
  })

  const { data: revisions, isFetching: revisionsLoading } = useQuery({
    queryKey: ['notes', 'revisions', noteId],
    queryFn: () => getRevisions(Number(noteId)),
    enabled: !!noteId && historyOpen,
  })

  const { data: neighbors } = useQuery({
    queryKey: ['notes', 'neighbors', noteId],
    queryFn: () => getNoteNeighbors(Number(noteId)),
    enabled: !!noteId,
  })

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['notes', 'timeline'] })
    queryClient.invalidateQueries({ queryKey: ['notes', 'detail', noteId] })
    queryClient.invalidateQueries({ queryKey: ['notes', 'revisions', noteId] })
    queryClient.invalidateQueries({ queryKey: ['notes', 'count'] })
    queryClient.invalidateQueries({ queryKey: ['notes', 'stats'] })
    queryClient.invalidateQueries({ queryKey: ['notes', 'throwback'] })
    queryClient.invalidateQueries({ queryKey: ['notes', 'semantic'] })
    queryClient.invalidateQueries({ queryKey: ['notes', 'books'] })
    queryClient.invalidateQueries({ queryKey: ['notes', 'tags'] })
    queryClient.invalidateQueries({ queryKey: ['notes', 'neighbors'] })
  }

  const deleteMutation = useMutation({
    mutationFn: () => deleteNote(Number(noteId)),
    onSuccess: () => {
      invalidate()
      toast.success('笔记已删除')
      goBack()
    },
    onError: (err: Error) => toast.error(err.message || '删除失败'),
  })

  const pinMutation = useMutation({
    mutationFn: (pinned: boolean) => setNotePinned(Number(noteId), pinned),
    onSuccess: (_data, pinned) => {
      invalidate()
      toast.success(pinned ? '已置顶' : '已取消置顶')
    },
    onError: (err: Error) => toast.error(err.message || '操作失败'),
  })

  const favoriteMutation = useMutation({
    mutationFn: (favorited: boolean) => setNoteFavorited(Number(noteId), favorited),
    onSuccess: (_data, favorited) => {
      invalidate()
      toast.success(favorited ? '已收藏' : '已取消收藏')
    },
    onError: (err: Error) => toast.error(err.message || '操作失败'),
  })

  const updateMutation = useMutation({
    mutationFn: () =>
      updateNote(Number(noteId), {
        title: title.trim() || null,
        contentMd: content.trim() || null,
        occurredAt: occurredAt ? (occurredAt.length === 16 ? `${occurredAt}:00` : occurredAt) : null,
        tags,
      }),
    onSuccess: () => {
      invalidate()
      toast.success('已保存')
      setEditOpen(false)
    },
    onError: (err: Error) => toast.error(err.message || '保存失败'),
  })

  const openEdit = () => {
    if (!note) return
    setTitle(note.title ?? '')
    setContent(note.contentMd ?? '')
    setOccurredAt(toLocalInput(note.occurredAt))
    setTags(note.tags ?? [])
    setEditOpen(true)
  }

  // 从列表「编辑」直达时自动打开编辑弹窗
  useEffect(() => {
    if (!note || autoEditDone.current) return
    if (searchParams.get('edit') === '1') {
      autoEditDone.current = true
      openEdit()
      const next = new URLSearchParams(searchParams)
      next.delete('edit')
      setSearchParams(next, { replace: true })
    }
  }, [note, searchParams, setSearchParams])

  const copyContent = async () => {
    if (!note) return
    const text = [note.title, note.contentMd].filter(Boolean).join('\n\n')
    if (!text.trim()) {
      toast.error('这条笔记没有可复制的正文')
      return
    }
    try {
      await navigator.clipboard.writeText(text)
      toast.success('已复制')
    } catch {
      toast.error('复制失败')
    }
  }

  if (isLoading) {
    return (
      <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col overflow-hidden bg-background">
        <div className="shrink-0 border-b border-border/50 px-4 py-3">
          <div className="h-5 w-24 animate-pulse rounded bg-muted" />
        </div>
        <div className="flex-1 space-y-3 px-4 py-4">
          <div className="h-4 w-1/2 animate-pulse rounded bg-muted" />
          <div className="h-3 w-full animate-pulse rounded bg-muted" />
          <div className="h-3 w-4/6 animate-pulse rounded bg-muted" />
        </div>
      </div>
    )
  }

  if (!note) {
    return (
      <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col items-center justify-center gap-3 bg-background text-muted-foreground">
        <p className="text-sm">笔记不存在或已被删除</p>
        <button onClick={() => goBack()} className="text-sm font-medium text-primary">返回</button>
      </div>
    )
  }

  return (
    <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col overflow-hidden bg-background page-enter">
      <header className="shrink-0 flex items-center gap-3 border-b border-border/50 bg-navbar/95 px-4 py-3 backdrop-blur-xl z-20">
        <button
          onClick={() => goBack()}
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl transition-colors hover:bg-muted"
          aria-label="返回"
        >
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="flex-1 truncate text-base font-bold">笔记</h1>
        <div className="flex shrink-0 items-center gap-1">
          <button
            onClick={() => pinMutation.mutate(!note.pinned)}
            className={cn(
              'flex h-9 w-9 items-center justify-center rounded-xl transition-colors hover:bg-muted',
              note.pinned ? 'text-primary' : 'text-muted-foreground hover:text-foreground'
            )}
            aria-label={note.pinned ? '取消置顶' : '置顶'}
            title={note.pinned ? '取消置顶' : '置顶'}
          >
            <Pin className={cn('h-4 w-4', note.pinned && 'rotate-45')} />
          </button>
          <button
            onClick={() => favoriteMutation.mutate(!note.favorited)}
            className={cn(
              'flex h-9 w-9 items-center justify-center rounded-xl transition-colors hover:bg-muted',
              note.favorited ? 'text-warning' : 'text-muted-foreground hover:text-foreground'
            )}
            aria-label={note.favorited ? '取消收藏' : '收藏'}
            title={note.favorited ? '取消收藏' : '收藏'}
          >
            <Star className={cn('h-4 w-4', note.favorited && 'fill-warning')} />
          </button>
          {note.version > 1 && (
            <button
              onClick={() => setHistoryOpen(true)}
              className="flex h-9 w-9 items-center justify-center rounded-xl text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
              aria-label="历史版本"
              title="历史版本"
            >
              <History className="h-4 w-4" />
            </button>
          )}
          <button
            onClick={copyContent}
            className="flex h-9 w-9 items-center justify-center rounded-xl text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            aria-label="复制正文"
            title="复制正文"
          >
            <Copy className="h-4 w-4" />
          </button>
          <button
            onClick={openEdit}
            className="flex h-9 w-9 items-center justify-center rounded-xl text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            aria-label="编辑"
          >
            <Pencil className="h-4 w-4" />
          </button>
          <button
            onClick={() => setConfirmOpen(true)}
            className="flex h-9 w-9 items-center justify-center rounded-xl text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive"
            aria-label="删除"
          >
            <Trash2 className="h-4 w-4" />
          </button>
        </div>
      </header>

      <div className="flex-1 overflow-y-auto overscroll-contain px-4 py-4 pb-20 md:pb-8">
        <div className="mx-auto w-full max-w-2xl">
          <div className="flex items-center gap-2">
            <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
              {NOTE_TYPE_LABEL[note.noteType]}
            </span>
            <span className="text-xs text-muted-foreground">{formatRelativeTime(note.occurredAt)}</span>
            {note.version > 1 && (
              <span className="text-xs text-muted-foreground/70">· 已编辑</span>
            )}
            {countWords(note.contentMd) > 0 && (
              <span className="text-xs text-muted-foreground/70">· {countWords(note.contentMd)} 字</span>
            )}
            {note.pinned && (
              <span className="flex items-center gap-0.5 text-xs text-primary">
                <Pin className="h-3 w-3 rotate-45" />置顶
              </span>
            )}
            {note.favorited && (
              <span className="flex items-center gap-0.5 text-xs text-warning">
                <Star className="h-3 w-3 fill-warning" />收藏
              </span>
            )}
          </div>

          {(note.tags ?? []).length > 0 && (
            <div className="mt-2 flex flex-wrap gap-1.5">
              {(note.tags ?? []).map((tag) => (
                <span key={tag} className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
                  #{tag}
                </span>
              ))}
            </div>
          )}

          {note.bookId != null && (
            <button
              onClick={() => navigate(`/book/${note.bookId}`)}
              className="mt-3 flex w-full items-center gap-3 rounded-2xl border border-border/50 bg-card p-3 text-left transition-colors hover:border-border"
            >
              {note.bookCover ? (
                <img src={note.bookCover} alt={note.bookTitle ?? ''} className="h-14 w-10 shrink-0 rounded-md object-cover" />
              ) : (
                <span className="flex h-14 w-10 shrink-0 items-center justify-center rounded-md bg-muted">
                  <BookOpen className="h-5 w-5 text-muted-foreground/50" />
                </span>
              )}
              <span className="truncate text-sm font-semibold">《{note.bookTitle}》</span>
            </button>
          )}

          {note.title && <h2 className="mt-4 text-xl font-bold leading-snug">{note.title}</h2>}

          {note.contentMd ? (
            <MarkdownRenderer content={note.contentMd} className="mt-3 text-body leading-relaxed" />
          ) : (
            !note.snapshot && <p className="mt-3 text-sm text-muted-foreground">这条笔记没有正文</p>
          )}

          {note.noteType === 'REVIEW_CAPTURE' && (
            <NoteSourceCard
              note={note}
              onOpenSource={
                note.sourceStatus === 'ACTIVE' && note.sourceId != null && note.bookId != null
                  ? () => navigate(`/book/${note.bookId}/reviews/${note.sourceId}`)
                  : undefined
              }
            />
          )}

          {(neighbors?.prev || neighbors?.next) && (
            <div className="mt-8 grid grid-cols-2 gap-3 border-t border-border/50 pt-4">
              {neighbors?.prev ? (
                <button
                  onClick={() => navigate(`/notes/${neighbors.prev!.id}`)}
                  className="flex items-center gap-2 rounded-xl border border-border/50 bg-card p-3 text-left transition-colors hover:border-primary/35"
                >
                  <ChevronLeft className="h-4 w-4 shrink-0 text-muted-foreground" />
                  <span className="min-w-0">
                    <span className="block text-xs text-muted-foreground">上一条 · 更早</span>
                    <span className="block truncate text-sm font-medium">{neighbors.prev!.title || '未命名笔记'}</span>
                  </span>
                </button>
              ) : <span />}
              {neighbors?.next ? (
                <button
                  onClick={() => navigate(`/notes/${neighbors.next!.id}`)}
                  className="flex items-center justify-end gap-2 rounded-xl border border-border/50 bg-card p-3 text-right transition-colors hover:border-primary/35"
                >
                  <span className="min-w-0">
                    <span className="block text-xs text-muted-foreground">下一条 · 更新</span>
                    <span className="block truncate text-sm font-medium">{neighbors.next!.title || '未命名笔记'}</span>
                  </span>
                  <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
                </button>
              ) : <span />}
            </div>
          )}
        </div>
      </div>

      <Dialog open={editOpen} onOpenChange={setEditOpen}>
        <DialogContent className="gap-0 p-0">
          <DialogHeader className="border-b border-border/50 p-4">
            <DialogTitle>编辑笔记</DialogTitle>
          </DialogHeader>
          <div className="space-y-3 p-4">
            <Input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="标题（可选）" maxLength={100} />
            <Textarea
              value={content}
              onChange={(e) => setContent(e.target.value)}
              placeholder="此刻的想法…"
              rows={6}
              maxLength={20000}
              className="resize-none"
            />
            <div className="space-y-1.5">
              <Label className="text-xs text-muted-foreground">发生时间（可回填到过去某天）</Label>
              <Input
                type="datetime-local"
                value={occurredAt}
                onChange={(e) => setOccurredAt(e.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label className="text-xs text-muted-foreground">标签</Label>
              <TagInput value={tags} onChange={setTags} />
            </div>
          </div>
          <div className="border-t border-border/50 p-4">
            <button
              onClick={() => updateMutation.mutate()}
              disabled={updateMutation.isPending || (!title.trim() && !content.trim() && !occurredAt && tags.length === 0)}
              className="h-11 w-full rounded-xl bg-primary text-sm font-semibold text-primary-foreground transition-transform active:scale-[0.98] disabled:opacity-50"
            >
              {updateMutation.isPending ? '保存中...' : '保存'}
            </button>
          </div>
        </DialogContent>
      </Dialog>

      <AlertDialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除这条笔记？</AlertDialogTitle>
            <AlertDialogDescription>删除后将从时间轴移除，确定吗？</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => deleteMutation.mutate()}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              {deleteMutation.isPending ? '删除中...' : '删除'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <Sheet open={historyOpen} onOpenChange={setHistoryOpen}>
        <SheetContent side="bottom" className="max-h-[80vh] gap-0 overflow-y-auto rounded-t-2xl">
          <SheetHeader className="border-b border-border/50">
            <SheetTitle>历史版本</SheetTitle>
          </SheetHeader>
          <div className="space-y-3 p-4">
            {revisionsLoading ? (
              <div className="space-y-3">
                {[0, 1].map((i) => <div key={i} className="h-20 animate-pulse rounded-xl bg-muted" />)}
              </div>
            ) : !revisions || revisions.length === 0 ? (
              <p className="py-6 text-center text-sm text-muted-foreground">还没有历史版本</p>
            ) : (
              revisions.map((r) => (
                <div key={r.id} className="rounded-xl border border-border/50 p-3">
                  <div className="flex items-center gap-2 text-xs text-muted-foreground">
                    <span className="rounded-full bg-muted px-2 py-0.5 font-medium">v{r.revisionNo}</span>
                    <span>{r.editedAt ? `${formatRelativeTime(r.editedAt)}改的` : ''}</span>
                    {r.occurredAt && <span className="ml-auto">{formatRelativeTime(r.occurredAt)}</span>}
                  </div>
                  {r.title && <p className="mt-2 text-sm font-semibold">{r.title}</p>}
                  {r.contentMd ? (
                    <p className="mt-1 whitespace-pre-wrap text-sm text-muted-foreground line-clamp-4">{r.contentMd}</p>
                  ) : (
                    !r.title && <p className="mt-1 text-sm text-muted-foreground/60">（空）</p>
                  )}
                </div>
              ))
            )}
          </div>
        </SheetContent>
      </Sheet>
    </div>
  )
}
