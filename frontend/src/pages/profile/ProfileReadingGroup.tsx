import { BookOpen, SlidersHorizontal, Trash2, Sparkles, ChevronRight } from 'lucide-react'
import { ROUTES } from '@/constants'
import { Card } from '@/components/ui/card'

const BOOK_CHAT_STYLES = [
  { value: 'CASUAL', label: '随和聊天', desc: '口语化，像朋友在聊书' },
  { value: 'DEEP', label: '深度分析', desc: '结构化解读，认真钻研' },
  { value: 'CONCISE', label: '简洁直接', desc: '要言不烦，直击重点' },
  { value: 'WITTY', label: '幽默风趣', desc: '轻松调侃，边读边乐' },
]

interface Props {
  totalBooks: number
  trashCount: number
  bookChatStyle: string
  onNavigate: (path: string) => void
  onOpenPreference: () => void
  onOpenStylePicker: () => void
}

export default function ProfileReadingGroup({ totalBooks, trashCount, bookChatStyle, onNavigate, onOpenPreference, onOpenStylePicker }: Props) {
  const items = [
    {
      label: '阅读记录',
      icon: BookOpen,
      extra: `${totalBooks ?? 0}本`,
      onClick: () => onNavigate(ROUTES.READING_LIST),
    },
    {
      label: '阅读偏好',
      icon: SlidersHorizontal,
      onClick: onOpenPreference,
    },
    {
      label: '对话风格',
      icon: Sparkles,
      extra: BOOK_CHAT_STYLES.find(s => s.value === (bookChatStyle || 'DEEP'))?.label || '深度',
      onClick: onOpenStylePicker,
    },
    {
      label: '垃圾桶',
      icon: Trash2,
      extra: trashCount > 0 ? `${trashCount}本` : '',
      onClick: () => onNavigate(ROUTES.TRASH),
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
            <div className="flex items-center gap-2">
              {'extra' in item && item.extra && (
                <span className="text-xs text-muted-foreground">{item.extra}</span>
              )}
              <ChevronRight className="h-4 w-4 text-muted-foreground" />
            </div>
          </button>
        )
      })}
    </Card>
  )
}
