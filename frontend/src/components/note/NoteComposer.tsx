import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Check, PenLine, RotateCcw } from 'lucide-react'
import { createNote } from '@/api/note'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  Input,
  Textarea,
} from '@/components/ui'
import { countWords } from '@/utils/text'
import { cn } from '@/utils/cn'
import { TagInput } from './TagInput'

const DRAFT_KEY = 'kbook:note-draft'

export const NOTE_PROMPTS = [
  { label: '今天的一个想法', template: '今天的一个想法：\n' },
  { label: '书里的一句话', template: '> ' },
  { label: '没想清楚的问题', template: '我一直在想：\n' },
  { label: '今天的心情', template: '今天的心情：\n' },
]

interface Draft {
  title?: string
  content?: string
  tags?: string[]
}

function readDraft(): Draft | null {
  try {
    const raw = localStorage.getItem(DRAFT_KEY)
    return raw ? (JSON.parse(raw) as Draft) : null
  } catch {
    return null
  }
}

function writeDraft(draft: Draft) {
  try {
    if (!draft.title?.trim() && !draft.content?.trim()) {
      localStorage.removeItem(DRAFT_KEY)
    } else {
      localStorage.setItem(DRAFT_KEY, JSON.stringify(draft))
    }
  } catch {
    // 隐私模式下 localStorage 可能不可用，草稿能力降级即可
  }
}

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** 打开时预填的内容（如「今日一问」或空状态提示词） */
  seedContent?: string
}

export function NoteComposer({ open, onOpenChange, seedContent }: Props) {
  const queryClient = useQueryClient()
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [tags, setTags] = useState<string[]>([])
  const [restored, setRestored] = useState(false)
  const contentRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    if (!open) return
    const draft = readDraft()
    const hasDraft = !!(draft && (draft.title?.trim() || draft.content?.trim()))
    const seed = seedContent?.trim() ?? ''
    if (!seed) {
      if (hasDraft) {
        setTitle(draft!.title ?? '')
        setContent(draft!.content ?? '')
        setTags(draft!.tags ?? [])
        setRestored(true)
      } else {
        setTitle('')
        setContent('')
        setTags([])
        setRestored(false)
      }
      return
    }
    // 带预填内容打开时（粘贴即记、提示词），已有草稿不能被顶掉，接在后面合并
    const base = hasDraft ? (draft!.content ?? '').trimEnd() : ''
    setTitle(hasDraft ? draft!.title ?? '' : '')
    setTags(hasDraft ? draft!.tags ?? [] : [])
    setContent(base && !base.endsWith(seed) ? `${base}\n\n${seed}` : base || seed)
    setRestored(hasDraft)
  }, [open, seedContent])

  useEffect(() => {
    if (!open) return
    const timer = setTimeout(() => writeDraft({ title, content, tags }), 350)
    return () => clearTimeout(timer)
  }, [open, title, content, tags])

  const createMutation = useMutation({
    mutationFn: () => createNote({
      title: title.trim() || null,
      contentMd: content.trim() || null,
      tags,
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notes'] })
      writeDraft({})
      setTitle('')
      setContent('')
      setTags([])
      setRestored(false)
      toast.success('记下了')
      onOpenChange(false)
    },
    onError: (err: Error) => toast.error(err.message || '保存失败'),
  })

  const words = useMemo(() => countWords(content), [content])
  const canSave = !!(title.trim() || content.trim())

  const save = () => {
    if (!canSave || createMutation.isPending) return
    createMutation.mutate()
  }

  const handleOpenChange = (next: boolean) => {
    if (!next) writeDraft({ title, content, tags })
    onOpenChange(next)
  }

  const applyPrompt = (template: string) => {
    setContent(template)
    requestAnimationFrame(() => contentRef.current?.focus())
  }

  const discardDraft = () => {
    writeDraft({})
    setTitle('')
    setContent('')
    setTags([])
    setRestored(false)
    requestAnimationFrame(() => contentRef.current?.focus())
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
        <DialogHeader className="space-y-1 border-b border-border/50 px-5 py-4 pr-12">
          <DialogTitle className="flex items-center gap-2 text-base">
            <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-primary/10 text-primary">
              <PenLine className="h-4 w-4" />
            </span>
            记一笔
          </DialogTitle>
          <DialogDescription className="text-xs">私人笔记，只属于你。想到什么先放进来。</DialogDescription>
        </DialogHeader>

        <div
          className="space-y-3 px-5 py-4"
          onKeyDown={(e) => {
            if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
              e.preventDefault()
              save()
            }
          }}
        >
          <Input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="标题（可选）"
            maxLength={100}
            className="border-transparent bg-muted/60 text-base font-semibold shadow-none focus-visible:bg-background"
          />

          {!content.trim() && !title.trim() && (
            <div className="flex flex-wrap gap-1.5">
              {NOTE_PROMPTS.map((p) => (
                <button
                  key={p.label}
                  type="button"
                  onClick={() => applyPrompt(p.template)}
                  className="flex h-9 shrink-0 items-center rounded-full border border-border/60 bg-background px-3.5 text-xs text-muted-foreground transition-colors hover:border-primary/40 hover:text-primary"
                >
                  {p.label}
                </button>
              ))}
            </div>
          )}

          <Textarea
            ref={contentRef}
            value={content}
            onChange={(e) => setContent(e.target.value)}
            placeholder="此刻的想法…（支持 Markdown）"
            rows={7}
            maxLength={20000}
            autoFocus
            className="resize-none border-transparent bg-muted/60 shadow-none focus-visible:bg-background"
          />

          <TagInput value={tags} onChange={setTags} className="border-transparent bg-muted/60 shadow-none" />

          <div className="flex items-center gap-3 text-xs text-muted-foreground">
            <span className="tabular-nums">{words} 字</span>
            {restored && (
              <button
                type="button"
                onClick={discardDraft}
                className="flex items-center gap-1 text-muted-foreground/80 transition-colors hover:text-foreground"
              >
                <RotateCcw className="h-3 w-3" />
                已恢复上次草稿 · 清空重写
              </button>
            )}
            <span className="ml-auto hidden items-center gap-1 sm:flex">
              <kbd className="rounded border border-border/60 bg-muted px-1 py-px font-sans">⌘</kbd>
              <span>+</span>
              <kbd className="rounded border border-border/60 bg-muted px-1 py-px font-sans">↵</kbd>
              <span>保存</span>
            </span>
          </div>
        </div>

        <div className="flex items-center gap-3 border-t border-border/50 px-5 py-3">
          <button
            type="button"
            onClick={() => setContent((v) => `${v}${v && !v.endsWith('\n') ? '\n' : ''}- `)}
            className="flex h-8 items-center px-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
          >
            插入列表
          </button>
          <button
            type="button"
            onClick={() => setContent((v) => `${v}${v && !v.endsWith('\n') ? '\n' : ''}> `)}
            className="flex h-8 items-center px-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
          >
            引用
          </button>
          <button
            onClick={save}
            disabled={createMutation.isPending || !canSave}
            className={cn(
              'ml-auto flex h-10 items-center gap-1.5 rounded-xl bg-primary px-5 text-sm font-semibold text-primary-foreground transition-transform',
              'active:scale-[0.98] disabled:opacity-50'
            )}
          >
            {createMutation.isPending ? '保存中…' : <><Check className="h-4 w-4" />保存</>}
          </button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
