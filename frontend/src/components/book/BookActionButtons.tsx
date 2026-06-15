import { useNavigate } from 'react-router-dom'
import { MessageCircle, Users, MessageSquare } from 'lucide-react'

interface BookActionButtonsProps {
  bookId: string | number
}

export function BookActionButtons({ bookId }: BookActionButtonsProps) {
  const navigate = useNavigate()

  return (
    <div className="flex items-center justify-end gap-1.5">
      <button
        onClick={() => navigate(`/book/${bookId}?mode=qa`)}
        className="flex items-center gap-1 rounded-lg bg-primary/10 px-2 py-1 text-xs font-medium text-primary hover:bg-primary/20 transition-colors"
      >
        <MessageCircle className="h-3 w-3" />
        AI问答
      </button>
      <button
        onClick={() => navigate(`/book/${bookId}/round-table`)}
        className="flex items-center gap-1 rounded-lg bg-violet-500/10 px-2 py-1 text-xs font-medium text-violet-600 hover:bg-violet-500/20 transition-colors"
      >
        <Users className="h-3 w-3" />
        圆桌
      </button>
      <button
        onClick={() => navigate(`/book/${bookId}/debate`)}
        className="flex items-center gap-1 rounded-lg bg-brand-400/10 px-2 py-1 text-xs font-medium text-brand-500 hover:bg-brand-400/20 transition-colors"
      >
        <MessageSquare className="h-3 w-3" />
        辩论
      </button>
    </div>
  )
}