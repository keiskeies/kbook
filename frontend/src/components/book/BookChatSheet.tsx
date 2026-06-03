import { useState, useRef, useEffect, useCallback } from 'react'
import { Send, Loader2, Bot, User, RefreshCw, Copy, Check, History, X, Volume2, Square, Plus, ChevronDown, ChevronUp } from 'lucide-react'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from '@/components/ui/sheet'
import { streamBookChat, getBookSuggestedQuestions, getFollowUpQuestions, getBookChatSessions, getBookChatHistory } from '@/api/bookChat'
import MarkdownRenderer from '@/components/ui/markdown-renderer'
import ThinkingBlock from '@/components/ui/thinking-block'
import { BlinkingBot } from '@/components/layout/TabBar'
import { ttsService } from '@/utils/tts'
import { useTtsStore } from '@/store/tts'
import { getActiveTtsConfig } from '@/api/adminTts'
import { useAuthStore } from '@/store/auth'
import { updateBookChatStyle } from '@/api/auth'
import type { AiMessage } from '@/types/ai'
import type { AiSessionItem } from '@/types/ai'
import type { Book } from '@/types/book'

interface BookChatSheetProps {
  book: Book
  open: boolean
  onOpenChange: (open: boolean) => void
  initialQuestion?: string
  side?: 'bottom' | 'right'
}

const CHAT_STYLES = [
  { value: 'CASUAL', label: '随和', desc: '口语化、像朋友聊天' },
  { value: 'DEEP', label: '深度', desc: '结构化、认真分析' },
  { value: 'CONCISE', label: '简洁', desc: '要言不烦、直击重点' },
  { value: 'WITTY', label: '幽默', desc: '轻松调侃、玩梗' },
]

