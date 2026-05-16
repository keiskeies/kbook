import { useState, useRef, useEffect, useCallback } from 'react'
import { Send, Loader2, Bot, User, Sparkles, RefreshCw, Copy, Check } from 'lucide-react'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from '@/components/ui/sheet'
import { streamBookChat, getBookSuggestedQuestions } from '@/api/bookChat'
import MarkdownRenderer from '@/components/ui/markdown-renderer'
import ThinkingBlock from '@/components/ui/thinking-block'
import type { AiMessage } from '@/types/ai'
import type { Book } from '@/types/book'

interface BookChatSheetProps {
  book: Book
  open: boolean
  onOpenChange: (open: boolean) => void
}

export default function BookChatSheet({ book, open, onOpenChange }: BookChatSheetProps) {
  const [messages, setMessages] = useState<AiMessage[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [suggestions, setSuggestions] = useState<string[]>([])
  const [sessionId, setSessionId] = useState<string>('')
  const [copiedId, setCopiedId] = useState<string | null>(null)
  const [chatTitle, setChatTitle] = useState<string>('')
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const abortRef = useRef<AbortController | null>(null)

  // 加载推荐问题
  useEffect(() => {
    if (open && book.id) {
      getBookSuggestedQuestions(book.id)
        .then((res) => {
          const data = (res as any)?.data || (res as any)
          if (Array.isArray(data)) setSuggestions(data)
        })
        .catch(() => {})
    }
  }, [open, book.id])

  // 滚动到底部
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  // 关闭时中断请求
  useEffect(() => {
    if (!open && abortRef.current) {
      abortRef.current.abort()
      abortRef.current = null
    }
  }, [open])

  // 关闭时重置状态
  const handleClose = useCallback(() => {
    if (abortRef.current) {
      abortRef.current.abort()
      abortRef.current = null
    }
    setMessages([])
    setInput('')
    setLoading(false)
    setSessionId('')
    setChatTitle('')
    onOpenChange(false)
  }, [onOpenChange])

  const handleSend = useCallback(async (text?: string) => {
    const message = (text || input).trim()
    if (!message || loading) return

    // 中断之前未完成的流，防止并发
    if (abortRef.current) {
      abortRef.current.abort()
      abortRef.current = null
    }

    // 添加用户消息
    const userMsg: AiMessage = {
      id: `u-${Date.now()}`,
      role: 'user',
      content: message,
      timestamp: Date.now(),
    }
    setMessages((prev) => [...prev, userMsg])
    
    // 设置会话标题：书籍名称-第一句问题
    if (!chatTitle) {
      const questionPreview = message.length > 15 ? message.slice(0, 15) + '...' : message
      setChatTitle(`${book.title}-${questionPreview}`)
    }
    
    setInput('')
    setLoading(true)

    // AI 占位消息
    const assistantMsg: AiMessage = {
      id: `a-${Date.now()}`,
      role: 'assistant',
      content: '',
      timestamp: Date.now(),
      streaming: true,
    }
    setMessages((prev) => [...prev, assistantMsg])

    // 流式请求
    const controller = streamBookChat(
      book.id,
      { message, sessionId: sessionId || undefined },
      (chunk) => {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantMsg.id ? { ...m, content: m.content + chunk, thinkingStatus: undefined } : m
          )
        )
      },
      () => {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantMsg.id ? { ...m, streaming: false, thinkingStatus: undefined } : m
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
  }, [input, loading, book.id, sessionId])

  /** 重新生成最后一条 AI 回答 */
  const handleRegenerate = useCallback(() => {
    if (loading) return

    // 1. 先中断当前正在进行的流
    if (abortRef.current) {
      abortRef.current.abort()
      abortRef.current = null
    }

    // 2. 找到最后一条 assistant 消息及其对应的 user 消息
    let lastAssistantIdx = -1
    for (let i = messages.length - 1; i >= 0; i--) {
      if (messages[i].role === 'assistant' && !messages[i].streaming) {
        lastAssistantIdx = i
        break
      }
    }
    if (lastAssistantIdx === -1) return

    // 获取要重发的问题
    let userMsgContent = ''
    if (lastAssistantIdx > 0 && messages[lastAssistantIdx - 1].role === 'user') {
      userMsgContent = messages[lastAssistantIdx - 1].content
    }

    // 截断消息（删除最后的 user + assistant 对）
    const cutIdx = lastAssistantIdx > 0 && messages[lastAssistantIdx - 1].role === 'user'
      ? lastAssistantIdx - 1
      : lastAssistantIdx
    const newMessages = messages.slice(0, cutIdx)
    setMessages(newMessages)

    // 3. 在下一帧重新发送（确保 setMessages 已生效）
    if (userMsgContent) {
      // 用 requestAnimationFrame 确保 React 完成渲染后再发送
      requestAnimationFrame(() => {
        handleSend(userMsgContent)
      })
    }
  }, [messages, loading, handleSend])

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  const hasMessages = messages.length > 0

  return (
    <Sheet open={open} onOpenChange={(v) => { if (!v) handleClose(); else onOpenChange(true) }}>
      <SheetContent side="bottom" className="h-[85vh] rounded-t-2xl border-t p-0 flex flex-col">
        {/* Header */}
        <SheetHeader className="shrink-0 border-b px-4 py-3">
          <div className="flex items-center gap-3">
            <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-primary/10">
              <Sparkles className="h-5 w-5 text-primary" />
            </div>
            <div className="min-w-0 flex-1">
              <SheetTitle className="text-base">{chatTitle || 'AI 书籍问答'}</SheetTitle>
              <SheetDescription className="truncate text-xs">
                基于原著内容回答关于《{book.title}》的问题
              </SheetDescription>
            </div>

          </div>
        </SheetHeader>

        {/* Messages Area */}
        <div className="flex-1 overflow-y-auto px-4 py-4">
          {!hasMessages ? (
            /* 空状态 - 推荐问题 */
            <div className="flex h-full flex-col items-center justify-center text-center">
              <div className="mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-primary/10">
                <Bot className="h-7 w-7 text-primary" />
              </div>
              <h3 className="mb-1 text-base font-medium">向 AI 提问关于这本书</h3>
              <p className="mb-5 text-sm text-muted-foreground">
                主旨、人物、情节、思想... 基于原著回答
              </p>
              {suggestions.length > 0 && (
                <div className="flex flex-col gap-2 w-full max-w-xs">
                  {suggestions.map((hint, i) => (
                    <button
                      key={i}
                      className="w-full rounded-xl border border-border/50 bg-card px-4 py-3 text-left text-sm transition-colors hover:border-primary/30 hover:bg-primary/5 active:scale-[0.98]"
                      onClick={() => handleSend(hint)}
                      disabled={loading}
                    >
                      <span className="text-primary mr-2 font-medium">{i + 1}.</span>
                      {hint}
                    </button>
                  ))}
                </div>
              )}
            </div>
          ) : (
            /* 消息列表 */
            <div className="space-y-4 pb-4">
              {messages.map((msg) => (
                <div
                  key={msg.id}
                  className={`flex gap-2.5 ${msg.role === 'user' ? 'flex-row-reverse' : 'flex-row'}`}
                >
                  {/* 头像 */}
                  <div
                    className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full ${
                      msg.role === 'user' ? 'bg-primary text-primary-foreground' : 'bg-muted'
                    }`}
                  >
                    {msg.role === 'user' ? (
                      <User className="h-3.5 w-3.5" />
                    ) : (
                      <Bot className="h-3.5 w-3.5" />
                    )}
                  </div>

                  {/* 消息气泡 */}
                  <div className="max-w-[80%]">
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
                          <MarkdownRenderer content={msg.content} className="text-sm text-justify" />
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
                    {/* AI 回答操作按钮 */}
                    {msg.role === 'assistant' && !msg.streaming && msg.content && (
                      <div className="mt-1.5 flex items-center gap-1 px-1">
                        <button
                          className="flex h-7 items-center gap-1 rounded-md px-2 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground active:scale-95"
                          onClick={() => handleRegenerate()}
                          disabled={loading}
                          title="重新生成"
                        >
                          <RefreshCw className="h-3.5 w-3.5" />
                          <span>重新生成</span>
                        </button>
                        <button
                          className="flex h-7 items-center gap-1 rounded-md px-2 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground active:scale-95"
                          onClick={() => {
                            navigator.clipboard.writeText(msg.content)
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
                      </div>
                    )}
                  </div>
                </div>
              ))}
              <div ref={messagesEndRef} />
            </div>
          )}
        </div>

        {/* 剩余推荐问题（对话进行中） */}
        {hasMessages && suggestions.length > 0 && !loading && (
          <div className="shrink-0 border-t px-4 py-2">
            <div className="flex gap-2 overflow-x-auto pb-1 scrollbar-none">
              {suggestions.slice(0, 3).map((hint, i) => (
                <button
                  key={i}
                  className="shrink-0 rounded-full border border-border/50 bg-card px-3 py-1.5 text-xs transition-colors hover:border-primary/30 hover:bg-primary/5"
                  onClick={() => handleSend(hint)}
                >
                  {hint}
                </button>
              ))}
            </div>
          </div>
        )}

        {/* Input Area */}
        <div className="shrink-0 border-t bg-background px-4 py-3 pb-safe-bottom">
          <div className="flex items-center gap-2">
            <input
              type="text"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder={`问关于《${book.title.length > 10 ? book.title.slice(0, 10) + '...' : book.title}》的问题...`}
              disabled={loading}
              className="flex-1 rounded-full bg-muted px-4 py-2.5 text-sm outline-none placeholder:text-muted-foreground disabled:opacity-50"
            />
            <button
              onClick={() => handleSend()}
              disabled={loading || !input.trim()}
              className="flex h-10 w-10 items-center justify-center rounded-full bg-primary text-primary-foreground disabled:opacity-50 transition-transform active:scale-95"
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
