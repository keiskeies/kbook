import { useState, useEffect, useRef, useCallback } from 'react'
import { useParams, useNavigate, useSearchParams } from 'react-router-dom'
import {
  ArrowLeft, Volume2, Square, Loader2, Play, Pause, BarChart3, Target, RefreshCw, FileText, X,
} from 'lucide-react'
import { Sheet, SheetContent } from '@/components/ui/sheet'
import MarkdownRenderer from '@/components/ui/markdown-renderer'
import CoveragePanel from '@/components/round-table/CoveragePanel'
import {
  getRoundTableRoles, getRoundTableMessages, getRoundTableSession, updateRoundTableSessionStatus,
  getNextSpeaker, streamCharacterSpeak,
  triggerRoundTableReport, getRoundTableReport,
} from '@/api/roundTable'
import { getBook } from '@/api/book'
import type { RoundTableRole, RoundTableMessage, RoundTableReport, RoundTableSession } from '@/types/roundTable'
import {
  ROLE_COLORS, ROLE_NAMES, ROLE_ICONS, ROLE_TTS_CONFIG,
  hexToRgba,
} from '@/types/roundTable'
import { toast } from 'sonner'
import { useIsMobile } from '@/hooks/use-mobile'
import { useAuthStore } from '@/store/auth'
import { getSortedChineseVoices, assignVoiceForRole, assignVoiceForRoleSafe } from '@/utils/browserTts'
import { speechService } from '@/utils/speechService'
import { getAzureVoiceForRole, ROUNDTABLE_AZURE_VOICE } from '@/utils/speechVoices'
import { voiceTester } from '@/utils/voiceTester'

type Phase = 'loading' | 'discussing' | 'paused' | 'completed' | 'error'

interface DisplayMessage {
  id: string
  roleKey: string
  roleName: string
  roleColor: string
  roleIcon: string
  content: string
  timestamp: number
  streaming?: boolean
}

