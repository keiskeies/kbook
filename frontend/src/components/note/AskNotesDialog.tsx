import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { ArrowUp, BookOpen, Loader2, Sparkles } from 'lucide-react'
import { askNotes, type NoteAskVO } from '@/api/note'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  Input,
} from '@/components/ui'
import MarkdownRenderer from '@/components/ui/markdown-renderer'
import { formatRelativeTime } from '@/utils/time'

const SUGGESTIONS = [
  '我最近在反复想什么？',
  '我对哪本书印象最深？',
  '我写过的关于「自由」的观点有哪些？',
  '帮我总结一下最近的阅读状态',
]

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function AskNotesDialog({ open, onOpenChange }: Props) {
  const navigate = useNavigate()
  const [question, setQuestion] = useState('')
  const [result, setResult] = useState<NoteAskVO | null>(null)
  const [error, setError] = useState('')
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (open) {
      setError('')
      const timer = setTimeout(() => inputRef.current?.focus(), 120)
      return () => clearTimeout(timer)
    }
  }, [open])

  const askMutation = useMutation({
    mutationFn: (q: string) => askNotes(q),
    onSuccess: (data) => {
      setResult(data)
      setError('')
    },
    onError: (err: Error) => {
      setError(err.message || '暂时无法回答，请稍后再试')
    },
  })

  const submit = (raw?: string) => {
    const q = (raw ?? question).trim()
    if (!q || askMutation.isPending) return
    setQuestion(q)
    setResult(null)
    setError('')
    askMutation.mutate(q)
  }

  const openSource = (id: number) => {
    onOpenChange(false)
    navigate(`/notes/${id}`)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[85vh] flex-col gap-0 overflow-hidden p-0 sm:max-w-2xl">
        <DialogHeader className="shrink-0 space-y-1 border-b border-border/50 px-5 py-4 pr-12">
          <DialogTitle className="flex items-center gap-2 text-base">
            <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-primary/10 text-primary">
              <Sparkles className="h-4 w-4" />
            </span>
            问我的笔记
          </DialogTitle>
          <DialogDescription className="text-xs">
            AI 只根据你自己的笔记回答，并标出来源
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-5 py-4">
          {!result && !askMutation.isPending && !error && (
            <div>
              <p className="text-xs text-muted-foreground">可以这样问：</p>
              <div className="mt-2 flex flex-wrap gap-1.5">
                {SUGGESTIONS.map((s) => (
                  <button
                    key={s}
                    onClick={() => submit(s)}
                    className="flex h-9 items-center rounded-full border border-border/60 bg-background px-3.5 text-xs text-muted-foreground transition-colors hover:border-primary/40 hover:text-primary"
                  >
                    {s}
                  </button>
                ))}
              </div>
            </div>
          )}

          {askMutation.isPending && (
            <div className="flex flex-col items-center gap-2 py-10 text-muted-foreground">
              <Loader2 className="h-6 w-6 animate-spin text-primary" />
              <p className="text-sm">正在翻你的笔记…</p>
              <p className="text-xs text-muted-foreground/70">先检索相关笔记，再组织回答</p>
            </div>
          )}

          {error && !askMutation.isPending && (
            <div className="rounded-xl border border-destructive/20 bg-destructive/5 px-3 py-2.5 text-sm text-destructive">
              {error}
            </div>
          )}

          {result && (
            <>
              <div className="rounded-2xl border border-border/50 bg-muted/30 p-4">
                <MarkdownRenderer content={result.answer} className="text-sm leading-relaxed" />
              </div>

              {result.sources.length > 0 && (
                <div>
                  <p className="mb-2 text-xs font-semibold text-muted-foreground">回答来源</p>
                  <div className="space-y-2">
                    {result.sources.map((source, index) => (
                      <button
                        key={source.id}
                        onClick={() => openSource(source.id)}
                        className="flex w-full items-start gap-2.5 rounded-xl border border-border/50 bg-card p-3 text-left transition-colors hover:border-primary/35"
                      >
                        <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-md bg-primary/10 text-xs font-bold text-primary">
                          {index + 1}
                        </span>
                        <span className="min-w-0 flex-1">
                          <span className="block truncate text-xs font-semibold">
                            {source.title || source.snippet || '未命名笔记'}
                          </span>
                          <span className="mt-0.5 flex items-center gap-1.5 text-xs text-muted-foreground">
                            {source.bookTitle && <><BookOpen className="h-3 w-3" />《{source.bookTitle}》·</>}
                            {formatRelativeTime(source.occurredAt)}
                          </span>
                        </span>
                      </button>
                    ))}
                  </div>
                </div>
              )}

              <button
                onClick={() => {
                  setResult(null)
                  setQuestion('')
                  inputRef.current?.focus()
                }}
                className="inline-flex h-8 items-center px-1 text-xs font-medium text-primary"
              >
                再问一个 →
              </button>
            </>
          )}
        </div>

        <div className="shrink-0 border-t border-border/50 p-4">
          <div className="flex items-center gap-2">
            <Input
              ref={inputRef}
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault()
                  submit()
                }
              }}
              placeholder="问点关于你笔记的事…"
              maxLength={500}
              disabled={askMutation.isPending}
            />
            <button
              onClick={() => submit()}
              disabled={!question.trim() || askMutation.isPending}
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-primary text-primary-foreground transition-transform active:scale-95 disabled:opacity-40"
              aria-label="提问"
            >
              {askMutation.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <ArrowUp className="h-4 w-4" />}
            </button>
          </div>
          <p className="mt-1.5 text-xs text-muted-foreground/60">只检索你自己的笔记，不会联网</p>
        </div>
      </DialogContent>
    </Dialog>
  )
}
