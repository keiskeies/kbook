import { useLocation } from 'react-router-dom'
import { PenLine } from 'lucide-react'
import { useNoteComposerStore } from '@/store/noteComposer'
import { ROUTES } from '@/constants'

/** 全局悬浮「记一笔」— 位于 AI 助手按钮上方，避免遮挡 */
export function QuickCaptureFab() {
  const openComposer = useNoteComposerStore((s) => s.openComposer)
  const location = useLocation()

  // 笔记页自带入口，悬浮按钮反而遮挡内容；后台是工作台，不放个人记录入口
  if (location.pathname.startsWith(ROUTES.NOTES) || location.pathname.startsWith('/admin')) return null

  return (
    <button
      onClick={() => openComposer()}
      title="记一笔（快捷键 N）"
      aria-label="记一笔"
      className="fixed bottom-[calc(env(safe-area-inset-bottom,0px)+10.5rem)] right-4 z-40 flex h-11 w-11 items-center justify-center rounded-full bg-primary text-primary-foreground shadow-lg shadow-primary/25 transition-transform hover:scale-105 active:scale-95 md:bottom-[168px] md:right-6 md:h-12 md:w-12"
    >
      <PenLine className="h-5 w-5" />
    </button>
  )
}
