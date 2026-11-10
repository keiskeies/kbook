import { useState, useRef, useEffect, useCallback } from 'react'
import { Send, Loader2, Bot, User, RefreshCw, Copy, Check, History, Volume2, Square, Plus, ChevronDown, ChevronUp, Download, ThumbsUp, ThumbsDown, FileSearch } from 'lucide-react'
import { Sheet, SheetContent, SheetTitle, SheetDescription } from '@/components/ui/sheet'
import MobileSheetHeader from '@/components/common/MobileSheetHeader'
import {
  streamBookChat, getBookSuggestedQuestions, getFollowUpQuestions, getBookChatSessions,
  getBookChatHistory, exportBookChatHistory, getRetrievalEvidence, submitAnswerFeedback,
  getAnswerFeedbackBatch,
} from '@/api/bookChat'
import type { RetrievalEvidence, FeedbackRating, FeedbackReason, ScaleMode } from '@/api/bookChat'
import MarkdownRenderer from '@/components/ui/markdown-renderer'
import ThinkingBlock from '@/components/ui/thinking-block'
import { BlinkingBot } from '@/components/BlinkingBot'
import { ttsService } from '@/utils/tts'
import { useTtsStore } from '@/store/tts'
import { getActiveTtsConfig } from '@/api/adminTts'
import { useAuthStore } from '@/store/auth'
import { updateBookChatStyle } from '@/api/auth'
import { createStreamBatcher } from '@/utils/stream-batcher'
import type { AiMessage } from '@/types/ai'
import type { AiSessionItem } from '@/types/ai'
import type { Book } from '@/types/book'
import ChatHistoryPanel from './ChatHistoryPanel'
import TtsProgressBar from './TtsProgressBar'

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

/** 差评原因：与后端 VALID_REASONS 一一对应，选择后进待标注队列供人工复核 */
const FEEDBACK_REASONS: { value: FeedbackReason; label: string }[] = [
  { value: 'WRONG', label: '答错了' },
  { value: 'NOT_GROUNDED', label: '没有依据' },
  { value: 'OFF_TOPIC', label: '答非所问' },
  { value: 'TOO_LONG', label: '太啰嗦' },
  { value: 'OTHER', label: '其他' },
]

const SCALE_MODE_LABEL: Record<ScaleMode, string> = {
  small: '全书精读',
  medium: '章节精读',
  large: '检索作答',
}

