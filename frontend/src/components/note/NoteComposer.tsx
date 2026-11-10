import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Check, ChevronDown, PenLine, RotateCcw, Sparkles, Tag } from 'lucide-react'
import { autoTagNote, createNote, updateNoteTags, type NotePayload } from '@/api/note'
import { useIsMobile } from '@/hooks/use-mobile'
import {
  Input,
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  Textarea,
} from '@/components/ui'
import { countWords } from '@/utils/text'
import { cn } from '@/utils/cn'
import { TagInput } from './TagInput'

const DRAFT_KEY = 'kbook:note-draft'
const META_OPEN_KEY = 'kbook:note-composer-meta-open'

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

function readMetaOpenPref(): boolean {
  try {
    return localStorage.getItem(META_OPEN_KEY) === '1'
  } catch {
    return false
  }
}

function writeMetaOpenPref(open: boolean) {
  try {
    localStorage.setItem(META_OPEN_KEY, open ? '1' : '0')
  } catch {
    // 隐私模式下忽略
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
  const isMobile = useIsMobile()
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [tags, setTags] = useState<string[]>([])
  const [restored, setRestored] = useState(false)
  const [metaOpen, setMetaOpen] = useState(false)
  const [metaDefaultOpen, setMetaDefaultOpen] = useState(() => readMetaOpenPref())
  const [promptsOpen, setPromptsOpen] = useState(false)
  const contentRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    if (!open) return
    const draft = readDraft()
    const hasDraft = !!(draft && (draft.title?.trim() || draft.content?.trim()))
    const pref = readMetaOpenPref()
    setMetaDefaultOpen(pref)
    setPromptsOpen(false)
    // 草稿里已有标题/标签时强制展开，避免恢复的内容被藏在收起区
    setMetaOpen(pref || !!draft?.title?.trim() || (draft?.tags?.length ?? 0) > 0)

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

  const requestAutoTags = async (noteId: number) => {
    try {
      const tagged = await autoTagNote(noteId)
      if (tagged.tags.length === 0) return
      queryClient.invalidateQueries({ queryKey: ['notes'] })
      toast.success(`AI 已补标签：${tagged.tags.map((t) => `#${t}`).join(' ')}`, {
        duration: 8000,
        action: {
          label: '撤销',
          onClick: () => {
            updateNoteTags(noteId, [])
              .then(() => {
                queryClient.invalidateQueries({ queryKey: ['notes'] })
                toast.success('已撤销标签')
              })
              .catch((err: Error) => toast.error(err.message || '撤销失败'))
          },
        },
      })
    } catch {
      // 自动标签只是增强，失败时保持无标签
    }
  }

  const createMutation = useMutation({
    mutationFn: (payload: NotePayload) => createNote(payload),
    onSuccess: (note, payload) => {
      const shouldAutoTag = !payload.tags?.length && !!payload.contentMd?.trim()
      queryClient.invalidateQueries({ queryKey: ['notes'] })
      writeDraft({})
      setTitle('')
      setContent('')
      setTags([])
      setRestored(false)
      toast.success('记下了')
      onOpenChange(false)
      if (shouldAutoTag) {
        void requestAutoTags(note.id)
      }
    },
    onError: (err: Error) => toast.error(err.message || '保存失败'),
  })

  const words = useMemo(() => countWords(content), [content])
  const canSave = !!(title.trim() || content.trim())
  const metaFilledCount = (title.trim() ? 1 : 0) + tags.length
  const metaToggleLabel = metaOpen
    ? '收起标题、标签'
    : metaFilledCount > 0
      ? `标题与标签（${metaFilledCount}）`
      : '添加标题、标签'

  const save = () => {
    if (!canSave || createMutation.isPending) return
    createMutation.mutate({
      title: title.trim() || null,
      contentMd: content.trim() || null,
      tags,
    })
  }

  const handleOpenChange = (next: boolean) => {
    if (!next) writeDraft({ title, content, tags })
    onOpenChange(next)
  }

  const handleMetaDefaultChange = (checked: boolean) => {
    setMetaDefaultOpen(checked)
    writeMetaOpenPref(checked)
  }

  const applyPrompt = (template: string) => {
    setContent(template)
    setPromptsOpen(false)
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
    <Sheet open={open} onOpenChange={handleOpenChange}>
      <SheetContent
        side={isMobile ? 'bottom' : 'right'}
        className={cn(
          'gap-0',
          isMobile ? 'h-[85dvh] max-h-[85dvh] rounded-t-2xl' : 'sm:max-w-2xl'
        )}
      >
        <SheetHeader className="shrink-0 space-y-1 border-b border-border/50 px-5 py-4 pr-12">
          <SheetTitle className="flex items-center gap-2 text-base">
            <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-primary/10 text-primary">
              <PenLine className="h-4 w-4" />
            </span>
            记一笔
          </SheetTitle>
          <SheetDescription className="text-xs">私人笔记，只属于你。想到什么先放进来。</SheetDescription>
        </SheetHeader>

        <div
          className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto px-5 py-4"
          onKeyDown={(e) => {
            if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
              e.preventDefault()
              save()
            }
          }}
        >
          <div className="flex shrink-0 items-center gap-1">
            <button
              type="button"
              onClick={() => setMetaOpen((v) => !v)}
              aria-expanded={metaOpen}
              className="flex h-8 items-center gap-1.5 rounded-lg px-1 text-xs font-medium text-muted-foreground transition-colors hover:text-foreground"
            >
              <Tag className="h-3.5 w-3.5" />
              {metaToggleLabel}
              <ChevronDown className={cn('h-3.5 w-3.5 transition-transform', metaOpen && 'rotate-180')} />
            </button>
            {!content.trim() && (
              <button
                type="button"
                onClick={() => setPromptsOpen((v) => !v)}
                aria-expanded={promptsOpen}
                className="flex h-8 items-center gap-1.5 rounded-lg px-1 text-xs font-medium text-muted-foreground transition-colors hover:text-foreground"
              >
                <Sparkles className="h-3.5 w-3.5" />
                灵感
                <ChevronDown className={cn('h-3.5 w-3.5 transition-transform', promptsOpen && 'rotate-180')} />
              </button>
            )}
          </div>

          {metaOpen && (
            <div className="shrink-0 space-y-2">
              <div className="flex items-center gap-2">
                <Input
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                  placeholder="标题（留空自动生成）"
                  maxLength={100}
                  className="h-9 flex-1 border-transparent bg-muted/60 text-sm font-semibold shadow-none"
                />
                <label className="flex shrink-0 cursor-pointer items-center gap-1.5 text-[11px] text-muted-foreground">
                  <input
                    type="checkbox"
                    checked={metaDefaultOpen}
                    onChange={(e) => handleMetaDefaultChange(e.target.checked)}
                    className="h-3.5 w-3.5 accent-primary"
                  />
                  默认展开
                </label>
              </div>
              <TagInput
                value={tags}
                onChange={setTags}
                placeholder="添加标签（留空由 AI 挑选）"
                className="rounded-lg border-0 bg-muted/40 px-2.5 py-1"
              />
            </div>
          )}

          {promptsOpen && !content.trim() && (
            <div className="flex shrink-0 gap-1.5 overflow-x-auto pb-0.5 scrollbar-hide">
              {NOTE_PROMPTS.map((p) => (
                <button
                  key={p.label}
                  type="button"
                  onClick={() => applyPrompt(p.template)}
                  className="flex h-8 shrink-0 items-center whitespace-nowrap rounded-full border border-border/60 bg-background px-3 text-xs text-muted-foreground transition-colors hover:border-primary/40 hover:text-primary"
                >
                  {p.label}
                </button>
              ))}
            </div>
          )}

          <Textarea
            ref={contentRef}
            value={content}
            onChange={(e) => {
              setContent(e.target.value)
              if (e.target.value.trim()) setPromptsOpen(false)
            }}
            placeholder="此刻的想法…（支持 Markdown）"
            maxLength={20000}
            autoFocus
            className="min-h-40 flex-1 resize-none border-transparent bg-muted/60 shadow-none focus-visible:bg-background"
          />

          <div className="flex shrink-0 items-center gap-3 text-xs text-muted-foreground">
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

        <div className="flex shrink-0 items-center gap-3 border-t border-border/50 px-5 py-3">
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
      </SheetContent>
    </Sheet>
  )
}
