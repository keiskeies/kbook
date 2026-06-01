import { useEffect, useState, useCallback, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useGoBack } from '@/hooks/useGoBack'
import { useScrollRestore } from '@/hooks/useScrollRestore'
import { ArrowLeft, Bell, Heart, Bookmark, MessageCircle, BookOpen, CheckCheck } from 'lucide-react'
import { getNotifications, markAsRead, markAllAsRead } from '@/api/notification'
import type { NotificationVO } from '@/api/notification'
import { formatRelativeTime } from '@/utils/time'
import { toast } from 'sonner'

const typeConfig: Record<string, { icon: typeof Heart; label: string; color: string }> = {
  COMMENT_REPLY: { icon: MessageCircle, label: '回复了你的评论', color: 'text-info' },
  COMMENT_LIKED: { icon: Heart, label: '赞了你的评论', color: 'text-danger' },
  COMMENT_FAVORITED: { icon: Bookmark, label: '收藏了你的评论', color: 'text-warning' },
  NEW_REVIEW: { icon: BookOpen, label: '发表了新书评', color: 'text-success' },
}

export default function NotificationsPage() {
  const navigate = useNavigate()
  const goBack = useGoBack()
  const [notifications, setNotifications] = useState<NotificationVO[]>([])
  const [loading, setLoading] = useState(true)
  const [page, setPage] = useState(1)
  const [hasMore, setHasMore] = useState(true)
  const scrollRef = useRef<HTMLDivElement>(null)
  const { handleScroll } = useScrollRestore(scrollRef)

  const loadData = useCallback(async (p: number) => {
    try {
      const res = await getNotifications(p, 30)
      const data = (res as any)?.data || (res as any)
      if (data?.list) {
        setNotifications(prev => p === 1 ? data.list : [...prev, ...data.list])
        setHasMore(data.list.length >= 30)
      }
    } catch { /* ignore */ } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { loadData(1) }, [loadData])

  const handleMarkRead = async (id: number) => {
    try {
      await markAsRead(id)
      setNotifications(prev => prev.map(n => n.id === id ? { ...n, isRead: true } : n))
    } catch { /* ignore */ }
  }

  const handleMarkAllRead = async () => {
    try {
      await markAllAsRead()
      setNotifications(prev => prev.map(n => ({ ...n, isRead: true })))
      toast.success('已全部标记为已读')
    } catch {
      toast.error('操作未完成')
    }
  }

  const handleClick = (n: NotificationVO) => {
    if (!n.isRead) handleMarkRead(n.id)
    if (n.bookId) navigate(`/book/${n.bookId}`)
  }

  if (loading) {
    return (
      <div className="fixed inset-0 flex items-center justify-center bg-background">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent" />
      </div>
    )
  }

  return (
    <div className="fixed inset-0 flex flex-col overflow-hidden bg-background page-enter overscroll-contain">
      <header className="shrink-0 flex items-center justify-between border-b border-border/50 bg-background/80 px-4 py-3 backdrop-blur-xl z-20">
        <div className="flex items-center gap-3">
          <button onClick={() => goBack()} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted">
            <ArrowLeft className="h-5 w-5" />
          </button>
          <h1 className="text-base font-bold">通知</h1>
        </div>
        {notifications.some(n => !n.isRead) && (
          <button onClick={handleMarkAllRead} className="flex items-center gap-1 text-xs text-primary font-medium">
            <CheckCheck className="h-3.5 w-3.5" />
            全部已读
          </button>
        )}
      </header>

      <div ref={scrollRef} onScroll={handleScroll} className="flex-1 overflow-y-auto overscroll-contain px-4">
        {notifications.length === 0 ? (
          <div className="py-16 text-center text-sm text-muted-foreground">
            <Bell className="mx-auto h-10 w-10 text-muted-foreground/30" />
            <p className="mt-3">暂无通知</p>
          </div>
        ) : (
          <div className="divide-y divide-border/50">
            {notifications.map(n => {
              const config = typeConfig[n.type] || { icon: Bell, label: n.type, color: 'text-muted-foreground' }
              const Icon = config.icon
              return (
                <button
                  key={n.id}
                  onClick={() => handleClick(n)}
                  className={`flex w-full items-start gap-3 py-3.5 text-left transition-colors ${!n.isRead ? 'bg-primary/3' : ''}`}
                >
                  <div className={`mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full ${n.isRead ? 'bg-muted' : 'bg-primary/10'}`}>
                    <Icon className={`h-4 w-4 ${n.isRead ? 'text-muted-foreground' : config.color}`} />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-sm">
                      <span className="font-semibold">{n.triggerUserNickname || '用户'}</span>
                      <span className="text-muted-foreground"> {config.label}</span>
                    </p>
                    <p className="mt-0.5 text-[10px] text-muted-foreground">{formatRelativeTime(n.createdAt)}</p>
                  </div>
                  {!n.isRead && <div className="mt-2 h-2 w-2 rounded-full bg-primary" />}
                </button>
              )
            })}
          </div>
        )}

        {hasMore && notifications.length > 0 && (
          <button
            onClick={() => { const next = page + 1; setPage(next); loadData(next) }}
            className="mt-4 mb-8 w-full rounded-xl bg-muted py-2.5 text-sm font-medium text-muted-foreground"
          >
            加载更多
          </button>
        )}
      </div>
    </div>
  )
}