const MODE_LABEL: Record<string, string> = {
  full_text: '全书原文',
  chapter: '整章原文',
  retrieval: '向量检索',
  context: '检索片段',
  neighbor: '邻域片段',
  anchor: '目录锚点',
  llm_refine: 'LLM 精排',
  list: '列表检索',
}

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
  // P2-5 / P2-7：反馈状态、差评原因面板、引用依据面板
  const [feedbackMap, setFeedbackMap] = useState<Record<string, FeedbackRating>>({})
  const [reasonFor, setReasonFor] = useState<string | null>(null)
  const [evidenceOpenFor, setEvidenceOpenFor] = useState<string | null>(null)
  const [evidenceMap, setEvidenceMap] = useState<Record<string, RetrievalEvidence[]>>({})
  const [evidenceLoading, setEvidenceLoading] = useState<string | null>(null)
  const [scaleMode, setScaleMode] = useState<ScaleMode | ''>('')
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const scrollContainerRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  const streamSessionIdRef = useRef('')
  const followUpsFromSseRef = useRef(false)
  const initialQuestionRef = useRef(initialQuestion)
  const hasSentInitialRef = useRef(false)
  const userScrollingRef = useRef(false)

  useEffect(() => {
    initialQuestionRef.current = initialQuestion
  }, [initialQuestion])

  useEffect(() => {
    if (open && book.id) {
      getBookSuggestedQuestions(book.id)
        .then((res) => {
          const data = res
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
          handleSend(iq.trim(), false, false)
        }, 300)
      }
    } else if (!open) {
      hasSentInitialRef.current = false
    }
  }, [open, book.id])

  useEffect(() => {
    if (open && book.id && !initialQuestion && messages.length === 0 && historySessions.length > 0) {
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
        loadSessionHistory(latestSession.sessionId, latestSession.title)
      }
    }
  }, [open, book.id, initialQuestion, historySessions])

  useEffect(() => {
    const streaming = messages.some((m) => m.streaming)
    if (streaming && !userScrollingRef.current) {
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
  }, [messages])

  const handleUserScroll = () => {
    const container = scrollContainerRef.current
    if (!container) return
    const { scrollTop, scrollHeight, clientHeight } = container
    const isAtBottom = scrollHeight - scrollTop - clientHeight < 50
    userScrollingRef.current = !isAtBottom
  }

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

  // 组件卸载（路由切换/页面退出）时停止 TTS，并释放常驻 audio 元素
  useEffect(() => {
    return () => {
      if (abortRef.current) {
        abortRef.current.abort()
        abortRef.current = null
      }
      ttsService.dispose()
    }
  }, [])

  useEffect(() => {
    if (!input && textareaRef.current) {
      textareaRef.current.style.height = 'auto'
    }
  }, [input])

  const loadHistorySessions = async () => {
    try {
      const res = await getBookChatSessions(book.id)
      const data = res
      if (Array.isArray(data)) {
        setHistorySessions(data)
      }
    } catch { /* ignore */ }
  }

  const loadSessionHistory = async (targetSessionId: string, title?: string) => {
    try {
      const res = await getBookChatHistory(book.id, targetSessionId)
      const data = res
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
              conversationId: r.role === 'assistant' ? Number(r.id) : undefined,
            }
          })
        setMessages(history)
        setSessionId(targetSessionId)
        setFeedbackMap({})
        setEvidenceMap({})
        setEvidenceOpenFor(null)
        setReasonFor(null)
        // 一次性取回这段历史里已有的反馈，避免每条回答各发一个请求
        const ids = history.filter((m) => m.conversationId).map((m) => m.conversationId as number)
        if (ids.length > 0) {
          getAnswerFeedbackBatch(book.id, ids)
            .then((res) => {
              if (!res || typeof res !== 'object') return
              const next: Record<string, FeedbackRating> = {}
              for (const m of history) {
                const vo = m.conversationId ? res[String(m.conversationId)] : undefined
                if (vo?.rating === 'UP' || vo?.rating === 'DOWN') next[m.id] = vo.rating
              }
              if (Object.keys(next).length > 0) setFeedbackMap((prev) => ({ ...prev, ...next }))
            })
            .catch(() => {})
        }
        if (title) setChatTitle(title)
        setShowHistory(false)
        setTimeout(() => {
          messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
        }, 100)
      }
    } catch { /* ignore */ }
  }

  /** 流式回答用 SSE 补上的 id，历史回答直接用数字化的 msg.id */
  const resolveConversationId = useCallback((msg: AiMessage): number | undefined => {
    if (msg.conversationId && msg.conversationId > 0) return msg.conversationId
    const n = Number(msg.id)
    return Number.isFinite(n) && n > 0 ? n : undefined
  }, [])

  const handleFeedback = useCallback(async (msg: AiMessage, rating: FeedbackRating, reason?: FeedbackReason) => {
    const cid = resolveConversationId(msg)
    if (!cid) return
    // 乐观更新：反馈不该打断阅读，失败时回滚即可
    const before = feedbackMap[msg.id]
    setFeedbackMap((prev) => ({ ...prev, [msg.id]: rating }))
    setReasonFor(null)
    try {
      await submitAnswerFeedback(book.id, cid, { rating, reason })
    } catch {
      setFeedbackMap((prev) => {
        const next = { ...prev }
        if (before) next[msg.id] = before
        else delete next[msg.id]
        return next
      })
    }
  }, [book.id, feedbackMap, resolveConversationId])

  const handleToggleEvidence = useCallback(async (msg: AiMessage) => {
    if (evidenceOpenFor === msg.id) {
      setEvidenceOpenFor(null)
      return
    }
    const cid = resolveConversationId(msg)
    if (!cid) return
    setEvidenceOpenFor(msg.id)
    if (evidenceMap[msg.id] !== undefined) return
    setEvidenceLoading(msg.id)
    try {
      const list = await getRetrievalEvidence(book.id, cid)
      setEvidenceMap((prev) => ({ ...prev, [msg.id]: Array.isArray(list) ? list : [] }))
    } catch {
      setEvidenceMap((prev) => ({ ...prev, [msg.id]: [] }))
    } finally {
      setEvidenceLoading(null)
    }
  }, [book.id, evidenceMap, evidenceOpenFor, resolveConversationId])

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
    setFeedbackMap({})
    setEvidenceMap({})
    setEvidenceOpenFor(null)
    setReasonFor(null)
    setScaleMode('')
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
    setFeedbackMap({})
    setEvidenceMap({})
    setEvidenceOpenFor(null)
    setReasonFor(null)
    setScaleMode('')
  }, [speakingId])

  const handleSend = useCallback(async (text?: string, isRegenerate?: boolean, manual: boolean = true) => {
    const message = (text || input).trim()
    if (!message || loading) return

    // 清除用户手动滚动标识，恢复自动滚动
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

    if (!chatTitle) {
      setChatTitle(message.length > 20 ? `${message.slice(0, 20)}…` : message)
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

    // 流式 token 批量更新：rAF 累积 chunk 后一次性 setState，避免每 token re-render
    const mainBatcher = createStreamBatcher<AiMessage>(
      setMessages,
      assistantMsg.id,
      (m, bufferedChunk) => ({ ...m, content: m.content + bufferedChunk, thinkingStatus: undefined })
    )
    const thinkingBatcher = createStreamBatcher<AiMessage>(
      setMessages,
      assistantMsg.id,
      (m, bufferedChunk) => ({ ...m, thinkingContent: (m.thinkingContent || '') + bufferedChunk })
    )

    const controller = streamBookChat(
      book.id,
      { message, sessionId: sessionId || undefined, regenerate: isRegenerate || undefined, manual },
      (chunk) => {
        fullAnswerContent += chunk
        mainBatcher.append(chunk)
      },
      async () => {
        // 流结束前 flush 残余 buffer，确保最终内容完整
        mainBatcher.flush()
        thinkingBatcher.flush()
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
            const data = res
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
        // 错误时 flush 残余 thinking buffer（主内容会被错误提示覆盖，无需 flush）
        thinkingBatcher.flush()
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
        thinkingBatcher.append(chunk)
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
      (safeText) => {
        // onReplace — 后端检测到系统提示泄露时，用安全文本覆盖已显示的流式内容
        fullAnswerContent = safeText
        setMessages((prev) =>
          prev.map((m) => (m.id === assistantMsg.id ? { ...m, content: safeText } : m))
        )
      },
      (conversationId) => {
        // P2-5 / P2-7：回答落库后才知道真实行 id，反馈与依据都靠它
        setMessages((prev) =>
          prev.map((m) => (m.id === assistantMsg.id ? { ...m, conversationId } : m))
        )
      },
      (mode) => {
        if (mode === 'small' || mode === 'medium' || mode === 'large') setScaleMode(mode)
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
    // 必须在点击的同步阶段解锁音频元素：iOS Safari 要求播放由用户手势发起，
    // 而下面 await 取配置会断掉手势链，之后再播放就会被自动播放策略拒绝。
    ttsService.primeForGesture()
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
      .replace(/\[BOOK:id=\d+\]\s*/g, '')      // 去掉图书链接标记，保留书名
      .replace(/\*\*([^*]+)\*\*/g, '$1')
      .replace(/\*([^*]+)\*/g, '$1')
      .replace(/^(#{1,6})\s+/gm, '\n')
      .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
      .replace(/^[-*]\s+/gm, '')
      .replace(/^(\d+)\.\s+/gm, '$1、')
      .replace(/^>\s+/gm, '')
      .replace(/\|/g, ' ')
      .replace(/---+/g, '\n')
      .replace(/\p{Emoji_Presentation}|\p{Extended_Pictographic}/gu, '')  // 去掉 emoji，TTS 无法朗读
      .trim()
    if (!plainText) return
    ttsService.cancel()
    setSpeakingId(msgId)
    // 传入书名与封面：锁屏 / 通知栏的媒体控制条据此展示，用户不必解锁就能确认在听哪本书
    ttsService.speakLongText(
      plainText,
      () => {
        setSpeakingId((prev) => prev === msgId ? null : prev)
      },
      {
        title: `《${book.title}》问答`,
        artist: '图书问答朗读',
        album: book.title,
        artworkUrl: book.coverUrl ?? undefined,
      },
    )
  }, [speakingId, book.title, book.coverUrl])

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
        handleSend(userMsgContent, true, false)
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

  const handleExport = useCallback(async () => {
    if (!sessionId || !hasMessages) return

    try {
      const res = await exportBookChatHistory(book.id, sessionId)
      const data = res

      if (data.error) {
        return
      }

      if (data.content) {
        // 创建Blob并下载
        const blob = new Blob([data.content], { type: 'text/plain;charset=utf-8' })
        const url = URL.createObjectURL(blob)
        const link = document.createElement('a')
        link.href = url
        link.download = `${data.title || '对话记录'}.txt`
        document.body.appendChild(link)
        link.click()
        document.body.removeChild(link)
        URL.revokeObjectURL(url)
      }
    } catch {
      // 导出失败静默处理
    }
  }, [sessionId, hasMessages, book.id, book.title])

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
        <SheetTitle className="sr-only">{chatTitle || 'AI 书籍问答'}</SheetTitle>
        <SheetDescription className="sr-only">基于原著内容回答关于《{book.title}》的问题</SheetDescription>
        <MobileSheetHeader
          icon={<BlinkingBot className="h-5 w-5 text-primary" />}
          title={chatTitle || 'AI 书籍问答'}
          description={`《${book.title}》${scaleMode ? ` · ${SCALE_MODE_LABEL[scaleMode]}` : ''}`}
          actions={
            <>
              {hasMessages && sessionId && (
                <button
                  onClick={handleExport}
                  className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground"
                  title="导出对话"
                >
                  <Download className="h-4 w-4" />
                </button>
              )}
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

        <ChatHistoryPanel
          showHistory={showHistory}
          historySessions={historySessions}
          currentSessionId={sessionId}
          onSessionClick={loadSessionHistory}
          onClose={() => setShowHistory(false)}
          formatTime={formatTime}
        />
        {!showHistory && (
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
                        onClick={() => handleSend(hint, false, false)}
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
                            <MarkdownRenderer content={msg.content} className="text-detail text-justify" />
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
                      {/* P2-5 / P2-7：反馈与引用依据。只有拿到落库行 id 的回答才能操作 */}
                      {msg.role === 'assistant' && !msg.streaming && msg.content && resolveConversationId(msg) && (
                        <div className="mt-1 flex flex-wrap items-center gap-1">
                          <button
                            className={`flex h-7 items-center gap-1 rounded-md px-2 text-xs transition-colors active:scale-95 ${
                              feedbackMap[msg.id] === 'UP'
                                ? 'bg-primary/10 text-primary'
                                : 'text-muted-foreground hover:bg-muted hover:text-foreground'
                            }`}
                            onClick={() => handleFeedback(msg, 'UP')}
                            title="这条回答有帮助"
                          >
                            <ThumbsUp className="h-3.5 w-3.5" />
                            <span>有用</span>
                          </button>
                          <button
                            className={`flex h-7 items-center gap-1 rounded-md px-2 text-xs transition-colors active:scale-95 ${
                              feedbackMap[msg.id] === 'DOWN'
                                ? 'bg-destructive/10 text-destructive'
                                : 'text-muted-foreground hover:bg-muted hover:text-foreground'
                            }`}
                            onClick={() => {
                              if (feedbackMap[msg.id] === 'DOWN') handleFeedback(msg, 'UP')
                              else setReasonFor(reasonFor === msg.id ? null : msg.id)
                            }}
                            title="这条回答有问题"
                          >
                            <ThumbsDown className="h-3.5 w-3.5" />
                            <span>没用</span>
                          </button>
                          <button
                            className={`flex h-7 items-center gap-1 rounded-md px-2 text-xs transition-colors active:scale-95 ${
                              evidenceOpenFor === msg.id
                                ? 'bg-primary/10 text-primary'
                                : 'text-muted-foreground hover:bg-muted hover:text-foreground'
                            }`}
                            onClick={() => handleToggleEvidence(msg)}
                            title="查看这条回答实际用到的书中片段"
                          >
                            <FileSearch className="h-3.5 w-3.5" />
                            <span>依据</span>
                          </button>
                          {feedbackMap[msg.id] && (
                            <span className="text-xs text-muted-foreground">感谢反馈</span>
                          )}
                        </div>
                      )}
                      {/* 差评原因：选一个即提交，样本进入待标注队列 */}
                      {reasonFor === msg.id && (
                        <div className="mt-1.5 rounded-lg border border-border/60 bg-card p-2">
                          <p className="mb-1.5 text-xs text-muted-foreground">哪里不对？我们会拿它改进回答</p>
                          <div className="flex flex-wrap gap-1">
                            {FEEDBACK_REASONS.map((r) => (
                              <button
                                key={r.value}
                                className="rounded-md border border-border/60 px-2 py-1 text-xs text-muted-foreground transition-colors hover:border-primary/40 hover:text-primary active:scale-95"
                                onClick={() => handleFeedback(msg, 'DOWN', r.value)}
                              >
                                {r.label}
                              </button>
                            ))}
                            <button
                              className="rounded-md px-2 py-1 text-xs text-muted-foreground hover:text-foreground"
                              onClick={() => setReasonFor(null)}
                            >
                              取消
                            </button>
                          </div>
                        </div>
                      )}
                      {/* 引用依据：展示真正进入上下文的片段与来源档位 */}
                      {evidenceOpenFor === msg.id && (
                        <div className="mt-1.5 rounded-lg border border-border/60 bg-card p-2.5">
                          {evidenceLoading === msg.id ? (
                            <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                              <Loader2 className="h-3 w-3 animate-spin" />
                              <span>正在读取依据…</span>
                            </div>
                          ) : (() => {
                            const list = evidenceMap[msg.id] || []
                            const full = list.some((e) => e.chunkIndex === -1)
                            if (full) {
                              const mode = list[0]?.source
                              return (
                                <div className="text-xs text-muted-foreground">
                                  <p className="mb-1 font-medium text-foreground">
                                    本轮使用{MODE_LABEL[mode || ''] || mode || '全文'}，未经片段检索
                                  </p>
                                  <p>整本/整章原文已进入上下文，因此没有片段编号与排序分；这是设计使然，不是检索失败。</p>
                                  {list[0]?.textPreview && (
                                    <p className="mt-1 break-all font-mono text-[11px] opacity-70">{list[0].textPreview}</p>
                                  )}
                                </div>
                              )
                            }
                            if (list.length === 0) {
                              return <p className="text-xs text-muted-foreground">这条回答没有留下检索依据记录。</p>
                            }
                            return (
                              <div className="space-y-1.5">
                                <p className="text-xs font-medium text-foreground">
                                  实际进入上下文的 {list.length} 个片段
                                </p>
                                {list.map((e, ei) => (
                                  <div key={e.id ?? ei} className="rounded-md bg-muted/60 px-2 py-1.5 text-xs">
                                    <div className="mb-0.5 flex flex-wrap items-center gap-1.5 text-muted-foreground">
                                      <span className="font-medium text-foreground">
                                        片段 {e.chunkIndex}{e.chunkEndIndex && e.chunkEndIndex !== e.chunkIndex ? `–${e.chunkEndIndex}` : ''}
                                      </span>
                                      {e.source && <span>· {MODE_LABEL[e.source] || e.source}</span>}
                                      {typeof e.score === 'number' && <span>· 相似度 {e.score.toFixed(3)}</span>}
                                    </div>
                                    {e.textPreview && (
                                      <p className="line-clamp-2 break-words text-muted-foreground">{e.textPreview}</p>
                                    )}
                                  </div>
                                ))}
                              </div>
                            )
                          })()}
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
                                onClick={() => handleSend(q, false, false)}
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

        <TtsProgressBar
          active={speakingId !== null}
          onStop={() => {
            ttsService.cancel()
            setSpeakingId(null)
          }}
        />

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
                        <div className="text-xs opacity-60 mt-0.5">{s.desc}</div>
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
              enterKeyHint="send"
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.nativeEvent.isComposing && !e.shiftKey) {
                  e.preventDefault()
                  handleSend()
                }
              }}
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
