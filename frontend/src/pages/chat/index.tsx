import { useEffect, useState, useCallback, useRef } from 'react'
import { ArrowLeft, Search, MessageCircle, ChevronRight } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { useGoBack } from '@/hooks/useGoBack'
import { useScrollRestore } from '@/hooks/useScrollRestore'
import { getConversations, searchConversations } from '@/api/chat'
import { useChatStore } from '@/store/chat'
import { chatWebSocketService } from '@/services/chatWebSocket'
import { formatRelativeTime } from '@/utils/time'

export default function ChatListPage() {
  const navigate = useNavigate()
  const goBack = useGoBack()
  const { setConversations, conversations, setSearchKeyword, searchKeyword } = useChatStore()
  const [loading, setLoading] = useState(true)
  const scrollRef = useRef<HTMLDivElement>(null)
  const { handleScroll } = useScrollRestore(scrollRef)

  const loadConversations = useCallback(async () => {
    setLoading(true)
    try {
      const res = await getConversations()
      setConversations((res as any)?.data || res || [])
    } catch { /* ignore */ }
    finally { setLoading(false) }
  }, [setConversations])

  useEffect(() => {
    loadConversations()
  }, [loadConversations])

  useEffect(() => {
    chatWebSocketService.connect()

    return () => {
      chatWebSocketService.disconnect()
    }
  }, [])

  useEffect(() => {
    const handleSearch = async () => {
      if (!searchKeyword.trim()) {
        await loadConversations()
        return
      }
      try {
        const res = await searchConversations(searchKeyword)
        setConversations((res as any)?.data || res || [])
      } catch { /* ignore */ }
    }

    const debounce = setTimeout(handleSearch, 300)
    return () => clearTimeout(debounce)
  }, [searchKeyword, loadConversations, setConversations])

  return (
    <div className="absolute inset-0 flex flex-col overflow-hidden bg-background page-enter">
      <header className="shrink-0 z-10 border-b border-border/50 bg-background/80 backdrop-blur-xl">
        <div className="flex items-center gap-3 px-4 py-3">
          <button onClick={() => goBack()} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted">
            <ArrowLeft className="h-5 w-5" />
          </button>
          <h1 className="text-base font-bold">私信</h1>
        </div>
        <div className="px-4 pb-3">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <input
              type="text"
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
              placeholder="搜索昵称或消息..."
              className="w-full rounded-xl border bg-background pl-9 pr-4 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 transition-shadow"
            />
          </div>
        </div>
      </header>

      <div ref={scrollRef} onScroll={handleScroll} className="flex-1 overflow-y-auto overscroll-contain px-4 py-3">
        {loading ? (
          <div className="flex justify-center py-12">
            <div className="h-6 w-6 animate-spin rounded-full border-3 border-primary border-t-transparent" />
          </div>
        ) : conversations.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-16 text-muted-foreground">
            <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-muted mb-4">
              <MessageCircle className="h-8 w-8 text-muted-foreground/50" />
            </div>
            <p className="text-sm">暂无私信</p>
            <p className="mt-1 text-xs">关注他人后即可发送私信</p>
          </div>
        ) : (
          <div className="space-y-1.5">
            {conversations.map(conv => (
              <button
                key={conv.id}
                onClick={() => navigate(`/chat/${conv.id}`)}
                className="flex w-full items-center gap-3 rounded-2xl bg-card p-3.5 border border-border/30 hover:bg-muted/50 active:scale-[0.99] transition-all"
              >
                <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-primary/10 overflow-hidden">
                  {conv.otherUserAvatar ? (
                    <img src={conv.otherUserAvatar} alt="" className="h-full w-full object-cover" />
                  ) : (
                    <span className="text-base font-bold text-primary">{conv.otherUserNickname?.[0] || 'U'}</span>
                  )}
                </div>
                <div className="flex-1 min-w-0 text-left">
                  <div className="flex items-center justify-between">
                    <p className="text-sm font-semibold truncate">{conv.otherUserNickname}</p>
                    <span className="text-xs text-muted-foreground">{conv.updatedAt ? formatRelativeTime(conv.updatedAt) : ''}</span>
                  </div>
                  <div className="flex items-center justify-between mt-0.5">
                    <p className="text-xs text-muted-foreground truncate max-w-[200px]">
                      {conv.lastMessage || '暂无消息'}
                    </p>
                    {conv.unreadCount > 0 && (
                      <span className="flex h-5 min-w-[20px] items-center justify-center rounded-full bg-primary text-[10px] font-bold text-primary-foreground px-1.5">
                        {conv.unreadCount > 99 ? '99+' : conv.unreadCount}
                      </span>
                    )}
                  </div>
                </div>
                <ChevronRight className="h-4 w-4 text-muted-foreground shrink-0" />
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}