import { useState } from 'react'
import { Plus, X } from 'lucide-react'
import { cn } from '@/utils/cn'

interface Props {
  value: string[]
  onChange: (tags: string[]) => void
  max?: number
  placeholder?: string
  className?: string
}

/** 轻量标签输入：回车/逗号添加，失焦时也会提交当前输入 */
export function TagInput({ value, onChange, max = 8, placeholder = '回车添加标签', className }: Props) {
  const [draft, setDraft] = useState('')

  const commit = (raw = draft) => {
    const tag = raw.replace(/[,，]/g, ' ').trim().slice(0, 20)
    setDraft('')
    if (!tag || value.includes(tag) || value.length >= max) return
    onChange([...value, tag])
  }

  const remove = (tag: string) => onChange(value.filter((t) => t !== tag))

  return (
    <div className={cn('rounded-xl border border-input bg-background px-3 py-2', className)}>
      <div className="flex flex-wrap items-center gap-1.5">
        {value.map((tag) => (
          <span
            key={tag}
            className="flex items-center gap-1 rounded-full bg-primary/10 py-0.5 pl-2 pr-1 text-xs font-medium text-primary"
          >
            {tag}
            <button
              type="button"
              onClick={() => remove(tag)}
              className="flex h-4 w-4 items-center justify-center rounded-full transition-colors hover:bg-primary/20"
              aria-label={`移除标签 ${tag}`}
            >
              <X className="h-2.5 w-2.5" />
            </button>
          </span>
        ))}
        {value.length < max && (
          <input
            value={draft}
            onChange={(e) => {
              const v = e.target.value
              if (/[,，]/.test(v)) commit(v)
              else setDraft(v)
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                commit()
              }
            }}
            onBlur={() => commit()}
            placeholder={value.length ? '' : placeholder}
            maxLength={20}
            className="min-w-[80px] flex-1 bg-transparent py-0.5 text-sm outline-none placeholder:text-muted-foreground/60"
          />
        )}
        {draft.trim() && (
          <button
            type="button"
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => commit()}
            className="flex h-6 w-6 items-center justify-center rounded-lg bg-muted text-muted-foreground"
            aria-label="添加标签"
          >
            <Plus className="h-3.5 w-3.5" />
          </button>
        )}
      </div>
    </div>
  )
}
