import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Check } from 'lucide-react'
import type { ReviewVO } from '@/api/review'
import { captureReview, type CaptureMode } from '@/api/note'
import { useIsMobile } from '@/hooks/use-mobile'
import { cn } from '@/utils/cn'
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
  Textarea,
} from '@/components/ui'

const MODES: { value: CaptureMode; label: string; desc: string }[] = [
  { value: 'FULL', label: '存全文', desc: '把整篇书评完整保存进笔记' },
  { value: 'EXCERPT', label: '存摘录', desc: '只保留我选中的那一段' },
  { value: 'REFERENCE', label: '只存引用', desc: '留一张引用卡片，不保存正文' },
]

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  review: ReviewVO | null
  /** 打开时预填的选中文本，用于 EXCERPT 模式 */
  initialExcerpt?: string
}

export function NoteCaptureSheet({ open, onOpenChange, review, initialExcerpt }: Props) {
  const isMobile = useIsMobile()
  const queryClient = useQueryClient()
  const [mode, setMode] = useState<CaptureMode>('FULL')
  const [excerpt, setExcerpt] = useState(initialExcerpt ?? '')
  const [myThought, setMyThought] = useState('')

  const reset = () => {
    setMode('FULL')
    setExcerpt(initialExcerpt ?? '')
    setMyThought('')
  }

  const mutation = useMutation({
    mutationFn: () =>
      captureReview(review!.id, {
        mode,
        excerpt: mode === 'EXCERPT' ? excerpt : null,
        myThought: myThought.trim() || null,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notes', 'timeline'] })
      queryClient.invalidateQueries({ queryKey: ['notes', 'count'] })
      queryClient.invalidateQueries({ queryKey: ['notes', 'stats'] })
      queryClient.invalidateQueries({ queryKey: ['notes', 'throwback'] })
      queryClient.invalidateQueries({ queryKey: ['notes', 'semantic'] })
      if (review) queryClient.invalidateQueries({ queryKey: ['book', 'reviews', review.bookId] })
      queryClient.invalidateQueries({ queryKey: ['review', 'detail', review?.id] })
      toast.success('已收录进笔记')
      onOpenChange(false)
      reset()
    },
    onError: (err: Error) => toast.error(err.message || '收录失败'),
  })

  const excerptRequired = mode === 'EXCERPT' && !excerpt.trim()

  const handleOpenChange = (next: boolean) => {
    onOpenChange(next)
    if (!next) reset()
  }

  return (
    <Sheet open={open} onOpenChange={handleOpenChange}>
      <SheetContent side={isMobile ? 'bottom' : 'right'} className="gap-0 overflow-y-auto">
        <SheetHeader className="border-b border-border/50">
          <SheetTitle>收录进笔记</SheetTitle>
          <SheetDescription>
            {review?.title ? `《${review.bookTitle ?? ''}》· ${review.title}` : '这条书评会保存一份快照，原文删了也不丢'}
          </SheetDescription>
        </SheetHeader>

        <div className="space-y-5 p-4">
          <div className="space-y-2">
            {MODES.map((m) => {
              const active = mode === m.value
              return (
                <button
                  key={m.value}
                  onClick={() => setMode(m.value)}
                  className={cn(
                    'flex w-full items-center gap-3 rounded-xl border p-3 text-left transition-colors',
                    active ? 'border-primary bg-primary/5' : 'border-border/50 hover:border-border'
                  )}
                >
                  <div className="min-w-0 flex-1">
                    <p className={cn('text-sm font-semibold', active && 'text-primary')}>{m.label}</p>
                    <p className="truncate text-xs text-muted-foreground">{m.desc}</p>
                  </div>
                  <span
                    className={cn(
                      'flex h-5 w-5 shrink-0 items-center justify-center rounded-full border',
                      active ? 'border-primary bg-primary text-primary-foreground' : 'border-border'
                    )}
                  >
                    {active && <Check className="h-3 w-3" />}
                  </span>
                </button>
              )
            })}
          </div>

          {mode === 'EXCERPT' && (
            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground">我选中的片段</label>
              <Textarea
                value={excerpt}
                onChange={(e) => setExcerpt(e.target.value)}
                placeholder="粘贴或输入要保留的那段文字"
                rows={4}
                className="resize-none"
              />
            </div>
          )}

          <div className="space-y-1.5">
            <label className="text-xs font-medium text-muted-foreground">
              我为什么收这条 <span className="text-muted-foreground/50">（可跳过，但强烈建议写）</span>
            </label>
            <Textarea
              value={myThought}
              onChange={(e) => setMyThought(e.target.value)}
              placeholder="当下的想法，日后回看会知道当时的自己"
              rows={4}
              autoFocus
              className="resize-none"
            />
          </div>
        </div>

        <div className="sticky bottom-0 border-t border-border/50 bg-background p-4">
          <button
            onClick={() => mutation.mutate()}
            disabled={mutation.isPending || !review || excerptRequired}
            className="h-11 w-full rounded-xl bg-primary text-sm font-semibold text-primary-foreground transition-transform active:scale-[0.98] disabled:opacity-50"
          >
            {mutation.isPending ? '收录中...' : '收录'}
          </button>
        </div>
      </SheetContent>
    </Sheet>
  )
}
