import { useState, type ReactNode } from 'react'
import { ChevronDown } from 'lucide-react'

/**
 * 折叠状态的记忆方式全站只此一份：localStorage 里存 '1'/'0'，没存过视为展开。
 * 主看板的面板与画像抽屉的每个维度都走它，所以同一个 key 的语义在两边一致。
 */
export function useCollapsed(storageKey: string, defaultOpen = true) {
  const [open, setOpen] = useState(() => {
    try {
      const saved = localStorage.getItem(storageKey)
      return saved === null ? defaultOpen : saved === '1'
    } catch {
      return defaultOpen
    }
  })

  const toggle = () => {
    setOpen(prev => {
      const next = !prev
      try {
        localStorage.setItem(storageKey, next ? '1' : '0')
      } catch {
        // 隐私模式或配额满时折叠仍可用，只是不跨刷新
      }
      return next
    })
  }

  return { open, toggle }
}

export function CollapsibleSection({ storageKey, icon, title, action, children }: {
  storageKey: string
  icon: ReactNode
  title: string
  action?: ReactNode
  children: ReactNode
}) {
  const { open, toggle } = useCollapsed(storageKey)

  return (
    <section className="rounded-xl bg-card shadow-xs">
      <div className="flex items-center gap-2 px-4 py-3 md:px-5">
        <button onClick={toggle} aria-expanded={open} className="flex min-w-0 flex-1 items-center gap-2 text-left">
          <span className="text-primary">{icon}</span>
          <h2 className="text-h4">{title}</h2>
          <ChevronDown className={`h-4 w-4 shrink-0 text-muted-foreground transition-transform ${open ? 'rotate-180' : ''}`} />
        </button>
        {action}
      </div>
      {open && (
        <div className="space-y-4 px-4 pb-4 md:px-5 md:pb-5">
          {children}
        </div>
      )}
    </section>
  )
}