export default function BookChatSheet({ book, open, onOpenChange, initialQuestion, side = 'bottom' }: BookChatSheetProps) {
  const [messages, setMessages] = useState<AiMessage[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [styleMenuOpen, setStyleMenuOpen] = useState(false)
  const userInfo = useAuthStore((s) => s.userInfo)
  const updateUserInfo = useAuthStore((s) => s.updateUserInfo)
  const currentStyle = userInfo?.bookChatStyle || 'DEEP'
  const currentStyleLabel = CHAT_STYLES.find(s => s.value === currentStyle)?.label || '深度'
  const [suggestions, setSuggestions] = useState<string[]>([])
  const [sessionId, setSessionId] = useState<string>('')
  const [copiedId, setCopiedId] = useState<string | null>(null)
  const [speakingId, setSpeakingId] = useState<string | null>(null)
  const [chatTitle, setChatTitle] = useState<string>('')
  const [showHistory, setShowHistory] = useState(false)
  const [historySessions, setHistorySessions] = useState<AiSessionItem[]>([])
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  const streamSessionIdRef = useRef('')
  const followUpsFromSseRef = useRef(false)
  const initialQuestionRef = useRef(initialQuestion)
  const hasSentInitialRef = useRef(false)

  useEffect(() => {
    initialQuestionRef.current = initialQuestion
  }, [initialQuestion])

  useEffect(() => {
    if (open && book.id) {
      getBookSuggestedQuestions(book.id)
        .then((res) => {
          const data = (res as any)?.data || (res as any)
          if (Array.isArray(data)) setSuggestions(data)
        })
        .catch(() => {})
      loadHistorySessions()

      // 如果有初始问题，自动发送（只发送一次）
      const iq = initialQuestionRef.current
      if (iq && iq.trim() && !hasSentInitialRef.current) {
        hasSentInitialRef.current = true
        // 延迟一点确保 sheet 完全打开
        setTimeout(() => {
          handleSend(iq.trim())
        }, 300)
      }
    }
  }, [open, book.id])

  useEffect(() => {
    const streaming = messages.some((m) => m.streaming)
    if (streaming) {
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
  }, [messages])

  useEffect(() => {
    if (!open && abortRef.current) {
      abortRef.current.abort()
      abortRef.current = null
    }
  }, [open])

  useEffect(() => {
    if (!open && speakingId) {
      ttsService.cancel()
      setSpeakingId(null)
    }
  }, [open, speakingId])

  useEffect(() => {
    if (!input && textareaRef.current) {
      textareaRef.current.style.height = 'auto'
    }
  }, [input])

  const loadHistorySessions = async () => {
    try {
      const res = await getBookChatSessions(book.id)
      const data = (res as any)?.data || (res as any)
      if (Array.isArray(data)) {
        setHistorySessions(data)
      }
    } catch { /* ignore */ }
  }

  const loadSessionHistory = async (targetSessionId: string, title?: string) => {
    try {
      const res = await getBookChatHistory(book.id, targetSessionId)
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
        if (title) setChatTitle(title)
        setShowHistory(false)
      }
    } catch { /* ignore */ }
  }

  const handleClose = useCallback(() => {
    if (abortRef.current) {
      abortRef.current.abort()
      abortRef.current = null
    }
    if (speakingId) {
      ttsService.cancel()
      setSpeakingId(null)
    }
    setMessages([])
    setInput('')
    setLoading(false)
    setSessionId('')
    setChatTitle('')
    setShowHistory(false)
    onOpenChange(false)
  }, [onOpenChange, speakingId])

  const handleNewChat = useCallback(() => {
    if (abortRef.current) {
      abortRef.current.abort()
      abortRef.current = null
    }
    if (speakingId) {
      ttsService.cancel()
      setSpeakingId(null)
    }
    setMessages([])
    setSessionId('')
    setChatTitle('')
    setLoading(false)
    setShowHistory(false)
  }, [speakingId])

  const handleSend = useCallback(async (text?: string, isRegenerate?: boolean) => {
    const message = (text || input).trim()
    if (!message || loading) return

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

    if (!chatTitle) {
      const questionPreview = message.length > 15 ? message.slice(0, 15) + '...' : message
      setChatTitle(`${book.title}-${questionPreview}`)
    }

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

    const controller = streamBookChat(
      book.id,
      { message, sessionId: sessionId || undefined, regenerate: isRegenerate || undefined },
      (chunk) => {
        fullAnswerContent += chunk
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantMsg.id ? { ...m, content: m.content + chunk, thinkingStatus: undefined } : m
          )
        )
      },
      async () => {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantMsg.id ? { ...m, streaming: false, thinkingStatus: undefined } : m
          )
        )
        setLoading(false)

        if (!followUpsFromSseRef.current && fullAnswerContent && assistantMsg.userQuestion) {
          setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantMsg.id ? { ...m, loadingFollowUps: true } : m
            )
          )
          try {
            const res = await getFollowUpQuestions(book.id, {
              question: assistantMsg.userQuestion,
              answer: fullAnswerContent,
              sessionId: streamSessionIdRef.current || undefined,
            })
            const data = (res as any)?.data || (res as any)
            if (Array.isArray(data) && data.length > 0) {
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === assistantMsg.id ? { ...m, followUpQuestions: data, loadingFollowUps: false } : m
                )
              )
            } else {
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === assistantMsg.id ? { ...m, loadingFollowUps: false } : m
                )
              )
            }
          } catch {
            setMessages((prev) =>
              prev.map((m) =>
                m.id === assistantMsg.id ? { ...m, loadingFollowUps: false } : m
              )
            )
          }
        } else {
          setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantMsg.id ? { ...m, loadingFollowUps: false } : m
            )
          )
        }

        followUpsFromSseRef.current = false
        loadHistorySessions()
      },
      (_) => {
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
      (newSessionId) => {
        streamSessionIdRef.current = newSessionId
        setSessionId(newSessionId)
      },
      (followUpJson) => {
        try {
          const questions = JSON.parse(followUpJson)
          if (Array.isArray(questions) && questions.length > 0) {
            followUpsFromSseRef.current = true
            setMessages((prev) =>
              prev.map((m) =>
                m.id === assistantMsg.id ? { ...m, followUpQuestions: questions, loadingFollowUps: false } : m
              )
            )
          }
        } catch { /* ignore */ }
      },
    )
    abortRef.current = controller
  }, [input, loading, book.id, sessionId])

  const handleToggleSpeak = useCallback(async (msgId: string, content: string) => {
    if (speakingId === msgId) {
      ttsService.cancel()
      setSpeakingId(null)
      return
    }
    if (!useTtsStore.getState().backendConfig) {
      try {
        const config = await getActiveTtsConfig()
        if (config) {
          useTtsStore.getState().setBackendConfig(config)
          useTtsStore.getState().setBackendMode(true)
        }
      } catch { /* no backend TTS, use browser */ }
    }
    const plainText = content
      .replace(/```[\s\S]*?```/g, '')
      .replace(/`[^`]+`/g, '')
      .replace(/\*\*([^*]+)\*\*/g, '$1')
      .replace(/\*([^*]+)\*/g, '$1')
      .replace(/^(#{1,6})\s+/gm, '\n')
      .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
      .replace(/^[-*]\s+/gm, '')
      .replace(/^(\d+)\.\s+/gm, '$1、')
      .replace(/^>\s+/gm, '')
      .replace(/\|/g, ' ')
      .replace(/---+/g, '\n')
      .trim()
    if (!plainText) return
    ttsService.cancel()
    setSpeakingId(msgId)
    ttsService.speakLongText(plainText, () => {
      setSpeakingId((prev) => prev === msgId ? null : prev)
    })
  }, [speakingId])

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
        handleSend(userMsgContent, true)
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
            ? 'h-full w-full sm:max-w-xl rounded-l-2xl border-l'
            : 'h-[85vh] rounded-t-2xl border-t'
        }`}
      >
        <SheetHeader className="shrink-0 border-b px-4 py-3">
          <div className="flex items-center gap-3">
            <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-primary/10">
              <BlinkingBot className="h-5 w-5 text-primary" />
            </div>
            <div className="min-w-0 flex-1">
              <SheetTitle className="text-base truncate">{chatTitle || 'AI 书籍问答'}</SheetTitle>
              <SheetDescription className="truncate text-xs">
                基于原著内容回答关于《{book.title}》的问题
              </SheetDescription>
            </div>
            <div className="flex items-center gap-0.5">
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
              <button
                onClick={handleClose}
                className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground"
                title="关闭"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
          </div>
        </SheetHeader>

        {showHistory ? (
          <div className="flex-1 overflow-y-auto overscroll-y-contain">
            <div className="flex items-center justify-between border-b px-4 py-2">
              <span className="text-sm font-medium">历史问答</span>
              <button onClick={() => setShowHistory(false)} className="text-xs text-muted-foreground">
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
                      session.sessionId === sessionId ? 'bg-muted' : ''
                    }`}
                    onClick={() => loadSessionHistory(session.sessionId, session.title)}
                  >
                    <p className="truncate text-sm font-medium">{session.title || '未命名对话'}</p>
                    <p className="mt-0.5 text-xs text-muted-foreground">{formatTime(session.updatedAt)}</p>
                  </button>
                ))}
              </div>
            )}
          </div>
        ) : (
          <div className="flex-1 overflow-y-auto overscroll-y-contain px-4 py-4">
            {!hasMessages ? (
              <div className="flex min-h-full flex-col items-center text-center">
                <div className="my-auto flex flex-col items-center">
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
                      <span className="text-[11px] text-muted-foreground">
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
                          {msg.content && (
                            <button
                              className={`flex h-7 items-center gap-1 rounded-md px-2 text-xs transition-colors active:scale-95 ${
                                speakingId === msg.id
                                  ? 'text-primary hover:bg-primary/10'
                                  : 'text-muted-foreground hover:bg-muted hover:text-foreground'
                              }`}
                              onClick={() => handleToggleSpeak(msg.id, msg.content)}
                              title={speakingId === msg.id ? '停止朗读' : '朗读'}
                            >
                              {speakingId === msg.id ? (
                                <>
                                  <Square className="h-3.5 w-3.5 fill-current" />
                                  <span>停止</span>
                                </>
                              ) : (
                                <>
                                  <Volume2 className="h-3.5 w-3.5" />
                                  <span>朗读</span>
                                </>
                              )}
                            </button>
                          )}
                        </div>
                      )}
                      {msg.role === 'assistant' && !msg.streaming && msg.followUpQuestions && msg.followUpQuestions.length > 0 && (
                        <div className="mt-2 text-left">
                          <p className="mb-1.5 text-[11px] text-muted-foreground">深入探索</p>
                          <div className="flex flex-wrap justify-start gap-1.5">
                            {msg.followUpQuestions.map((q, qi) => (
                              <button
                                key={qi}
                                className="inline-flex shrink-0 items-center whitespace-nowrap rounded-lg border border-primary/20 bg-primary/5 px-2.5 py-1 text-left text-xs text-primary transition-colors hover:bg-primary/10 active:scale-[0.97]"
                                onClick={() => handleSend(q)}
                              >
                                {q}
                              </button>
                            ))}
                          </div>
                        </div>
                      )}
                      {msg.role === 'assistant' && !msg.streaming && msg.loadingFollowUps && (
                        <div className="mt-2">
                          <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                            <Loader2 className="h-3 w-3 animate-spin" />
                            <span>正在生成深入追问...</span>
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
            {/* 对话风格切换 */}
            <div className="relative">
              <button
                onClick={() => setStyleMenuOpen(!styleMenuOpen)}
                className={`flex items-center gap-1 rounded-xl border border-transparent px-3 py-2.5 text-xs text-primary-foreground transition-all shrink-0 ${
                  styleMenuOpen ? 'bg-primary' : 'bg-primary/50'
                }`}
                title="切换对话风格"
              >
                <span>{currentStyleLabel}</span>
                {styleMenuOpen ? (
                  <ChevronDown className="h-3 w-3 shrink-0" />
                ) : (
                  <ChevronUp className="h-3 w-3 shrink-0" />
                )}
              </button>
              {styleMenuOpen && (
                <>
                  <div className="fixed inset-0 z-10" onClick={() => setStyleMenuOpen(false)} />
                  <div className="absolute bottom-full left-0 mb-2 z-20 bg-card rounded-xl border shadow-lg p-1.5 w-48">
                    {CHAT_STYLES.map((s) => (
                      <button
                        key={s.value}
                        onClick={async () => {
                          try {
                            await updateBookChatStyle(s.value)
                            updateUserInfo({ bookChatStyle: s.value })
                            setStyleMenuOpen(false)
                          } catch { /* ignore */ }
                        }}
                        className={`w-full text-left rounded-lg px-3 py-2 text-xs transition-colors ${
                          currentStyle === s.value
                            ? 'bg-primary/10 text-primary font-medium'
                            : 'text-muted-foreground hover:bg-muted'
                        }`}
                      >
                        <div>{s.label}</div>
                        <div className="text-[10px] opacity-60 mt-0.5">{s.desc}</div>
                      </button>
                    ))}
                  </div>
                </>
              )}
            </div>
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
              placeholder={`聊聊《${book.title.length > 10 ? book.title.slice(0, 10) + '...' : book.title}》`}
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
