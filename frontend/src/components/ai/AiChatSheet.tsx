import { useState, useRef, useEffect, useCallback } from 'react'
import { Send, Loader2, Bot, User, RefreshCw, Copy, Check, History, Plus } from 'lucide-react'
import { Sheet, SheetContent, SheetTitle, SheetDescription } from '@/components/ui/sheet'
import MobileSheetHeader from '@/components/common/MobileSheetHeader'
import { streamChat, getHistory, getSessions } from '@/api/ai'
import MarkdownRenderer from '@/components/ui/markdown-renderer'
import ThinkingBlock from '@/components/ui/thinking-block'
import { BlinkingBot } from '@/components/BlinkingBot'
import type { AiMessage } from '@/types/ai'
import type { AiSessionItem } from '@/types/ai'

interface AiChatSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export default function AiChatSheet({ open, onOpenChange }: AiChatSheetProps) {
  const [messages, setMessages] = useState<AiMessage[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [sessionId, setSessionId] = useState<string>('')
  const [copiedId, setCopiedId] = useState<string | null>(null)
  const [showHistory, setShowHistory] = useState(false)
  const [historySessions, setHistorySessions] = useState<AiSessionItem[]>([])
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const scrollContainerRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  const followUpsFromSseRef = useRef(false)
  const userScrollingRef = useRef(false)

  // Responsive side: bottom on mobile, right on PC
  const [side, setSide] = useState<'bottom' | 'right'>('bottom')

  useEffect(() => {
    const mq = window.matchMedia('(min-width: 768px)')
    const update = () => setSide(mq.matches ? 'right' : 'bottom')
    update()
    mq.addEventListener('change', update)
    return () => mq.removeEventListener('change', update)
  }, [])

  // Load sessions when opened
  useEffect(() => {
    if (open) {
      loadHistorySessions()
    }
  }, [open])

  // Auto-scroll on streaming messages
  useEffect(() => {
    const streaming = messages.some((m) => m.streaming)
    if (streaming && !userScrollingRef.current) {
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
  }, [messages])

  // Abort stream on close
  useEffect(() => {
    if (!open && abortRef.current) {
      abortRef.current.abort()
      abortRef.current = null
    }
  }, [open])

  // Reset textarea height when input is cleared
  useEffect(() => {
    if (!input && textareaRef.current) {
      textareaRef.current.style.height = 'auto'
    }
  }, [input])

  // Auto-load latest session when opening with no messages
  useEffect(() => {
    if (open && messages.length === 0 && historySessions.length > 0) {
      let latestSession: AiSessionItem | null = null
      let latestTime = 0
      for (const session of historySessions) {
        const sessionTime = new Date(session.updatedAt || session.createdAt).getTime()
        if (sessionTime > latestTime) {
          latestTime = sessionTime
          latestSession = session
        }
      }
      if (latestSession && latestSession.sessionId) {
        loadSessionHistory(latestSession.sessionId)
      }
    }
  }, [open, historySessions])

  const handleUserScroll = () => {
    const container = scrollContainerRef.current
    if (!container) return
    const { scrollTop, scrollHeight, clientHeight } = container
    const isAtBottom = scrollHeight - scrollTop - clientHeight < 50
    userScrollingRef.current = !isAtBottom
  }

  const loadHistorySessions = async () => {
    try {
      const res = await getSessions()
      const data = (res as any)?.data || (res as any)
      if (Array.isArray(data)) {
        setHistorySessions(data)
      }
    } catch { /* ignore */ }
  }

  const loadSessionHistory = async (targetSessionId: string) => {
    try {
      const res = await getHistory(targetSessionId)
      const data = (res as any)?.data || (res as any)
      if (Array.isArray(data)) {
        const history: AiMessage[] = data
          .filter((r: any) => r.role === 'user' || r.role === 'assistant')
          .map((r: any) => {
            let followUps: string[] | undefined
            if (r.followUpQuestions) {
              try { followUps = JSON.parse(r.followUpQuestions) } catch { /* ignore */ }
            }
            return {
              id: String(r.id),
              role: r.role as 'user' | 'assistant',
              content: r.content,
              timestamp: new Date(r.createdAt).getTime(),
              thinkingContent: r.thinkingContent || undefined,
              followUpQuestions: followUps,
            }
          })
        setMessages(history)
        setSessionId(targetSessionId)
        setShowHistory(false)
        setTimeout(() => {
          messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
        }, 100)
      }
    } catch { /* ignore */ }
  }

  const handleClose = useCallback(() => {
    if (abortRef.current) {
      abortRef.current.abort()
      abortRef.current = null
    }
    setMessages([])
    setInput('')
    setLoading(false)
    setSessionId('')
    setShowHistory(false)
    onOpenChange(false)
  }, [onOpenChange])

  const handleNewChat = useCallback(() => {
    if (abortRef.current) {
      abortRef.current.abort()
      abortRef.current = null
    }
    setMessages([])
    setSessionId('')
    setLoading(false)
    setShowHistory(false)
  }, [])

  const handleSend = useCallback(async (text?: string) => {
    const message = (text || input).trim()
    if (!message || loading) return

    userScrollingRef.current = false

    if (abortRef.current) {
      abortRef.current.abort()
      abortRef.current = null
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
      userQuestion: message,
    }
    setMessages((prev) => [...prev, assistantMsg])

    let fullAnswerContent = ''

    const controller = streamChat(
      { message, sessionId: sessionId || undefined },
      (chunk) => {
        fullAnswerContent += chunk
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantMsg.id ? { ...m, content: m.content + chunk, thinkingStatus: undefined } : m
          )
        )
      },
      () => {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantMsg.id ? { ...m, streaming: false, thinkingStatus: undefined, loadingFollowUps: false } : m
          )
        )
        setLoading(false)
        followUpsFromSseRef.current = false
        loadHistorySessions()
      },
      () => {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantMsg.id
              ? { ...m, content: '抱歉，AI 助手暂时无法回复，请稍后重试。', streaming: false, thinkingStatus: undefined }
              : m
          )
        )
        setLoading(false)
      },
      (status) => {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantMsg.id ? { ...m, thinkingStatus: status } : m
          )
        )
      },
      (chunk) => {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantMsg.id ? { ...m, thinkingContent: (m.thinkingContent || '') + chunk } : m
          )
        )
      },
    )
    abortRef.current = controller
  }, [input, loading, sessionId])

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
    const newMessages = messages.slice(0, cutIdx)
    setMessages(newMessages)

    if (userMsgContent) {
      requestAnimationFrame(() => {
        handleSend(userMsgContent)
      })
    }
  }, [messages, loading, handleSend])

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

  const hasMessages = messages.length > 0

  return (
    <Sheet open={open} onOpenChange={(v) => { if (!v) handleClose(); else onOpenChange(true) }}>
      <SheetContent
        side={side}
        className={`p-0 flex flex-col [&>button]:hidden ${
          side === 'right'
            ? 'h-full w-full sm:max-w-[500px] rounded-l-2xl border-l'
            : 'h-[85vh] rounded-t-2xl border-t'
        }`}
      >
        <SheetTitle className="sr-only">AI 助手</SheetTitle>
        <SheetDescription className="sr-only">随时向 AI 助手提问</SheetDescription>
        <MobileSheetHeader
          icon={<BlinkingBot className="h-5 w-5 text-primary" />}
          title="AI 助手"
          description="随时提问，智能解答"
          actions={
            <>
              {hasMessages && (
                <button
                  onClick={handleNewChat}
                  className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground"
                  title="新对话"
                >
                  <Plus className="h-4 w-4" />
                </button>
              )}
              <button
                onClick={() => {
                  if (showHistory) {
                    setShowHistory(false)
                  } else {
                    loadHistorySessions()
                    setShowHistory(true)
                  }
                }}
                className={`flex h-8 w-8 items-center justify-center rounded-lg hover:bg-muted ${showHistory ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground'}`}
                title="历史记录"
              >
                <History className="h-4 w-4" />
              </button>
            </>
          }
          onClose={handleClose}
        />

        {showHistory ? (
          <div className="flex-1 overflow-y-auto overscroll-y-contain">
            <div className="flex items-center justify-between border-b px-4 py-2">
              <span className="text-sm font-medium">历史对话</span>
              <button onClick={() => setShowHistory(false)} className="text-xs text-muted-foreground">
                关闭
              </button>
            </div>
            {historySessions.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 text-muted-foreground">
                <History className="mb-2 h-8 w-8 opacity-40" />
                <p className="text-sm">暂无历史对话记录</p>
              </div>
            ) : (
              <div className="divide-y">
                {historySessions.map((session) => (
                  <button
                    key={session.id}
                    className={`w-full px-4 py-3 text-left transition-colors hover:bg-muted/50 ${
                      session.sessionId === sessionId ? 'bg-muted' : ''
                    }`}
                    onClick={() => loadSessionHistory(session.sessionId)}
                  >
                    <p className="truncate text-sm font-medium">{session.title || '未命名对话'}</p>
                    <p className="mt-0.5 text-xs text-muted-foreground">{formatTime(session.updatedAt)}</p>
                  </button>
                ))}
              </div>
            )}
          </div>
        ) : (
          <div
            ref={scrollContainerRef}
            onScroll={handleUserScroll}
            className="flex-1 overflow-y-auto overscroll-y-contain px-4 py-4"
          >
            {!hasMessages ? (
              <div className="flex min-h-full flex-col items-center text-center">
                <div className="my-auto flex flex-col items-center">
                  <div className="mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-primary/10">
                    <Bot className="h-7 w-7 text-primary" />
                  </div>
                  <h3 className="mb-1 text-base font-medium">向 AI 助手提问</h3>
                  <p className="mb-5 text-sm text-muted-foreground">
                    阅读、写作、思考... 随时为你解答
                  </p>
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
                  return messages.map((msg) => (
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
                        <span className="text-xs text-muted-foreground">
                          {msg.role === 'user' ? '你' : 'AI'}
                        </span>
                      </div>
                      <div className={msg.role === 'user' ? 'max-w-[90%]' : 'w-full'}>
                        <div
                          className={`rounded-2xl px-3.5 py-2.5 text-sm ${
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
                              <MarkdownRenderer content={msg.content} className="text-body text-justify" />
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
                        {msg.role === 'assistant' && !msg.streaming && (
                          <div className="mt-1.5 flex items-center justify-between">
                            <div className="flex items-center gap-1">
                              {msg.content && (
                                <button
                                  className="flex h-7 items-center gap-1 rounded-md px-2 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground active:scale-95"
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
                                  title="复制"
                                >
                                  {copiedId === msg.id ? (
                                    <>
                                      <Check className="h-3.5 w-3.5 text-green-500" />
                                      <span className="text-green-500">已复制</span>
                                    </>
                                  ) : (
                                    <>
                                      <Copy className="h-3.5 w-3.5" />
                                      <span>复制</span>
                                    </>
                                  )}
                                </button>
                              )}
                              {msg.id === lastAssistantId && (
                                <button
                                  className="flex h-7 items-center gap-1 rounded-md px-2 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground active:scale-95"
                                  onClick={() => handleRegenerate()}
                                  disabled={loading}
                                  title="重新生成"
                                >
                                  <RefreshCw className="h-3.5 w-3.5" />
                                  <span>重新生成</span>
                                </button>
                              )}
                            </div>
                          </div>
                        )}
                        {msg.role === 'assistant' && !msg.streaming && msg.followUpQuestions && msg.followUpQuestions.length > 0 && (
                          <div className="mt-2 text-left">
                            <p className="mb-1.5 text-xs text-muted-foreground">深入探索</p>
                            <div className="flex flex-wrap justify-start gap-1.5">
                              {msg.followUpQuestions.map((q, qi) => (
                                <button
                                  key={qi}
                                  className="inline-flex items-center rounded-lg border border-primary/20 bg-primary/5 px-2.5 py-1 text-left text-xs text-primary transition-colors hover:bg-primary/10 active:scale-[0.97] whitespace-normal break-words max-w-full"
                                  onClick={() => handleSend(q)}
                                >
                                  {q}
                                </button>
                              ))}
                            </div>
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
        )}

        <div className="shrink-0 border-t bg-background px-4 pt-2 pb-3 pb-safe-bottom">
          <div className="flex items-stretch gap-2">
            <textarea
              ref={textareaRef}
              rows={1}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              enterKeyHint="enter"
              onInput={() => {
                const el = textareaRef.current
                if (!el) return
                el.style.height = 'auto'
                el.style.height = Math.min(el.scrollHeight, 160) + 'px'
              }}
              placeholder="向 AI 助手提问..."
              disabled={loading}
              className="flex-1 resize-none rounded-xl bg-muted px-4 py-2.5 text-sm outline-none placeholder:text-muted-foreground placeholder:truncate disabled:opacity-50 overflow-y-auto"
            />
            <button
              onClick={() => handleSend()}
              disabled={loading || !input.trim()}
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-primary text-primary-foreground disabled:opacity-50 transition-transform active:scale-95"
            >
              {loading ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Send className="h-4 w-4" />
              )}
            </button>
          </div>
        </div>
      </SheetContent>
    </Sheet>
  )
}
