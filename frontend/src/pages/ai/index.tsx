import { useState, useRef, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { Send, Plus, Trash2, MessageSquare, Loader2, Bot, User, RefreshCw, Copy, Check } from 'lucide-react'
import { streamChat, createSession, getHistory, getSessions, deleteSession, getHotPrompts } from '@/api/ai'
import MarkdownRenderer from '@/components/ui/markdown-renderer'
import ThinkingBlock from '@/components/ui/thinking-block'
import type { AiMessage, AiSessionItem } from '@/types/ai'

export default function AIPage() {
  const navigate = useNavigate()
  const [sessions, setSessions] = useState<AiSessionItem[]>([])
  const [currentSessionId, setCurrentSessionId] = useState<string>('')
  const [messages, setMessages] = useState<AiMessage[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [showSidebar, setShowSidebar] = useState(false)
  const [hotPrompts, setHotPrompts] = useState<string[]>([])
  const [copiedId, setCopiedId] = useState<string | null>(null)
  const [bookMap, setBookMap] = useState<Record<string, number>>({})
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    loadSessions().then(() => {})
    loadHotPrompts().then(() => {})
    const handleClick = (e: MouseEvent) => {
      const target = (e.target as HTMLElement).closest('a[data-kbook-nav]')
      if (target) {
        e.preventDefault()
        const path = target.getAttribute('data-kbook-nav')
        if (path) navigate(path)
      }
    }
    document.addEventListener('click', handleClick)
    return () => document.removeEventListener('click', handleClick)
  }, [navigate])

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const loadSessions = async () => {
    try {
      const data = await getSessions()
      const list = (data as any)?.data || data
      if (Array.isArray(list)) setSessions(list)
    } catch { /* ignore */ }
  }

  const loadHotPrompts = async () => {
    try {
      const res = await getHotPrompts(4)
      const data = (res as any)?.data || (res as any)
      if (Array.isArray(data) && data.length > 0) {
        setHotPrompts(data)
      }
    } catch { /* ignore */ }
  }

  const loadHistory = async (sessionId: string) => {
    try {
      const data = await getHistory(sessionId)
      const raw = (data as any)?.data || data
      const history = (Array.isArray(raw) ? raw : [])
        .filter((r: any) => r.role === 'user' || r.role === 'assistant')
        .map((r: any) => ({
          id: String(r.id),
          role: r.role as 'user' | 'assistant',
          content: r.content,
          timestamp: new Date(r.createdAt).getTime(),
          thinkingContent: r.thinkingContent || undefined,
        }))
      setMessages(history)
      setCurrentSessionId(sessionId)
    } catch { /* ignore */ }
  }

  const handleNewChat = async () => {
    try {
      const data = await createSession()
      const sid = (data as any).sessionId || (data as any)?.data?.sessionId
      setCurrentSessionId(sid)
      setMessages([])
      setShowSidebar(false)
      loadSessions()
    } catch { /* ignore */ }
  }

  const handleDeleteSession = async (sessionId: string) => {
    try {
      await deleteSession(sessionId)
      setSessions((prev) => prev.filter((s) => s.sessionId !== sessionId))
      if (currentSessionId === sessionId) {
        setMessages([])
        setCurrentSessionId('')
      }
    } catch { /* ignore */ }
  }

  const handleSend = useCallback(async (text?: string) => {
    const message = (text || input).trim()
    if (!message || loading) return

    let sessionId = currentSessionId
    if (!sessionId) {
      try {
        const data = await createSession()
        sessionId = (data as any).sessionId || (data as any)?.data?.sessionId
        setCurrentSessionId(sessionId)
        loadSessions()
      } catch {
        return
      }
    }

    const userMsg: AiMessage = {
      id: `u-${Date.now()}`,
      role: 'user',
      content: message,
      timestamp: Date.now(),
    }
    setMessages((prev) => [...prev, userMsg])
    setInput('')
    setLoading(true)

    const assistantMsg: AiMessage = {
      id: `a-${Date.now()}`,
      role: 'assistant',
      content: '',
      timestamp: Date.now(),
      streaming: true,
    }
    setMessages((prev) => [...prev, assistantMsg])

    const controller = streamChat(
      { sessionId, message },
      (chunk) => {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantMsg.id
              ? { ...m, content: m.content + chunk, thinkingStatus: undefined }
              : m
          )
        )
      },
      () => {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantMsg.id
              ? { ...m, streaming: false, thinkingStatus: undefined }
              : m
          )
        )
        setLoading(false)
        loadSessions()
      },
      () => {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantMsg.id
              ? { ...m, content: '抱歉，AI 助理暂时无法回复，请稍后重试。', streaming: false, thinkingStatus: undefined }
              : m
          )
        )
        setLoading(false)
      },
      (status) => {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantMsg.id
              ? { ...m, thinkingStatus: status }
              : m
          )
        )
      },
      (chunk) => {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantMsg.id
              ? { ...m, thinkingContent: (m.thinkingContent || '') + chunk }
              : m
          )
        )
      },
      (bookMap) => {
        setBookMap(bookMap)
      },
    )
    abortRef.current = controller as any
  }, [input, loading, currentSessionId])

  const handleRegenerate = useCallback((msgIndex?: number) => {
    if (loading) return
    if (abortRef.current) {
      abortRef.current.abort()
      abortRef.current = null
    }
    let targetIdx = msgIndex ?? -1
    if (targetIdx === -1) {
      for (let i = messages.length - 1; i >= 0; i--) {
        if (messages[i].role === 'assistant' && !messages[i].streaming) {
          targetIdx = i
          break
        }
      }
    }
    if (targetIdx === -1) return
    let userMsgContent = ''
    if (targetIdx > 0 && messages[targetIdx - 1].role === 'user') {
      userMsgContent = messages[targetIdx - 1].content
    }
    const cutIdx = targetIdx > 0 && messages[targetIdx - 1].role === 'user'
      ? targetIdx - 1
      : targetIdx
    setMessages((prev) => prev.slice(0, cutIdx))
    if (userMsgContent) {
      requestAnimationFrame(() => {
        handleSend(userMsgContent)
      })
    }
  }, [loading, messages, handleSend])

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  const formatTime = (dateStr: string) => {
    const d = new Date(dateStr)
    const now = new Date()
    const diffMs = now.getTime() - d.getTime()
    const diffMins = Math.floor(diffMs / 60000)
    if (diffMins < 1) return '刚刚'
    if (diffMins < 60) return `${diffMins}分钟前`
    const diffHours = Math.floor(diffMins / 60)
    if (diffHours < 24) return `${diffHours}小时前`
    const diffDays = Math.floor(diffHours / 24)
    if (diffDays < 7) return `${diffDays}天前`
    return `${d.getMonth() + 1}/${d.getDate()}`
  }

  return (
    <div className="fixed inset-0 z-40 flex flex-col bg-background">
      <header className="flex shrink-0 items-center border-b border-border/50 bg-background/80 px-4 py-3 backdrop-blur-xl">
        <button
          onClick={() => setShowSidebar(!showSidebar)}
          className="mr-3 flex h-8 w-8 items-center justify-center rounded-full hover:bg-muted"
        >
          <MessageSquare className="h-4 w-4" />
        </button>
        <div className="min-w-0 flex-1">
          <h1 className="text-lg font-semibold">AI 阅读助手</h1>
          <p className="text-xs text-muted-foreground">推荐好书，解答疑惑</p>
        </div>
        <button
          onClick={handleNewChat}
          className="ml-2 flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted"
        >
          <Plus className="h-4 w-4" />
        </button>
      </header>

      <div className="relative flex flex-1 overflow-hidden">
        {showSidebar && (
          <div
            className="absolute inset-0 z-20 bg-black/30"
            onClick={() => setShowSidebar(false)}
          />
        )}

        {showSidebar && (
          <div className="absolute inset-y-0 left-0 z-30 w-64 border-r bg-background shadow-lg">
            <div className="flex items-center justify-between border-b px-3 py-2">
              <span className="text-sm font-medium">历史会话</span>
              <button onClick={() => setShowSidebar(false)} className="text-xs text-muted-foreground">
                关闭
              </button>
            </div>
            <div className="overflow-y-auto overscroll-y-contain p-2">
              {sessions.length === 0 ? (
                <p className="py-4 text-center text-xs text-muted-foreground">暂无会话</p>
              ) : (
                sessions.map((session) => (
                  <div
                    key={session.id}
                    className={`group flex items-center gap-2 rounded-lg px-3 py-2 text-sm cursor-pointer hover:bg-muted ${
                      session.sessionId === currentSessionId ? 'bg-muted' : ''
                    }`}
                    onClick={() => {
                      loadHistory(session.sessionId)
                      setShowSidebar(false)
                    }}
                  >
                    <MessageSquare className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
                    <span className="min-w-0 flex-1 truncate text-xs">
                      {session.title || session.sessionId.slice(0, 8) + '...'}
                    </span>
                    <span className="shrink-0 text-[10px] text-muted-foreground">
                      {formatTime(session.updatedAt)}
                    </span>
                    <button
                      onClick={(e) => {
                        e.stopPropagation()
                        handleDeleteSession(session.sessionId)
                      }}
                      className="hidden shrink-0 group-hover:block"
                    >
                      <Trash2 className="h-3.5 w-3.5 text-destructive" />
                    </button>
                  </div>
                ))
              )}
            </div>
          </div>
        )}

        <div className="flex-1 overflow-y-auto px-4 py-4">
          {messages.length === 0 ? (
            <div className="flex h-full flex-col items-center justify-center text-center">
              <div className="mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-primary/10">
                <Bot className="h-8 w-8 text-primary" />
              </div>
              <h3 className="mb-2 text-base font-medium">你好，我是小书 AI 助理</h3>
              <p className="mb-6 text-sm text-muted-foreground">
                推荐好书，解答疑惑，陪你阅读
              </p>
              <div className="flex flex-wrap justify-center gap-2">
                {hotPrompts.map((hint) => (
                  <button
                    key={hint}
                    className="rounded-full border px-4 py-2 text-sm text-muted-foreground transition-colors hover:border-primary hover:text-primary"
                    onClick={() => handleSend(hint)}
                    disabled={loading}
                  >
                    {hint}
                  </button>
                ))}
              </div>
            </div>
          ) : (
            <div className="space-y-4 pb-4">
              {(() => {
                let lastAssistantId = ''
                for (let i = messages.length - 1; i >= 0; i--) {
                  if (messages[i].role === 'assistant') {
                    lastAssistantId = messages[i].id
                    break
                  }
                }
                return messages.map((msg, i) => (
                <div
                  key={msg.id}
                  className={msg.role === 'user' ? 'flex flex-col items-end' : 'flex flex-col'}
                >
                  <div className="flex items-center gap-1.5 mb-1">
                    <div
                      className={`flex h-5 w-5 items-center justify-center rounded-full ${
                        msg.role === 'user' ? 'bg-primary text-primary-foreground' : 'bg-muted'
                      }`}
                    >
                      {msg.role === 'user' ? (
                        <User className="h-3 w-3" />
                      ) : (
                        <Bot className="h-3 w-3" />
                      )}
                    </div>
                    <span className="text-[11px] text-muted-foreground">
                      {msg.role === 'user' ? '你' : 'AI'}
                    </span>
                  </div>
                  <div className={msg.role === 'user' ? 'max-w-[90%]' : 'w-full'}>
                    <div
                      className={`rounded-2xl px-4 py-2.5 text-sm ${
                        msg.role === 'user'
                          ? 'bg-primary text-primary-foreground'
                          : 'bg-muted'
                      }`}
                    >
                      {msg.role === 'user' ? (
                        <p className="whitespace-pre-wrap">{msg.content}</p>
                      ) : (
                        <>
                          {(msg.thinkingContent || (msg.streaming && msg.thinkingStatus && !msg.content)) && (
                            <ThinkingBlock
                              content={msg.thinkingContent || msg.thinkingStatus || ''}
                              streaming={msg.streaming && !msg.content}
                            />
                          )}
                          <MarkdownRenderer content={msg.content} bookMap={bookMap} className="text-sm text-justify" />
                        </>
                      )}
                      {msg.streaming && !msg.content && (
                        <div className="flex items-center gap-2 text-muted-foreground">
                          <Loader2 className="h-3.5 w-3.5 animate-spin" />
                          <span className="text-xs">{msg.thinkingStatus || '思考中...'}</span>
                        </div>
                      )}
                      {msg.streaming && msg.content && (
                        <span className="ml-0.5 inline-flex gap-0.5">
                          <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-foreground/40 [animation-delay:0ms]" />
                          <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-foreground/40 [animation-delay:150ms]" />
                          <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-foreground/40 [animation-delay:300ms]" />
                        </span>
                      )}
                    </div>
                    {msg.role === 'assistant' && !msg.streaming && msg.content && (
                      <div className="mt-1.5 flex items-center gap-1">
                        <button
                          className="flex items-center gap-1 rounded-md px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground active:scale-95"
                          onClick={() => {
                            const idx = messages.indexOf(msg)
                            const userMsg = idx > 0 ? messages[idx - 1] : null
                            const text = userMsg && userMsg.role === 'user'
                              ? `问题：${userMsg.content}\n回答：${msg.content}`
                              : `回答：${msg.content}`
                            navigator.clipboard.writeText(text)
                            setCopiedId(msg.id)
                            setTimeout(() => setCopiedId(null), 2000)
                          }}
                        >
                          {copiedId === msg.id ? (
                            <Check className="h-3 w-3 text-green-500" />
                          ) : (
                            <Copy className="h-3 w-3" />
                          )}
                          {copiedId === msg.id ? '已复制' : '复制'}
                        </button>
                        {msg.id === lastAssistantId && (
                          <button
                            className="flex items-center gap-1 rounded-md px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground active:scale-95"
                            onClick={() => handleRegenerate(i)}
                            disabled={loading}
                          >
                            <RefreshCw className="h-3 w-3" />
                            重新生成
                          </button>
                        )}
                      </div>
                    )}
                  </div>
                </div>
              ))
              })()}
              <div ref={messagesEndRef} />
            </div>
          )}
        </div>
      </div>

      <div className="shrink-0 border-t bg-background px-4 py-3 safe-area-bottom" style={{ paddingBottom: 'calc(0.75rem + 5rem)' }}>
        <div className="flex items-center gap-2">
          <input
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="输入你的问题..."
            disabled={loading}
            className="flex-1 rounded-full bg-muted px-4 py-2.5 text-sm outline-none placeholder:text-muted-foreground disabled:opacity-50"
          />
          <button
            onClick={() => handleSend()}
            disabled={loading || !input.trim()}
            className="flex h-10 w-10 items-center justify-center rounded-full bg-primary text-primary-foreground disabled:opacity-50"
          >
            {loading ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Send className="h-4 w-4" />
            )}
          </button>
        </div>
      </div>
    </div>
  )
}
