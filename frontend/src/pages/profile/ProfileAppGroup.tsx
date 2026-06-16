import { Palette, Info, Shield, ChevronRight } from 'lucide-react'
import { ROUTES } from '@/constants'
import { ThemeToggle } from '@/components/ThemeToggle'

interface Props {
  onNavigate: (path: string) => void
}

export default function ProfileAppGroup({ onNavigate }: Props) {
  const items = [
    {
      label: '关于',
      icon: Info,
      onClick: () => onNavigate(ROUTES.TERMS),
    },
    {
      label: '隐私政策',
      icon: Shield,
      onClick: () => onNavigate(ROUTES.PRIVACY),
    },
  ]

  return (
    <div className="rounded-2xl bg-card shadow-sm border border-border/50 overflow-hidden">
      {/* Theme toggle row — special: no ChevronRight, has ThemeToggle */}
      <div className="flex items-center justify-between px-4 py-3.5 border-b border-border/50">
        <div className="flex items-center gap-3">
          <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-muted">
            <Palette className="h-3.5 w-3.5 text-muted-foreground" />
          </div>
          <span className="text-sm font-medium">主题模式</span>
        </div>
        <ThemeToggle />
      </div>

      {items.map((item, i) => {
        const Icon = item.icon
        return (
          <button
            key={item.label}
            onClick={item.onClick}
            className={`flex w-full items-center justify-between px-4 py-3.5 active:bg-muted/50 md:hover:bg-muted/50 transition-colors ${
              i < items.length - 1 ? 'border-b border-border/50' : ''
            }`}
          >
            <div className="flex items-center gap-3">
              <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-muted">
                <Icon className="h-3.5 w-3.5 text-muted-foreground" />
              </div>
              <span className="text-sm font-medium">{item.label}</span>
            </div>
            <ChevronRight className="h-4 w-4 text-muted-foreground" />
          </button>
        )
      })}
    </div>
  )
}
