import { Star, Link2Off, Quote } from 'lucide-react'
import type { NoteVO } from '@/api/note'
import { cn } from '@/utils/cn'

interface Props {
  note: NoteVO
  /** REFERENCE 模式下点击跳转原书评；来源已删除时禁用 */
  onOpenSource?: () => void
}

function Stars({ rating }: { rating: number }) {
  return (
    <span className="flex items-center gap-0.5">
      {[1, 2, 3, 4, 5].map((s) => (
        <Star key={s} className={cn('h-3 w-3', rating >= s ? 'fill-warning text-warning' : 'text-muted-foreground/25')} />
      ))}
    </span>
  )
}

/** 渲染收录那一刻保存的来源快照；来源被删也照常显示，只加一个墓碑标记 */
export function NoteSourceCard({ note, onOpenSource }: Props) {
  const { snapshot, captureMode, sourceStatus } = note
  if (!snapshot) return null

  const deleted = sourceStatus === 'DELETED'

  const header = (
    <div className="flex items-center gap-2">
      <div className="flex h-6 w-6 shrink-0 items-center justify-center overflow-hidden rounded-full bg-muted text-[11px] font-bold text-muted-foreground">
        {snapshot.authorAvatar ? (
          <img src={snapshot.authorAvatar} alt={snapshot.authorName ?? ''} className="h-full w-full object-cover" />
        ) : (
          snapshot.authorName?.[0] ?? 'U'
        )}
      </div>
      <span className="truncate text-xs font-medium text-muted-foreground">
        {snapshot.authorName ?? '匿名读者'} 的书评
      </span>
      {snapshot.rating != null && <Stars rating={snapshot.rating} />}
    </div>
  )

  return (
    <div className="mt-2.5 rounded-xl border border-border/40 bg-muted/30 p-3">
      {deleted && (
        <div className="mb-2 flex items-center gap-1.5 text-[11px] font-medium text-muted-foreground">
          <Link2Off className="h-3 w-3" />
          原书评已删除 · 这是你收录时保存的快照
        </div>
      )}
      {header}
      {snapshot.sourceTitle && (
        <p className="mt-1.5 text-sm font-semibold leading-snug">{snapshot.sourceTitle}</p>
      )}

      {captureMode === 'REFERENCE' ? (
        <div className="mt-1.5 flex items-center gap-1.5 text-xs text-muted-foreground">
          <Quote className="h-3 w-3" />
          仅收录了引用，未保存正文
          {!deleted && onOpenSource && (
            <button onClick={onOpenSource} className="ml-auto font-medium text-primary">查看原文</button>
          )}
        </div>
      ) : (
        <div className="mt-1.5">
          <p className="whitespace-pre-wrap text-sm leading-relaxed text-muted-foreground">
            {captureMode === 'EXCERPT' ? (snapshot.excerpt ?? '') : (snapshot.sourceContent ?? '')}
          </p>
          {captureMode === 'EXCERPT' && (
            <p className="mt-1 text-[11px] text-muted-foreground/70">— 我截取的片段</p>
          )}
        </div>
      )}
    </div>
  )
}
