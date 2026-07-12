import { History } from 'lucide-react'
import type { AiSessionItem } from '@/types/ai'

interface ChatHistoryPanelProps {
  showHistory: boolean
  historySessions: AiSessionItem[]
  currentSessionId: string | null
  onSessionClick: (sessionId: string, title?: string) => void
  onClose: () => void
  formatTime: (dateStr: string) => string
}

export default function ChatHistoryPanel({
  showHistory,
  historySessions,
  currentSessionId,
  onSessionClick,
  onClose,
  formatTime,
}: ChatHistoryPanelProps) {
  if (!showHistory) return null

  return (
    <div className="flex-1 overflow-y-auto overscroll-y-contain">
      <div className="flex items-center justify-between border-b px-4 py-2">
        <span className="text-sm font-medium">历史问答</span>
        <button onClick={onClose} className="text-xs text-muted-foreground">
          关闭
        </button>
      </div>
      {historySessions.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-12 text-muted-foreground">
          <History className="mb-2 h-8 w-8 opacity-40" />
          <p className="text-sm">暂无历史问答记录</p>
        </div>
      ) : (
        <div className="divide-y">
          {historySessions.map((session) => (
            <button
              key={session.id}
              className={`w-full px-4 py-3 text-left transition-colors hover:bg-muted/50 ${
                session.sessionId === currentSessionId ? 'bg-muted' : ''
              }`}
              onClick={() => onSessionClick(session.sessionId, session.title)}
            >
              <p className="truncate text-sm font-medium">{session.title || '未命名对话'}</p>
              <p className="mt-0.5 text-xs text-muted-foreground">{formatTime(session.updatedAt)}</p>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