function RoleBar({
  roles,
  speakCounts,
  speakingKey,
  grabbingKey,
}: {
  roles: RoundTableRole[]
  speakCounts: Record<string, number>
  speakingKey: string | null
  grabbingKey: string | null
}) {
  return (
    <div className="shrink-0 border-b border-border/20 bg-navbar/95 backdrop-blur-xl px-3 py-2">
      <div className="flex items-center gap-1.5 overflow-x-auto scrollbar-hide">
        {roles.map(role => {
          const color = role.color || ROLE_COLORS[role.key] || '#6B655C'
          const count = speakCounts[role.key] || 0
          const isActive = speakingKey === role.key || grabbingKey === role.key
          return (
            <div
              key={role.key}
              className={`flex shrink-0 items-center gap-1.5 rounded-xl px-2 py-1.5 transition-all duration-300 ${
                isActive ? 'bg-[var(--role-color)]/[0.08]' : 'bg-muted/40'
              }`}
              style={{ '--role-color': color } as React.CSSProperties}
            >
              <div
                className={`flex h-7 w-7 items-center justify-center rounded-full text-sm transition-all duration-300 ${
                  isActive ? 'scale-110' : ''
                }`}
                style={{
                  backgroundColor: hexToRgba(color, isActive ? 0.2 : 0.08),
                  border: isActive ? `2px solid ${color}` : `1px solid ${hexToRgba(color, 0.15)}`,
                  boxShadow: isActive ? `0 0 12px ${hexToRgba(color, 0.25)}` : undefined,
                }}
              >
                {role.icon || ROLE_ICONS[role.key] || '👤'}
              </div>
              <div className="flex flex-col">
                <span
                  className="text-xs font-semibold leading-tight"
                  style={{ color: isActive ? color : undefined }}
                >
                  {role.name}
                </span>
                <span className="text-xs text-muted-foreground/60 leading-tight">
                  {count}次
                </span>
              </div>
              {isActive && (
                <span className="flex items-end gap-[1px] h-2 ml-0.5">
                  {[0, 1, 2].map(i => (
                    <span
                      key={i}
                      className="w-[2px] rounded-full animate-pulse"
                      style={{
                        backgroundColor: color,
                        animationDelay: `${i * 120}ms`,
                        height: `${3 + (i % 2) * 3}px`,
                      }}
                    />
                  ))}
                </span>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}

function MessageBubble({
  msg,
  isSpeaking,
  onToggleSpeak,
}: {
  msg: DisplayMessage
  isSpeaking: boolean
  onToggleSpeak: () => void
}) {
  const color = msg.roleColor

  return (
    <div className="group flex gap-3 animate-in fade-in slide-in-from-bottom-2 duration-300">
      <div className="flex flex-col items-center gap-1 shrink-0">
        <div
          className="flex h-9 w-9 items-center justify-center rounded-full text-base"
          style={{
            backgroundColor: hexToRgba(color, 0.1),
            border: `1.5px solid ${hexToRgba(color, 0.25)}`,
          }}
        >
          {msg.roleIcon}
        </div>
        <div
          className="w-[2px] flex-1 rounded-full min-h-[20px]"
          style={{ backgroundColor: hexToRgba(color, 0.15) }}
        />
      </div>

      <div className="min-w-0 flex-1 pb-3">
        <div className="flex items-center gap-2 mb-1.5">
          <span className="text-xs font-bold" style={{ color }}>{msg.roleName}</span>
          <span className="text-xs text-muted-foreground/40">
            {new Date(msg.timestamp).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}
          </span>
          {msg.streaming && (
            <span className="flex items-end gap-[2px] h-2">
              {[0, 1, 2].map(i => (
                <span
                  key={i}
                  className="w-[2px] rounded-full bg-foreground/20 animate-pulse"
                  style={{ animationDelay: `${i * 150}ms`, height: `${3 + (i % 2) * 4}px` }}
                />
              ))}
            </span>
          )}
        </div>

        <div className="relative">
          <div
            className="absolute left-0 top-0 bottom-0 w-[3px] rounded-full"
            style={{ backgroundColor: hexToRgba(color, 0.3) }}
          />
          <div
            className="rounded-r-xl rounded-bl-xl px-4 py-3 text-detail leading-relaxed ml-3"
            style={{
              backgroundColor: hexToRgba(color, 0.04),
              border: `1px solid ${hexToRgba(color, 0.08)}`,
              borderLeft: 'none',
            }}
          >
            {msg.content ? (
              <MarkdownRenderer content={msg.content} className="!text-detail" />
            ) : msg.streaming ? (
              <span className="flex items-center gap-2 text-muted-foreground">
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
                <span className="text-xs">麦克风传递中...</span>
              </span>
            ) : null}
            {msg.streaming && msg.content && (
              <span className="inline-flex items-center ml-1 align-middle">
                <span className="h-3.5 w-[2px] bg-foreground/30 animate-pulse rounded-full" />
              </span>
            )}
          </div>
        </div>

        {!msg.streaming && msg.content && msg.content !== '（发言失败）' && (
          <button
            onClick={onToggleSpeak}
            className={`mt-1.5 flex items-center gap-1 rounded-lg px-2 py-1 text-xs transition-all duration-200 opacity-0 group-hover:opacity-100 ${
              isSpeaking
                ? 'text-primary bg-primary/10 opacity-100'
                : 'text-muted-foreground/60 hover:text-muted-foreground'
            }`}
          >
            {isSpeaking ? <><Square className="h-2.5 w-2.5 fill-current" />停止</> : <><Volume2 className="h-2.5 w-2.5" />朗读</>}
          </button>
        )}
      </div>
    </div>
  )
}

function SpeakStatsPanel({
  roles,
  messages,
  onClose,
  isMobile,
}: {
  roles: RoundTableRole[]
  messages: DisplayMessage[]
  onClose: () => void
  isMobile?: boolean
}) {
  const stats = roles.map(role => {
    const count = messages.filter(m => m.roleKey === role.key).length
    const totalChars = messages
      .filter(m => m.roleKey === role.key)
      .reduce((sum, m) => sum + m.content.length, 0)
    return { role, count, totalChars }
  }).sort((a, b) => b.count - a.count)

  const maxCount = Math.max(...stats.map(s => s.count), 1)

  return (
    <div className="flex flex-col min-h-0">
      <div className="shrink-0 flex items-center justify-between px-4 py-3 border-b border-border/20">
        <h3 className="text-xs font-bold flex items-center gap-1.5">
          <BarChart3 className="h-3.5 w-3.5 text-brand-500" />
          发言统计
        </h3>
        {!isMobile && (
          <button onClick={onClose} className="text-xs text-muted-foreground hover:text-foreground mr-7">关闭</button>
        )}
      </div>
      <div className="px-4 py-3 shrink-0">
        <div className="space-y-2 overflow-y-auto">
          {stats.map(({ role, count, totalChars }) => {
          const color = role.color || ROLE_COLORS[role.key] || '#6B655C'
            const pct = (count / maxCount) * 100
            return (
              <div key={role.key} className="flex items-center gap-2">
                <span className="text-xs font-bold w-20 truncate" style={{ color }}>{role.name}</span>
                <div className="flex-1 h-2 rounded-full bg-muted overflow-hidden">
                  <div
                    className="h-full rounded-full transition-all duration-500"
                    style={{ width: `${pct}%`, backgroundColor: color }}
                  />
                </div>
                <span className="text-xs font-bold text-muted-foreground/80 w-14 text-right shrink-0 tabular-nums">{count}次</span>
                <span className="text-xs font-bold text-muted-foreground/80 w-20 text-right shrink-0 tabular-nums">{totalChars}字</span>
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}

function ReportPanel({
  report,
  isGenerating,
  isOwner,
  onTrigger,
  onClose,
}: {
  report: RoundTableReport | null
  isGenerating: boolean
  isOwner?: boolean
  onTrigger: () => void
  onClose: () => void
}) {
  return (
    <div className="flex flex-col min-h-0 h-full">
      <div className="shrink-0 flex items-center justify-between px-4 py-3 border-b border-border/20">
        <h3 className="text-xs font-bold flex items-center gap-1.5">
          <FileText className="h-3.5 w-3.5 text-brand-500" />
          解读报告
        </h3>
        <button
          onClick={onClose}
          className="flex h-7 w-7 items-center justify-center rounded-lg text-muted-foreground hover:text-foreground hover:bg-muted transition-colors"
        >
          <X className="h-4 w-4" />
        </button>
      </div>

      <div className="flex-1 min-h-0 overflow-y-auto overscroll-contain px-4 py-3">
        {!report && !isGenerating && (
          <div className="text-center py-6">
            {isOwner !== false ? (
              <>
                <p className="text-xs text-muted-foreground mb-3">AI 将深度解读本次讨论，生成七维度分析报告</p>
                <button
                  onClick={onTrigger}
                  className="inline-flex items-center gap-1.5 rounded-xl bg-gradient-to-r from-brand-400 to-brand-500 px-4 py-2 text-xs font-medium text-white shadow-sm active:scale-[0.97] transition-transform"
                >
                  <FileText className="h-3 w-3" />
                  生成解读报告
                </button>
                <p className="text-xs text-muted-foreground/60 mt-2">预计 2-3 分钟，完成后页面自动刷新</p>
              </>
            ) : (
              <p className="text-xs text-muted-foreground">报告尚未生成</p>
            )}
          </div>
        )}

        {isGenerating && (
          <div className="text-center py-6">
            <Loader2 className="h-6 w-6 animate-spin text-brand-500 mx-auto mb-2" />
            <p className="text-xs text-muted-foreground">AI 正在深度解读讨论内容...</p>
            <p className="text-xs text-muted-foreground/60 mt-1">预计 2-3 分钟，完成后页面自动刷新</p>
          </div>
        )}

        {report?.status === 'COMPLETED' && report.content && (
          <div className="[&_h2]:border-border/30 [&_h2]:pb-1.5 [&_h2]:mb-3 [&_h2]:!text-base
                          [&_h3]:!text-sm [&_h3]:mb-2
                          [&_p]:my-2
                          [&_blockquote]:!border-l-4 [&_blockquote]:!border-brand-300 [&_blockquote]:!bg-brand-50/50 dark:[&_blockquote]:!bg-brand-500/10 [&_blockquote]:pl-4
                          [&_strong]:!text-brand-600 dark:[&_strong]:!text-brand-400
                          [&_li]:my-0.5
                          [&_hr]:!my-4
                          [&_ul]:my-2 [&_ol]:my-2
                          [&_.table-scroll-wrapper]:-mx-4 [&_.table-scroll]:px-4">
            <MarkdownRenderer content={report.content} className="!text-detail !leading-relaxed" />
          </div>
        )}

        {report?.status === 'FAILED' && (
          <div className="text-center py-6">
            <p className="text-xs text-red-500 mb-2">报告生成失败：{report.errorMessage || '未知错误'}</p>
            {isOwner !== false && (
              <button
                onClick={onTrigger}
                className="inline-flex items-center gap-1.5 rounded-xl bg-gradient-to-r from-brand-400 to-brand-500 px-4 py-2 text-xs font-medium text-white shadow-sm active:scale-[0.97] transition-transform"
              >
                <RefreshCw className="h-3 w-3" />
                重新生成
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

export default function RoundTableSessionPage() {
  const { bookId, sessionId } = useParams<{ bookId: string; sessionId: string }>()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const bookIdNum = Number(bookId)

  const [phase, setPhase] = useState<Phase>('loading')
  const [messages, setMessages] = useState<DisplayMessage[]>([])
  const [speakingKey, setSpeakingKey] = useState<string | null>(null)
  const [ttsEnabled, setTtsEnabled] = useState(false)
  const [speakingMsgId, setSpeakingMsgId] = useState<string | null>(null)
  const [currentRound, setCurrentRound] = useState(0)
  const [grabbingAnimation, setGrabbingAnimation] = useState<string | null>(null)
  const [showStats, setShowStats] = useState(false)
  const [showCoverage, setShowCoverage] = useState(false)
  const [coverageVersion, setCoverageVersion] = useState(0)
  const [bookTitle, setBookTitle] = useState<string>('')
  const [showReport, setShowReport] = useState(false)
  const [report, setReport] = useState<RoundTableReport | null>(null)
  const [reportPolling, setReportPolling] = useState(false)
  const [isOwner, setIsOwner] = useState(false)
  const [useOverlay, setUseOverlay] = useState(false)
  const reportPollingStartRef = useRef<number>(0)

  const activeRolesRef = useRef<RoundTableRole[]>([])
  const messagesRef = useRef<DisplayMessage[]>([])
  const speakingKeyRef = useRef<string | null>(null)

  const abortRef = useRef<AbortController | null>(null)
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const currentMsgIdRef = useRef<string | null>(null)
  const currentContentRef = useRef<string>('')
  const userScrollingRef = useRef(false)
  const scrollContainerRef = useRef<HTMLDivElement>(null)
  const discussionLoopRef = useRef<boolean>(false)
  const ttsEnabledSpeakerRef = useRef<string | null>(null)
  const zhVoicesRef = useRef<SpeechSynthesisVoice[]>([])
  const roleVoiceMapRef = useRef<Map<string, SpeechSynthesisVoice>>(new Map())
  const ttsEnabledRef = useRef(false)
  const ttsLastReadIndexRef = useRef<number>(-1)
  const speechEnabledRef = useRef(false)
  const mainLayoutRef = useRef<HTMLDivElement>(null)
  const initialScrollDoneRef = useRef(false)

  useEffect(() => { messagesRef.current = messages }, [messages])

  // 响应式布局检测
  useEffect(() => {
    const checkLayout = () => {
      const width = mainLayoutRef.current?.clientWidth || window.innerWidth
      setUseOverlay(width < 1100)
    }
    checkLayout()
    window.addEventListener('resize', checkLayout)
    return () => window.removeEventListener('resize', checkLayout)
  }, [])

  useEffect(() => {
    if (typeof window !== 'undefined' && window.speechSynthesis) {
      const synth = window.speechSynthesis
      const loadVoices = () => {
        const sorted = getSortedChineseVoices(synth)
        if (sorted.length > 0) zhVoicesRef.current = sorted
        speechService.init().then(() => {
          speechEnabledRef.current = speechService.activeProvider !== 'browser'
        })
      }
      loadVoices()
      window.setTimeout(loadVoices, 500)
      synth.onvoiceschanged = loadVoices
    }
    return () => {
      discussionLoopRef.current = false
      abortRef.current?.abort()
      if (typeof window !== 'undefined' && window.speechSynthesis) {
        window.speechSynthesis.onvoiceschanged = null
        try { window.speechSynthesis.cancel() } catch {}
      }
    }
  }, [])

  useEffect(() => {
    if (!bookIdNum || !sessionId) return
    loadSession()
  }, [bookIdNum, sessionId])

  // 加载报告状态
  const loadReport = useCallback(async () => {
    if (!sessionId) return
    try {
      const res = await getRoundTableReport(sessionId)
      const data = (res as { data?: RoundTableReport })?.data ?? res as RoundTableReport
      setReport(data)
      return data
    } catch {
      return null
    }
  }, [sessionId])

  const loadReportRef = useRef<typeof loadReport>(loadReport)
  useEffect(() => { loadReportRef.current = loadReport }, [loadReport])

  // 报告生成中的轮询（最多 5 分钟，超时自动停止）
  useEffect(() => {
    if (!reportPolling || !sessionId) return
    if (!reportPollingStartRef.current) reportPollingStartRef.current = Date.now()
    const POLLING_TIMEOUT = 5 * 60 * 1000 // 5 分钟
    const interval = setInterval(async () => {
      // 超时兜底：5 分钟还没完成，停止轮询
      if (Date.now() - reportPollingStartRef.current > POLLING_TIMEOUT) {
        setReportPolling(false)
        reportPollingStartRef.current = 0
        // 刷新一下状态，后端可能已标记为 FAILED
        await loadReportRef.current()
        return
      }
      const data = await loadReportRef.current()
      if (data && (data.status === 'COMPLETED' || data.status === 'FAILED')) {
        setReportPolling(false)
        reportPollingStartRef.current = 0
      }
    }, 10000)
    return () => clearInterval(interval)
  }, [reportPolling, sessionId])

  // URL 参数 ?report=1 自动展开报告
  useEffect(() => {
    if (searchParams.get('report') === '1' && sessionId) {
      setShowReport(true)
      loadReport().then(data => {
        if (data && data.status === 'GENERATING') {
          setReportPolling(true)
        }
      })
    }
  }, [searchParams, sessionId])

  // 打开报告面板时，自动加载报告状态
  useEffect(() => {
    if (showReport && sessionId && !report) {
      loadReport().then(data => {
        if (data && data.status === 'GENERATING') {
          setReportPolling(true)
        }
      })
    }
  }, [showReport, sessionId])

  const loadSession = useCallback(async () => {
    setPhase('loading')
    try {
      const [messagesRes, rolesRes, bookRes, sessionRes] = await Promise.all([
        getRoundTableMessages(sessionId!),
        getRoundTableRoles(bookIdNum!),
        getBook(bookIdNum!),
        getRoundTableSession(sessionId!).catch(() => null),
      ])

      const sessionData = (sessionRes as { data?: RoundTableSession })?.data ?? sessionRes as RoundTableSession | null

      const data = (messagesRes as { data?: RoundTableMessage[] })?.data ?? messagesRes as RoundTableMessage[]
      if (Array.isArray(data)) {
        const displayMessages: DisplayMessage[] = data.map(msg => {
          const role = activeRolesRef.current.find(r => r.key === msg.roleKey)
          return {
            id: String(msg.id),
            roleKey: msg.roleKey,
            roleName: msg.roleName || role?.name || ROLE_NAMES[msg.roleKey] || msg.roleKey,
            roleColor: role?.color || ROLE_COLORS[msg.roleKey] || '#6B655C',
            roleIcon: role?.icon || ROLE_ICONS[msg.roleKey] || '👤',
            content: msg.content,
            timestamp: new Date(msg.createdAt).getTime(),
          }
        })
        setMessages(displayMessages)
        messagesRef.current = displayMessages
      }

      const rolesData = (rolesRes as { data?: RoundTableRole[] })?.data ?? rolesRes as RoundTableRole[]
      if (Array.isArray(rolesData) && rolesData.length > 0) {
        // 用会话的 roleKeys 过滤，而不是用后端的 selected 标记
        const sessionRoleKeys = sessionData?.roleKeys?.split(',').map(k => k.trim()) || []
        activeRolesRef.current = sessionRoleKeys.length > 0
          ? rolesData.filter(r => sessionRoleKeys.includes(r.key))
          : rolesData.filter(r => r.selected)
        const roleCount = activeRolesRef.current.length
        if (roleCount > 0) {
          setCurrentRound(Math.floor(messagesRef.current.length / roleCount) + 1)
        }
        // 角色加载后，用后端数据补全消息的 icon/name/color
        setMessages(prev => prev.map(msg => {
          const role = activeRolesRef.current.find(r => r.key === msg.roleKey)
          if (!role) return msg
          return {
            ...msg,
            roleName: msg.roleName || role.name,
            roleColor: role.color || msg.roleColor,
            roleIcon: role.icon || msg.roleIcon,
          }
        }))
      }

      const bookData = (bookRes as { data?: { title?: string } })?.data ?? bookRes as { title?: string }
      if (bookData?.title) {
        setBookTitle(bookData.title)
      }

      // 判断是否为会话创建者
      const currentUserId = useAuthStore.getState().userInfo?.id
      const sessionOwnerId = sessionData?.userId ?? (data as RoundTableMessage[])?.[0]?.userId
      const owner = sessionOwnerId != null && currentUserId != null && sessionOwnerId === currentUserId
      setIsOwner(owner)

      // 已结束会话进入页面后保持结束状态，不自动滚动到底
      const isCompleted = sessionData?.status === 'COMPLETED'
      if (isCompleted) {
        setPhase('completed')
        return
      }

      // 数据加载完成后：有消息 → 暂停；无消息 + 是主人 → 自动开始
      const hasHistory = Array.isArray(data) && data.length > 0
      const canAutoStart = !hasHistory && owner
      setPhase(hasHistory ? 'paused' : canAutoStart ? 'discussing' : 'paused')
      if (canAutoStart) {
        discussionLoopRef.current = true
        setTimeout(() => grabMicAndSpeak(undefined), 1000)
      }
    } catch {
      setPhase('error')
      toast.error('加载会话失败')
    }
  }, [bookIdNum, sessionId])

  useEffect(() => {
    if (messages.length === 0) return
    if (userScrollingRef.current) return
    // 已结束会话进入页面时不自动滚动到底
    if (phase === 'completed') return
    const container = scrollContainerRef.current
    if (!container) return

    requestAnimationFrame(() => {
      if (initialScrollDoneRef.current) {
        // 讨论过程中新增消息：平滑滚到底
        container.scrollTo({ top: container.scrollHeight, behavior: 'smooth' })
      } else {
        // 首次加载：直接滚到底，避免从顶部滑下来的跳动
        container.scrollTop = container.scrollHeight
        initialScrollDoneRef.current = true
      }
    })
  }, [messages, phase])

  const handleUserScroll = () => {
    const container = scrollContainerRef.current
    if (!container) return
    const { scrollTop, scrollHeight, clientHeight } = container
    userScrollingRef.current = scrollHeight - scrollTop - clientHeight > 50
  }

  const splitLongText = (text: string, maxLen = 180): string[] => {
    if (text.length <= maxLen) return [text]
    const chunks: string[] = []
    let start = 0
    while (start < text.length) {
      if (start + maxLen >= text.length) { chunks.push(text.slice(start)); break }
      let end = start + maxLen
      let found = false
      for (let i = end; i > start; i--) {
        if (/[。！？!?.]/.test(text[i - 1])) { chunks.push(text.slice(start, i)); start = i; found = true; break }
      }
      if (found) continue
      for (let i = end; i > start; i--) {
        if (/[，；,;、\n]/.test(text[i - 1])) { chunks.push(text.slice(start, i)); start = i; found = true; break }
      }
      if (!found) { chunks.push(text.slice(start, end)); start = end }
    }
    return chunks
  }

  const ttsQueueRef = useRef<{ text: string; roleKey: string; msgIndex: number }[]>([])
  const ttsSpeakingRef = useRef(false)

  const processTtsQueue = useCallback(() => {
    const synth = window.speechSynthesis
    if (!synth) return
    if (ttsSpeakingRef.current || ttsQueueRef.current.length === 0) return

    try { synth.resume() } catch {}
    try { if (synth.speaking) synth.cancel() } catch {}

    if (ttsSpeakingRef.current || ttsQueueRef.current.length === 0) return

    const { text, roleKey, msgIndex } = ttsQueueRef.current.shift()!
    ttsSpeakingRef.current = true

    // 更新当前朗读的消息 ID
    const msgs = messagesRef.current
    if (msgIndex >= 0 && msgIndex < msgs.length) {
      setSpeakingMsgId(msgs[msgIndex].id)
    }

    if (speechEnabledRef.current) {
      const voiceName = getAzureVoiceForRole(roleKey, ROUNDTABLE_AZURE_VOICE)
      const ttsCfg = ROLE_TTS_CONFIG[roleKey] || { pitch: 1.0, rate: 1.0 }
      speechService.speak(text, voiceName, ttsCfg.rate, ttsCfg.pitch, () => {
        if (msgIndex >= 0) ttsLastReadIndexRef.current = Math.max(ttsLastReadIndexRef.current, msgIndex)
        ttsSpeakingRef.current = false
        if (ttsQueueRef.current.length === 0) setSpeakingMsgId(null)
        processTtsQueue()
      })
      return
    }

    // ── 浏览器 TTS 路径 ──
    const speakBrowserChunk = (chunkText: string, triedVoices: string[] = []) => {
      const utterance = new SpeechSynthesisUtterance(chunkText)
      const config = ROLE_TTS_CONFIG[roleKey] || { pitch: 1.0, rate: 1.0 }
      utterance.pitch = Math.max(0.5, Math.min(2.0, config.pitch))
      utterance.rate = config.rate
      utterance.lang = 'zh-CN'
      utterance.volume = 1.0

      let zhVoices = zhVoicesRef.current
      if (zhVoices.length === 0 && window.speechSynthesis) {
        try {
          zhVoices = getSortedChineseVoices(window.speechSynthesis)
          if (zhVoices.length > 0) zhVoicesRef.current = zhVoices
        } catch {}
      }
      if (zhVoices.length > 0) {
        // 排除已尝试失败的语音
        const available = triedVoices.length > 0
          ? zhVoices.filter(v => !triedVoices.includes(v.name))
          : zhVoices

        const voice = available.length > 0
          ? assignVoiceForRoleSafe(roleKey, roleVoiceMapRef.current, available)
          : null

        if (voice) {
          utterance.voice = voice
          utterance.lang = voice.lang
          console.log('[TTS] Browser voice:', voice.name, '| lang:', voice.lang, '| role:', roleKey)
        } else if (zhVoices.length > 0) {
          // 所有已知语音都静音或被排除 → 用第一个非静音试试
          const fallback = zhVoices.find(v => voiceTester.getStatus(v.name) !== 'silent')
          if (fallback) {
            utterance.voice = fallback
            utterance.lang = fallback.lang
            console.log('[TTS] Browser voice (fallback):', fallback.name)
          }
        }
      }

      // ── 无声检测 ──
      const voiceName = utterance.voice?.name ?? '(default)'
      const startTime = performance.now()

      utterance.onend = () => {
        const duration = performance.now() - startTime
        // 每字符正常耗时约 200-400ms，静音时几乎瞬间 onend（< 文本长度×40ms）
        const silentThreshold = Math.max(120, chunkText.length * 40)
        const isSilent = duration < silentThreshold

        if (isSilent && voiceName !== '(default)') {
          console.warn('[TTS] 无声语音:', voiceName, '耗时:', Math.round(duration), 'ms → 标记静音并换音色')
          voiceTester.markSilent(voiceName)
          // 清除该角色的语音缓存，强制重分配
          roleVoiceMapRef.current.delete(roleKey)

          // 重试：从未尝试过的语音中再选
          const nextTried = [...triedVoices, voiceName]
          const zhVoices = zhVoicesRef.current
          const remaining = zhVoices.filter(v => !nextTried.includes(v.name) && voiceTester.getStatus(v.name) !== 'silent')
          if (remaining.length > 0) {
            // 放回队首重试
            ttsQueueRef.current.unshift({ text: chunkText, roleKey, msgIndex })
            ttsSpeakingRef.current = false
            processTtsQueue()
            return
          }
          console.warn('[TTS] 所有语音均已尝试或静音，放弃本条:', { text: chunkText.slice(0, 30) })
        }

        // 正常结束 or 无声且无更多可选语音
        if (msgIndex >= 0) ttsLastReadIndexRef.current = Math.max(ttsLastReadIndexRef.current, msgIndex)
        ttsSpeakingRef.current = false
        if (ttsQueueRef.current.length === 0) setSpeakingMsgId(null)
        processTtsQueue()
      }

      utterance.onerror = () => {
        ttsSpeakingRef.current = false
        if (ttsQueueRef.current.length === 0) setSpeakingMsgId(null)
        processTtsQueue()
      }

      try { synth.speak(utterance) } catch { ttsSpeakingRef.current = false }
    }

    speakBrowserChunk(text)
  }, [])

  const enqueueTtsRef = useRef<(text: string, roleKey: string, msgIndex: number) => void>((text, roleKey, msgIndex) => {
    const cleanText = text.replace(/```[\s\S]*?```/g, '').replace(/`[^`]+`/g, '')
      .replace(/\*\*([^*]+)\*\*/g, '$1').replace(/\*([^*]+)\*/g, '$1')
      .replace(/^#{1,6}\s+/gm, '').replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
      .replace(/^[-*]\s+/gm, '').replace(/^>\s+/gm, '').trim()
    if (!cleanText) return
    const chunks = splitLongText(cleanText)
    chunks.forEach(chunk => {
      ttsQueueRef.current.push({ text: chunk, roleKey, msgIndex })
    })
    processTtsQueue()
  })

  const stopDiscussion = useCallback(() => {
    discussionLoopRef.current = false
    abortRef.current?.abort()
    abortRef.current = null
    speechService.stop()
    try { window.speechSynthesis?.cancel() } catch {}
    ttsEnabledSpeakerRef.current = null
    ttsLastReadIndexRef.current = -1
    ttsEnabledRef.current = false
    ttsQueueRef.current = []
    ttsSpeakingRef.current = false
    speakingKeyRef.current = null
    setTtsEnabled(false)
    setSpeakingKey(null)
    // 清理残留的流式占位消息（对齐辩论页 handleStop）
    setMessages(prev => prev.map(m =>
      m.streaming ? { ...m, streaming: undefined } : m
    ))
  }, [])

  const pauseDiscussion = useCallback(() => {
    stopDiscussion()
    setPhase('paused')
  }, [stopDiscussion])

  const endDiscussion = useCallback(async () => {
    if (!sessionId) return
    stopDiscussion()
    try {
      await updateRoundTableSessionStatus(sessionId, 'COMPLETED')
      setPhase('completed')
    } catch {
      toast.error('结束讨论失败')
    }
  }, [sessionId, stopDiscussion])

  const grabMicAndSpeak = useCallback(async (lastSpeakerKey: string | undefined) => {
    if (!discussionLoopRef.current) return
    if (speakingKeyRef.current !== null) return

    try {
      const nextSpeakerRes = await getNextSpeaker(sessionId!)
      let nextSpeaker = (nextSpeakerRes as { data?: string })?.data ?? nextSpeakerRes as string
      if (!nextSpeaker) {
        setPhase('paused')
        return
      }

      // 前端兜底：禁止连续发言
      if (lastSpeakerKey && nextSpeaker === lastSpeakerKey) {
        // 后端返回了连续发言者，从其他角色中随机选择
        const otherRoles = activeRolesRef.current
          .filter(r => r.key !== lastSpeakerKey && r.selected)
          .map(r => r.key)
        if (otherRoles.length > 0) {
          nextSpeaker = otherRoles[Math.floor(Math.random() * otherRoles.length)]
        }
      }

      setGrabbingAnimation(nextSpeaker)
      setTimeout(() => setGrabbingAnimation(null), 800)

      setSpeakingKey(nextSpeaker)
      speakingKeyRef.current = nextSpeaker
      const msgId = `msg-${Date.now()}`
      currentMsgIdRef.current = msgId
      currentContentRef.current = ''

      const nextRole = activeRolesRef.current.find(r => r.key === nextSpeaker)
      const displayMsg: DisplayMessage = {
        id: msgId,
        roleKey: nextSpeaker,
        roleName: nextRole?.name || ROLE_NAMES[nextSpeaker] || nextSpeaker,
        roleColor: nextRole?.color || ROLE_COLORS[nextSpeaker] || '#6B655C',
        roleIcon: nextRole?.icon || ROLE_ICONS[nextSpeaker] || '👤',
        content: '',
        timestamp: Date.now(),
        streaming: true,
      }
      setMessages(prev => [...prev, displayMsg])

      abortRef.current?.abort()
      const stream = streamCharacterSpeak(bookIdNum, nextSpeaker, sessionId!)
      abortRef.current = stream.abortController

      stream.onMessage((text: string) => {
        if (!discussionLoopRef.current) { stream.abortController.abort(); return }
        currentContentRef.current += text
        setMessages(prev => {
          const updated = [...prev]
          const idx = updated.findIndex(m => m.id === msgId)
          if (idx >= 0) updated[idx] = { ...updated[idx], content: currentContentRef.current }
          return updated
        })
      })

      stream.onDone(() => {
        setMessages(prev => {
          const updated = [...prev]
          const idx = updated.findIndex(m => m.id === msgId)
          if (idx >= 0) updated[idx] = { ...updated[idx], streaming: undefined }
          return updated
        })
        setSpeakingKey(null)
        speakingKeyRef.current = null

        if (ttsEnabledRef.current) {
          enqueueTtsRef.current(currentContentRef.current, nextSpeaker, messagesRef.current.length)
        }

        // 按实际消息数/角色数计算轮数（每轮所有角色各发言一次）
        const roleCount = activeRolesRef.current.length
        setCurrentRound(roleCount > 0 ? Math.floor(messagesRef.current.length / roleCount) + 1 : 1)
        setCoverageVersion(prev => prev + 1)

        setTimeout(() => {
          if (discussionLoopRef.current) grabMicAndSpeak(nextSpeaker)
        }, 1500)
      })

      stream.onError((_err: Error) => {
        setMessages(prev => {
          const updated = [...prev]
          const idx = updated.findIndex(m => m.id === msgId)
          if (idx >= 0) updated[idx] = { ...updated[idx], streaming: undefined, content: '（发言失败）' }
          return updated
        })
        setSpeakingKey(null)
        speakingKeyRef.current = null
        if (discussionLoopRef.current) setTimeout(() => grabMicAndSpeak(lastSpeakerKey), 2000)
      })
    } catch {
      setSpeakingKey(null)
      speakingKeyRef.current = null
      if (discussionLoopRef.current) setTimeout(() => grabMicAndSpeak(lastSpeakerKey), 2000)
    }
  }, [bookIdNum, sessionId])

  const continueDiscussion = useCallback(async () => {
    if (!sessionId) return
    try {
      await updateRoundTableSessionStatus(sessionId, 'ACTIVE')
      discussionLoopRef.current = true
      setPhase('discussing')
      const lastMsg = messagesRef.current[messagesRef.current.length - 1]
      setTimeout(() => grabMicAndSpeak(lastMsg?.roleKey), 1000)
    } catch {
      toast.error('继续讨论失败')
    }
  }, [sessionId, grabMicAndSpeak])

  const handleTriggerReport = useCallback(async () => {
    if (!sessionId) return
    try {
      const res = await triggerRoundTableReport(sessionId)
      const data = (res as { data?: RoundTableReport })?.data ?? res as RoundTableReport
      setReport(data)
      if (data.status === 'GENERATING') {
        setReportPolling(true)
        toast.success('解读报告生成中，预计 2-3 分钟，完成后页面自动刷新')
      } else if (data.status === 'COMPLETED') {
        toast.success('解读报告已就绪')
      }
    } catch {
      toast.error('触发报告生成失败')
    }
  }, [sessionId])

  // 点击报告按钮时：切换面板 + 打开时如果正在生成则恢复轮询
  const handleToggleReport = useCallback(() => {
    if (showReport) {
      setShowReport(false)
      return
    }
    setShowReport(true)
    if (report?.status === 'GENERATING' && !reportPolling) {
      setReportPolling(true)
    } else if (!report && sessionId) {
      loadReport().then(data => {
        if (data && data.status === 'GENERATING') {
          setReportPolling(true)
        }
      })
    }
  }, [showReport, report, reportPolling, sessionId, loadReport])

  const handleToggleSpeak = useCallback((msgId: string) => {
    const synth = window.speechSynthesis
    if (!synth) return

    // 如果正在朗读同一条，停止
    if (speakingMsgId === msgId) {
      speechService.stop()
      try { synth.cancel() } catch {}
      setSpeakingMsgId(null)
      ttsSpeakingRef.current = false
      ttsQueueRef.current = []
      return
    }

    // 停止当前朗读，从点击的这条开始往后顺序朗读
    speechService.stop()
    try { synth.cancel() } catch {}
    ttsSpeakingRef.current = false
    ttsQueueRef.current = []

    // 找到点击消息在列表中的索引
    const msgs = messagesRef.current
    const startIdx = msgs.findIndex(m => m.id === msgId)
    if (startIdx < 0) return

    setSpeakingMsgId(msgId)
    ttsLastReadIndexRef.current = startIdx - 1

    // 从点击的消息开始，把后面所有消息加入朗读队列
    for (let i = startIdx; i < msgs.length; i++) {
      const m = msgs[i]
      if (!m.streaming && m.content) {
        enqueueTtsRef.current(m.content, m.roleKey, i)
      }
    }
  }, [speakingMsgId])

  const speakCounts = messages.reduce<Record<string, number>>((acc, msg) => {
    acc[msg.roleKey] = (acc[msg.roleKey] || 0) + 1
    return acc
  }, {})

  const roles = activeRolesRef.current.length > 0 ? activeRolesRef.current : []
  const isMobile = useIsMobile()
  const showSidePanel = showCoverage && !isMobile

  // 切换到手机版时自动关闭桌面侧面板
  useEffect(() => {
    if (isMobile) {
      setShowCoverage(false)
      setShowReport(false)
    }
  }, [isMobile])

  return (
    <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col overflow-hidden bg-background">
          <header className="shrink-0 flex items-center gap-3 border-b border-border/30 bg-navbar/95 px-4 py-2.5 backdrop-blur-xl z-20 pt-safe-top">
        <button
          onClick={() => navigate(-1)}
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl hover:bg-muted transition-colors"
        >
          <ArrowLeft className="h-4 w-4" />
        </button>
        <div className="min-w-0 flex-1">
          <h1 className="text-sm font-bold text-foreground truncate">
            {bookTitle ? `《${bookTitle}》圆桌派讨论` : '圆桌派讨论'}
          </h1>
          <p className="text-xs text-muted-foreground truncate flex items-center gap-1.5">
            {phase === 'loading' ? '加载中...' :
             phase === 'discussing' ? '讨论进行中' :
             phase === 'paused' ? '讨论已暂停' :
             phase === 'completed' ? (
               <><span className="inline-block h-1.5 w-1.5 rounded-full bg-red-400" />讨论已结束</>
             ) :
             phase === 'error' ? '加载出错' : ''}
          </p>
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden relative" ref={mainLayoutRef}>
        <div className="flex flex-1 flex-col overflow-hidden relative min-w-0">
          {showStats && !isMobile && (
            <SpeakStatsPanel
              roles={roles}
              messages={messages}
              onClose={() => setShowStats(false)}
            />
          )}
          {showStats && isMobile && (
            <Sheet open={showStats} onOpenChange={(v) => !v && setShowStats(false)}>
              <SheetContent side="bottom" className="rounded-t-2xl p-0 max-h-[85vh] [&>button]:hidden">
                <SpeakStatsPanel
                  roles={roles}
                  messages={messages}
                  onClose={() => setShowStats(false)}
                  isMobile
                />
              </SheetContent>
            </Sheet>
          )}

          <RoleBar
            roles={roles}
            speakCounts={speakCounts}
            speakingKey={speakingKey}
            grabbingKey={grabbingAnimation}
          />

          <div ref={scrollContainerRef} onScroll={handleUserScroll} className="flex-1 overflow-y-auto overscroll-y-contain">
            {messages.length === 0 && phase === 'loading' && (
              <div className="flex flex-1 flex-col items-center justify-center py-12 text-muted-foreground">
                <Loader2 className="h-8 w-8 animate-spin" />
                <p className="text-sm font-medium mt-4">加载讨论记录...</p>
              </div>
            )}
            <div className="min-h-full flex flex-col justify-end p-4 max-w-3xl mx-auto">
              {messages.map(msg => (
                <MessageBubble
                  key={msg.id}
                  msg={msg}
                  isSpeaking={speakingMsgId === msg.id}
                  onToggleSpeak={() => handleToggleSpeak(msg.id)}
                />
              ))}

              {/* 讨论结束标识：点击继续可删除并恢复讨论 */}
              {phase === 'completed' && (
                <div className="flex justify-center my-6">
                  <div className="inline-flex flex-col items-center gap-3 rounded-2xl border border-border/40 bg-muted/50 px-6 py-4 backdrop-blur-sm">
                    <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
                      <Square className="h-4 w-4 text-red-400" />
                      <span>圆桌派讨论已结束</span>
                    </div>
                    {isOwner && (
                      <button
                        onClick={continueDiscussion}
                        className="flex items-center gap-1.5 rounded-full bg-brand-500 px-4 py-1.5 text-xs font-medium text-white hover:bg-brand-600 transition-colors"
                      >
                        <Play className="h-3 w-3" />
                        继续讨论
                      </button>
                    )}
                  </div>
                </div>
              )}

              <div ref={messagesEndRef} />
            </div>
          </div>

          <div
            className="shrink-0 border-t border-border/20 bg-navbar/95 backdrop-blur-xl px-4 py-2.5"
            style={{ paddingBottom: 'calc(env(safe-area-inset-bottom, 0px) + 0.75rem)' }}
          >
            {/* PC: 左右分布 + 文字标签；手机: 图标-only */}
            <div className="flex items-center gap-2 max-w-3xl mx-auto">
              {/* 左区：操作按钮 */}
              <div className="flex items-center gap-1">
                <button
                  onClick={() => {
                    if (!ttsEnabled) {
                      ttsEnabledRef.current = true
                      try {
                        const sorted = getSortedChineseVoices(window.speechSynthesis)
                        if (sorted.length > 0) zhVoicesRef.current = sorted
                      } catch {}

                      const msgs = messagesRef.current
                      let startIdx = 0
                      for (let i = 0; i < msgs.length; i++) {
                        if (!msgs[i].streaming && msgs[i].content) { startIdx = i; break }
                      }
                      ttsLastReadIndexRef.current = startIdx - 1
                      for (let i = startIdx; i < msgs.length; i++) {
                        const m = msgs[i]
                        if (!m.streaming && m.content) {
                          enqueueTtsRef.current(m.content, m.roleKey, i)
                        }
                      }
                    } else {
                      ttsEnabledRef.current = false
                      ttsQueueRef.current = []
                      ttsSpeakingRef.current = false
                      try { window.speechSynthesis?.cancel() } catch {}
                      if (phase === 'discussing') {
                        ttsLastReadIndexRef.current = -1
                      }
                    }
                    setTtsEnabled(!ttsEnabled)
                  }}
                  className={`flex items-center justify-center gap-1.5 rounded-full sm:rounded-xl p-0 sm:px-3 py-2 sm:py-2 h-10 sm:h-auto w-10 sm:w-auto text-xs font-medium transition-all duration-200 ${
                    ttsEnabled
                      ? 'bg-brand-100 text-brand-500 border border-brand-200'
                      : 'bg-muted text-muted-foreground hover:text-foreground'
                  }`}
                >
                  <Volume2 className="h-3 w-3 shrink-0" />
                  <span className="hidden sm:inline">{ttsEnabled ? '朗读中' : '语音关'}</span>
                </button>

                {isOwner && phase === 'discussing' && (
                  <button
                    onClick={pauseDiscussion}
                    className="flex items-center justify-center gap-1.5 rounded-full sm:rounded-xl p-0 sm:px-3 py-2 sm:py-2 h-10 sm:h-auto w-10 sm:w-auto text-xs font-medium bg-amber-500/10 text-amber-600 hover:bg-amber-500/20 transition-colors"
                  >
                    <Pause className="h-3 w-3 shrink-0" />
                    <span className="hidden sm:inline">暂停</span>
                  </button>
                )}

                {isOwner && (phase === 'paused' || phase === 'completed') && messages.some(m => m.streaming === undefined) && (
                  <button
                    onClick={continueDiscussion}
                    className="flex items-center justify-center gap-1.5 rounded-full sm:rounded-xl p-0 sm:px-3 py-2 sm:py-2 h-10 sm:h-auto w-10 sm:w-auto text-xs font-semibold bg-gradient-to-r from-brand-400 to-brand-500 text-white shadow-md shadow-brand-400/20 active:scale-[0.97] transition-transform"
                  >
                    <Play className="h-3 w-3 shrink-0" />
                    <span className="hidden sm:inline">继续</span>
                  </button>
                )}

                {phase === 'error' && (
                  <button
                    onClick={loadSession}
                    className="flex items-center justify-center gap-1.5 rounded-full sm:rounded-xl p-0 sm:px-3 py-2 sm:py-2 h-10 sm:h-auto w-10 sm:w-auto text-xs font-semibold bg-gradient-to-r from-brand-400 to-brand-500 text-white shadow-md shadow-brand-400/20 active:scale-[0.97] transition-transform"
                  >
                    <RefreshCw className="h-3 w-3 shrink-0" />
                    <span className="hidden sm:inline">重试</span>
                  </button>
                )}

                {isOwner && phase !== 'completed' && phase !== 'loading' && phase !== 'error' && (
                  <button
                    onClick={endDiscussion}
                    className="flex items-center justify-center gap-1.5 rounded-full sm:rounded-xl p-0 sm:px-3 py-2 sm:py-2 h-10 sm:h-auto w-10 sm:w-auto text-xs font-medium bg-red-500/10 text-red-500 hover:bg-red-500/20 transition-colors"
                  >
                    <Square className="h-3 w-3 shrink-0" />
                    <span className="hidden sm:inline">结束</span>
                  </button>
                )}

                {!isOwner && phase !== 'error' && (
                  <span className="text-xs text-muted-foreground italic ml-1">观摩模式</span>
                )}
              </div>

              <div className="flex-1" />

              {/* 右区：信息开关 */}
              <div className="flex items-center gap-1">
                {messages.length > 0 && (
                  <button
                    onClick={() => setShowStats(!showStats)}
                    className={`flex items-center justify-center gap-1 rounded-full sm:rounded-xl p-0 sm:px-2.5 py-2 sm:py-2 h-10 sm:h-auto w-10 sm:w-auto text-xs transition-colors ${
                      showStats ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground hover:text-foreground'
                    }`}
                  >
                    <BarChart3 className="h-3 w-3 shrink-0" />
                    <span className="hidden sm:inline">统计</span>
                  </button>
                )}

                {messages.length > 0 && (
                  <button
                    onClick={() => setShowCoverage(!showCoverage)}
                    className={`flex items-center justify-center gap-1 rounded-full sm:rounded-xl p-0 sm:px-2.5 py-2 sm:py-2 h-10 sm:h-auto w-10 sm:w-auto text-xs transition-colors ${
                      showCoverage ? 'bg-brand-100 text-brand-500' : 'bg-muted text-muted-foreground hover:text-foreground'
                    }`}
                  >
                    <Target className="h-3 w-3 shrink-0" />
                    <span className="hidden sm:inline">覆盖度</span>
                  </button>
                )}

                {messages.length > 0 && (
                  <button
                    onClick={handleToggleReport}
                    className={`flex items-center justify-center gap-1 rounded-full sm:rounded-xl p-0 sm:px-2.5 py-2 sm:py-2 h-10 sm:h-auto w-10 sm:w-auto text-xs transition-colors ${
                      showReport ? 'bg-brand-100 text-brand-500' : 'bg-muted text-muted-foreground hover:text-foreground'
                    }`}
                  >
                    {reportPolling || report?.status === 'GENERATING' ? (
                      <Loader2 className="h-3 w-3 shrink-0 animate-spin" />
                    ) : (
                      <FileText className="h-3 w-3 shrink-0" />
                    )}
                    <span className="hidden sm:inline">报告</span>
                  </button>
                )}

                <span className="text-xs text-muted-foreground/60 shrink-0">
                  {roles.length}人·{currentRound}轮
                </span>
              </div>
            </div>
          </div>
        </div>

        {showCoverage && !showSidePanel && (
          <CoveragePanel
            sessionId={sessionId ?? null}
            open={showCoverage}
            onClose={() => setShowCoverage(false)}
            isMobile={true}
            version={coverageVersion}
          />
        )}

        {/* 话题覆盖度面板：宽屏并排，窄屏覆盖 */}
        {useOverlay && showCoverage && (
          <div className="absolute inset-0 z-30 flex justify-end bg-black/20" onClick={() => setShowCoverage(false)}>
            <div className="h-full w-80 border-l border-border/20 bg-navbar/95 backdrop-blur-xl shadow-xl" onClick={e => e.stopPropagation()}>
              <CoveragePanel
                sessionId={sessionId ?? null}
                open={showCoverage}
                onClose={() => setShowCoverage(false)}
                isMobile={false}
                version={coverageVersion}
              />
            </div>
          </div>
        )}
        {!useOverlay && (
          <div className={`shrink-0 overflow-hidden transition-all duration-300 ease-out flex flex-col ${showSidePanel ? 'w-80' : 'w-0'}`}>
            <CoveragePanel
              sessionId={sessionId ?? null}
              open={showCoverage}
              onClose={() => setShowCoverage(false)}
              isMobile={false}
              version={coverageVersion}
            />
          </div>
        )}

        {/* 报告面板：始终覆盖弹出 */}
        {showReport && !isMobile && (
          <div className="absolute inset-0 z-30 flex justify-end bg-black/20" onClick={() => setShowReport(false)}>
            <div className="h-full w-[420px] max-w-[80vw] border-l border-border/20 bg-navbar/95 backdrop-blur-xl shadow-xl animate-in slide-in-from-right duration-200 flex flex-col overflow-hidden" onClick={e => e.stopPropagation()}>
              <ReportPanel
                report={report}
                isGenerating={reportPolling || report?.status === 'GENERATING'}
                isOwner={isOwner}
                onTrigger={handleTriggerReport}
                onClose={() => setShowReport(false)}
              />
            </div>
          </div>
        )}

        {showReport && isMobile && (
          <Sheet open={showReport} onOpenChange={(v) => !v && setShowReport(false)}>
            <SheetContent side="bottom" className="rounded-t-2xl p-0 max-h-[85vh] [&>button]:hidden">
              <ReportPanel
                report={report}
                isGenerating={reportPolling || report?.status === 'GENERATING'}
                isOwner={isOwner}
                onTrigger={handleTriggerReport}
                onClose={() => setShowReport(false)}
              />
            </SheetContent>
          </Sheet>
        )}
      </div>
    </div>
  )
}