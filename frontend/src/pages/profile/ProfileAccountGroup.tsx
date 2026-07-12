import { UserCircle, Lock, ChevronRight } from 'lucide-react'
import { ROUTES } from '@/constants'
import { Card } from '@/components/ui/card'

interface Props {
  onNavigate: (path: string) => void
  onOpenTraits: () => void
}

export default function ProfileAccountGroup({ onNavigate, onOpenTraits }: Props) {
  const items = [
    {
      label: '编辑画像',
      icon: UserCircle,
      onClick: onOpenTraits,
    },
    {
      label: '修改密码',
      icon: Lock,
      onClick: () => onNavigate(ROUTES.CHANGE_PASSWORD),
    },
  ]

  return (
    <Card padding="none" className="overflow-hidden">
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
    </Card>
  )
}
