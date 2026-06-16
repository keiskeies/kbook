import { toast } from 'sonner'
import { Sparkles } from 'lucide-react'
import type { UserInfo } from '@/store/auth'
import { Sheet, SheetContent, SheetTitle, SheetDescription } from '@/components/ui/sheet'
import MobileSheetHeader from '@/components/common/MobileSheetHeader'

const BOOK_CHAT_STYLES = [
  { value: 'CASUAL', label: '随和聊天', desc: '口语化，像朋友在聊书' },
  { value: 'DEEP', label: '深度分析', desc: '结构化解读，认真钻研' },
  { value: 'CONCISE', label: '简洁直接', desc: '要言不烦，直击重点' },
  { value: 'WITTY', label: '幽默风趣', desc: '轻松调侃，边读边乐' },
]

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  userInfo: UserInfo | null
  updateUserInfo: (data: Partial<UserInfo>) => void
  isMobile: boolean
  sheetSide: 'bottom' | 'right'
}

export default function ProfileStylePickerSheet({ open, onOpenChange, userInfo, updateUserInfo, isMobile, sheetSide }: Props) {
  const currentStyle = userInfo?.bookChatStyle || 'DEEP'

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side={sheetSide} className={`p-5 [&>button]:hidden ${isMobile ? 'max-h-[85vh] rounded-t-2xl' : 'h-full sm:max-w-xl rounded-l-2xl'}`}>
        <SheetTitle className="sr-only">AI 对话风格</SheetTitle>
        <SheetDescription className="sr-only">选择 AI 图书问答的语气风格</SheetDescription>
        <MobileSheetHeader
          icon={<Sparkles className="h-5 w-5 text-primary" />}
          title="AI 对话风格"
          description="选择 AI 图书问答的语气风格"
          onClose={() => onOpenChange(false)}
        />
        <div className="grid grid-cols-2 gap-2">
          {BOOK_CHAT_STYLES.map(s => {
            const isActive = currentStyle === s.value
            return (
              <button
                key={s.value}
                onClick={async () => {
                  try {
                    const { updateBookChatStyle } = await import('@/api/auth')
                    await updateBookChatStyle(s.value)
                    updateUserInfo({ bookChatStyle: s.value })
                    onOpenChange(false)
                    toast.success(`已切换为「${s.label}」风格`)
                  } catch { toast.error('切换失败') }
                }}
                className={`text-left rounded-xl px-3.5 py-3 text-sm transition-colors border border-border ${
                  isActive ? 'bg-primary text-primary-foreground' : 'bg-background text-foreground hover:bg-muted'
                }`}
              >
                <div className="font-medium">{s.label}</div>
                <div className={`text-xs mt-0.5 ${isActive ? 'opacity-70' : 'text-muted-foreground'}`}>{s.desc}</div>
              </button>
            )
          })}
        </div>
      </SheetContent>
    </Sheet>
  )
}
