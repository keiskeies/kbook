import { useState, useRef, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { Send, Plus, Trash2, MessageSquare, Loader2, Bot, User, RefreshCw, Copy, Check } from 'lucide-react'
import { streamChat, createSession, getHistory, getSessions, deleteSession, getHotPrompts } from '@/api/ai'
import MarkdownRenderer from '@/components/ui/markdown-renderer'
import ThinkingBlock from '@/components/ui/thinking-block'
import type { AiMessage } from '@/types/ai'

export default function AIPage() {
  const navigate = useNavigate()
  const [sessions, setSessions] = useState<string[]>([])
  const [currentSessionId, setCurrentSessionId] = useState<string>('')
  const [messages, setMessages] = useState<AiMessage[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [showSidebar, setShowSidebar] = useState(false)
  const [sessionTitles, setSessionTitles] = useState<Record<string, string>>({})
  const [hotPrompts, setHotPrompts] = useState<string[]>([])
  const [copiedId, setCopiedId] = useState<string | null>(null)
  const [bookMap, setBookMap] = useState<Record<string, number>>({})
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const abortRef = useRef<AbortController | null>(null)

  // 加载会话列表 & 热门问题 & 事件委托处理图书链接点击
  useEffect(() => {
    loadSessions()
    loadHotPrompts()
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

  // 滚动到底部
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const loadSessions = async () => {
    try {
      const data = await getSessions()
      setSessions(data as unknown as string[])
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

  /** 生成会话标题 */
  const generateTitle = (content: string): string => {
    // 去除首尾空白和换行
    const trimmed = content.trim()
    // 如果内容超过20个字符，截取前20个字符并添加省略号
    if (trimmed.length > 20) {
      return trimmed.slice(0, 20) + '...'
    }
    return trimmed || '新会话'
  }

  const loadHistory = async (sessionId: string) => {
    try {
      const data = await getHistory(sessionId)
      const history = (data as any[]).map((r: any) => ({
        id: String(r.id),
        role: r.role as 'user' | 'assistant',
        content: r.content,
        timestamp: new Date(r.createdAt).getTime(),
      }))
      setMessages(history)
      setCurrentSessionId(sessionId)
      
      // 提取会话标题（第一条用户消息）
      const firstUserMsg = history.find((m: AiMessage) => m.role === 'user')
      if (firstUserMsg) {
        const title = generateTitle(firstUserMsg.content)
        setSessionTitles((prev) => ({ ...prev, [sessionId]: title }))
      }
    } catch { /* ignore */ }
  }

  const handleNewChat = async () => {
    try {
      const data = await createSession()
      const sid = (data as any).sessionId
      setCurrentSessionId(sid)
      setMessages([])
      setSessions((prev) => [sid, ...prev])
      setShowSidebar(false)
    } catch { /* ignore */ }
  }

  const handleDeleteSession = async (sessionId: string) => {
    try {
      await deleteSession(sessionId)
      setSessions((prev) => prev.filter((s) => s !== sessionId))
      // 清理标题
      setSessionTitles((prev) => {
        const newTitles = { ...prev }
        delete newTitles[sessionId]
        return newTitles
      })
      if (currentSessionId === sessionId) {
        setMessages([])
        setCurrentSessionId('')
      }
    } catch { /* ignore */ }
  }

  const handleSend = useCallback(async (text?: string) => {
    const message = (text || input).trim()
    if (!message || loading) return

    // 确保有会话
    let sessionId = currentSessionId
    if (!sessionId) {
      try {
        const data = await createSession()
        sessionId = (data as any).sessionId
        setCurrentSessionId(sessionId)
        setSessions((prev) => [sessionId, ...prev])
        // 为新会话设置标题
        setSessionTitles((prev) => ({ ...prev, [sessionId]: generateTitle(message) }))
      } catch {
        return
      }
    } else {
      // 如果当前会话还没有标题，设置标题
      if (!sessionTitles[sessionId]) {
        setSessionTitles((prev) => ({ ...prev, [sessionId]: generateTitle(message) }))
      }
    }

    // 添加用户消息
    const userMsg: AiMessage = {
      id: `u-${Date.now()}`,
      role: 'user',
      content: message,
      timestamp: Date.now(),
    }
    setMessages((prev) => [...prev, userMsg])
    setInput('')
    setLoading(true)

    // 添加 AI 占位消息
    const assistantMsg: AiMessage = {
      id: `a-${Date.now()}`,
      role: 'assistant',
      content: '',
      timestamp: Date.now(),
      streaming: true,
    }
    setMessages((prev) => [...prev, assistantMsg])

    // 流式请求
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
      },
      (error) => {
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
  }, [input, loading, currentSessionId, sessionTitles])

  /** 重新生成最后一条 AI 回答 */
  const handleRegenerate = useCallback(() => {
    if (loading) return
    if (abortRef.current) {
      abortRef.current.abort()
      abortRef.current = null
    }
    let lastAssistantIdx = -1
    for (let i = messages.length - 1; i >= 0; i--) {
      if (messages[i].role === 'assistant' && !messages[i].streaming) {
        lastAssistantIdx = i
        break
      }
    }
    if (lastAssistantIdx === -1) return
    let userMsgContent = ''
    if (lastAssistantIdx > 0 && messages[lastAssistantIdx - 1].role === 'user') {
      userMsgContent = messages[lastAssistantIdx - 1].content
    }
    const cutIdx = lastAssistantIdx > 0 && messages[lastAssistantIdx - 1].role === 'user'
      ? lastAssistantIdx - 1
      : lastAssistantIdx
    setMessages(messages.slice(0, cutIdx))
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

  return (
    <div className="fixed inset-0 z-40 flex flex-col bg-background">
      {/* 标题栏 - 固定在顶部 */}
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
        {/* 侧边栏遮罩 */}
        {showSidebar && (
          <div
            className="absolute inset-0 z-20 bg-black/30"
            onClick={() => setShowSidebar(false)}
          />
        )}

        {/* 侧边栏 - 会话列表 */}
        {showSidebar && (
          <div className="absolute inset-y-0 left-0 z-30 w-64 border-r bg-background shadow-lg">
            <div className="flex items-center justify-between border-b px-3 py-2">
              <span className="text-sm font-medium">历史会话</span>
              <button onClick={() => setShowSidebar(false)} className="text-xs text-muted-foreground">
                关闭
              </button>
            </div>
            <div className="overflow-y-auto p-2">
              {sessions.length === 0 ? (
                <p className="py-4 text-center text-xs text-muted-foreground">暂无会话</p>
              ) : (
                sessions.map((sid) => (
                  <div
                    key={sid}
                    className={`group flex items-center gap-2 rounded-lg px-3 py-2 text-sm cursor-pointer hover:bg-muted ${
                      sid === currentSessionId ? 'bg-muted' : ''
                    }`}
                    onClick={() => {
                      loadHistory(sid)
                      setShowSidebar(false)
                    }}
                  >
                    <MessageSquare className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
                    <span className="min-w-0 flex-1 truncate text-xs">
                      {sessionTitles[sid] || sid.slice(0, 8) + '...'}
                    </span>
                    <button
                      onClick={(e) => {
                        e.stopPropagation()
                        handleDeleteSession(sid)
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

        {/* 消息区域 */}
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
              {messages.map((msg) => (
                <div
                  key={msg.id}
                  className={`flex gap-2.5 ${msg.role === 'user' ? 'flex-row-reverse' : 'flex-row'}`}
                >
                  {/* 头像 */}
                  <div
                    className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full ${
                      msg.role === 'user' ? 'bg-primary text-primary-foreground' : 'bg-muted'
                    }`}
                  >
                    {msg.role === 'user' ? (
                      <User className="h-4 w-4" />
                    ) : (
                      <Bot className="h-4 w-4" />
                    )}
                  </div>

                  {/* 消息气泡 + 操作按钮 — 与图书伴聊结构一致 */}
                  <div className="max-w-[80%]">
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
                    {/* AI 回答操作按钮 — 图标按钮，位于气泡下方 */}
                    {msg.role === 'assistant' && !msg.streaming && msg.content && (
                      <div className="mt-1.5 flex items-center gap-0.5 px-1">
                        <button
                          className="flex h-7 w-7 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground active:scale-95"
                          onClick={handleRegenerate}
                          disabled={loading}
                          title="重新生成"
                        >
                          <RefreshCw className="h-3.5 w-3.5" />
                        </button>
                        <button
                          className="flex h-7 w-7 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground active:scale-95"
                          onClick={() => {
                            navigator.clipboard.writeText(msg.content)
                            setCopiedId(msg.id)
                            setTimeout(() => setCopiedId(null), 2000)
                          }}
                          title="复制"
                        >
                          {copiedId === msg.id ? (
                            <Check className="h-3.5 w-3.5 text-green-500" />
                          ) : (
                            <Copy className="h-3.5 w-3.5" />
                          )}
                        </button>
                      </div>
                    )}
                  </div>
                </div>
              ))}
              <div ref={messagesEndRef} />
            </div>
          )}
        </div>
      </div>

      {/* 输入区域 - 底部留出 TabBar + AI 凸起按钮的空间 */}
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
