import { useState, useEffect, useRef, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  ArrowLeft, Volume2, Square, Loader2, Play, Users, Pause, History, Trash2,
  BookOpen, Sparkles, MessageSquare, BarChart3, RefreshCw,
} from 'lucide-react'
import MarkdownRenderer from '@/components/ui/markdown-renderer'
import {
  getRoundTableRoles, createRoundTableSession, getRoundTableSessions,
  getRoundTableMessages, deleteRoundTableSession, streamCharacterSpeak,
  getNextSpeaker,
} from '@/api/roundTable'
import { getBook } from '@/api/book'
import type { RoundTableRole, RoundTableMessage, RoundTableSession } from '@/types/roundTable'
import type { Book } from '@/types/book'
import {
  ROLE_COLORS, ROLE_NAMES, ROLE_TITLES, ROLE_ICONS, ROLE_TTS_CONFIG,
  describePersonality,
} from '@/types/roundTable'
import { toast } from 'sonner'

type Phase = 'loading' | 'select' | 'discussing' | 'paused' | 'error'

interface DisplayMessage {
  id: string
  roleKey: string
  roleName: string
  roleColor: string
  content: string
  timestamp: number
  streaming?: boolean
}

// ==================== 工具函数 ====================

function hexToRgba(hex: string, alpha: number) {
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const b = parseInt(hex.slice(5, 7), 16)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

// ==================== 骨架屏 ====================

function SeatSkeleton({ index, total }: { index: number; total: number }) {
  const angle = (2 * Math.PI * index / total) - Math.PI / 2
  const radius = 38
  const x = 50 + radius * Math.cos(angle)
  const y = 50 + radius * Math.sin(angle)
  return (
    <div
      className="absolute flex flex-col items-center gap-1"
      style={{ left: `${x}%`, top: `${y}%`, transform: 'translate(-50%, -50%)' }}
    >
      <div className="h-12 w-12 rounded-full bg-muted animate-pulse" />
      <div className="h-2.5 w-10 rounded bg-muted animate-pulse" />
      <div className="h-1.5 w-8 rounded bg-muted/60 animate-pulse" />
    </div>
  )
}

function LoadingPhase() {
  const seatCount = 5
  return (
    <div className="flex flex-1 flex-col items-center justify-center px-4">
      <div className="relative w-full max-w-md aspect-square mb-6">
        <div className="absolute inset-[15%] rounded-full border-2 border-primary/10 bg-primary/5" />
        <div className="absolute inset-[20%] rounded-full border border-primary/5 flex flex-col items-center justify-center gap-2">
          <Loader2 className="h-6 w-6 animate-spin text-primary" />
          <span className="text-xs text-muted-foreground">邀请嘉宾中...</span>
        </div>
        {Array.from({ length: seatCount }).map((_, i) => (
          <SeatSkeleton key={i} index={i} total={seatCount} />
        ))}
      </div>
      <p className="text-sm text-muted-foreground">AI 正在根据书籍内容推荐讨论嘉宾</p>
    </div>
  )
}

// ==================== 角色卡片组件 ====================

function RoleCard({
  role,
  isSelected,
  isHost,
  onToggle,
}: {
  role: RoundTableRole
  isSelected: boolean
  isHost: boolean
  onToggle: () => void
}) {
  const color = ROLE_COLORS[role.key] || '#6B655C'
  const traits = describePersonality(role)

  return (
    <button
      onClick={onToggle}
      disabled={isHost}
      className={`group relative flex flex-col items-center gap-2 rounded-2xl border p-4 transition-all duration-300 ${
        isSelected
          ? 'border-[var(--role-color)]/40 bg-[var(--role-color)]/[0.04] shadow-sm'
          : 'border-border/40 bg-card hover:border-border/60 hover:shadow-sm'
      } ${isHost ? 'cursor-default' : 'cursor-pointer active:scale-[0.97]'}`}
      style={{ '--role-color': color } as React.CSSProperties}
    >
      {/* 选中角标 */}
      {isSelected && !isHost && (
        <span className="absolute -top-1.5 -right-1.5 flex h-5 w-5 items-center justify-center rounded-full bg-[var(--role-color)] text-[10px] text-white shadow-sm">
          <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"><polyline points="20 6 9 17 4 12" /></svg>
        </span>
      )}
      {isHost && (
        <span className="absolute -top-1.5 -right-1.5 rounded-full bg-primary px-2 py-0.5 text-[9px] font-bold text-primary-foreground shadow-sm">
          必选
        </span>
      )}

      {/* 头像 */}
      <div
        className="flex h-12 w-12 items-center justify-center rounded-full text-xl transition-transform duration-300 group-hover:scale-105"
        style={{
          backgroundColor: hexToRgba(color, 0.1),
          border: `1.5px solid ${hexToRgba(color, 0.25)}`,
        }}
      >
        {ROLE_ICONS[role.key] || '👤'}
      </div>

      {/* 名字和称号 */}
      <div className="text-center">
        <span className="block text-xs font-bold" style={{ color }}>{role.name}</span>
        <span className="block text-[10px] text-muted-foreground mt-0.5">{ROLE_TITLES[role.key]}</span>
      </div>

      {/* 性格标签 */}
      {traits.length > 0 && (
        <div className="flex flex-wrap gap-1 justify-center">
          {traits.slice(0, 3).map(t => (
            <span
              key={t}
              className="rounded-full px-1.5 py-0.5 text-[8px] font-medium"
              style={{
                backgroundColor: hexToRgba(color, 0.08),
                color: hexToRgba(color, 0.85),
              }}
            >
              {t}
            </span>
          ))}
        </div>
      )}
    </button>
  )
}

// ==================== 横向角色条组件 ====================

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
    <div className="shrink-0 border-b border-border/20 bg-background/80 backdrop-blur-xl px-3 py-2">
      <div className="flex items-center gap-1.5 overflow-x-auto scrollbar-hide">
        {roles.map(role => {
          const color = ROLE_COLORS[role.key] || '#6B655C'
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
                {ROLE_ICONS[role.key] || '👤'}
              </div>
              <div className="flex flex-col">
                <span
                  className="text-[10px] font-semibold leading-tight"
                  style={{ color: isActive ? color : undefined }}
                >
                  {role.name}
                </span>
                <span className="text-[8px] text-muted-foreground/60 leading-tight">
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

// ==================== 消息气泡组件 ====================

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
      {/* 左侧竖线装饰 + 头像 */}
      <div className="flex flex-col items-center gap-1 shrink-0">
        <div
          className="flex h-9 w-9 items-center justify-center rounded-full text-base"
          style={{
            backgroundColor: hexToRgba(color, 0.1),
            border: `1.5px solid ${hexToRgba(color, 0.25)}`,
          }}
        >
          {ROLE_ICONS[msg.roleKey] || '👤'}
        </div>
        <div
          className="w-[2px] flex-1 rounded-full min-h-[20px]"
          style={{ backgroundColor: hexToRgba(color, 0.15) }}
        />
      </div>

      {/* 内容区 */}
      <div className="min-w-0 flex-1 pb-3">
        {/* 名字 + 时间 + 状态 */}
        <div className="flex items-center gap-2 mb-1.5">
          <span className="text-[11px] font-bold" style={{ color }}>{msg.roleName}</span>
          <span className="text-[9px] text-muted-foreground/40">
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

        {/* 消息内容 — 左侧带色竖线 */}
        <div className="relative">
          <div
            className="absolute left-0 top-0 bottom-0 w-[3px] rounded-full"
            style={{ backgroundColor: hexToRgba(color, 0.3) }}
          />
          <div
            className="rounded-r-xl rounded-bl-xl px-4 py-3 text-[13px] leading-relaxed ml-3"
            style={{
              backgroundColor: hexToRgba(color, 0.04),
              border: `1px solid ${hexToRgba(color, 0.08)}`,
              borderLeft: 'none',
            }}
          >
            {msg.content ? (
              <MarkdownRenderer content={msg.content} className="!text-[13px]" />
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

        {/* 朗读按钮 */}
        {!msg.streaming && msg.content && msg.content !== '（发言失败）' && (
          <button
            onClick={onToggleSpeak}
            className={`mt-1.5 flex items-center gap-1 rounded-lg px-2 py-1 text-[9px] transition-all duration-200 opacity-0 group-hover:opacity-100 ${
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

// ==================== 发言统计面板 ====================

function SpeakStatsPanel({
  roles,
  messages,
  onClose,
}: {
  roles: RoundTableRole[]
  messages: DisplayMessage[]
  onClose: () => void
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
    <div className="absolute inset-x-0 top-0 z-30 bg-background/95 backdrop-blur-xl border-b border-border/20 shadow-lg animate-in slide-in-from-top duration-200">
      <div className="px-4 py-3">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-xs font-bold flex items-center gap-1.5">
            <BarChart3 className="h-3.5 w-3.5 text-muted-foreground" />
            发言统计
          </h3>
          <button onClick={onClose} className="text-[10px] text-muted-foreground hover:text-foreground">关闭</button>
        </div>
        <div className="space-y-2">
          {stats.map(({ role, count, totalChars }) => {
            const color = ROLE_COLORS[role.key] || '#6B655C'
            const pct = (count / maxCount) * 100
            return (
              <div key={role.key} className="flex items-center gap-2">
                <span className="text-[10px] font-medium w-12 truncate" style={{ color }}>{role.name}</span>
                <div className="flex-1 h-2 rounded-full bg-muted overflow-hidden">
                  <div
                    className="h-full rounded-full transition-all duration-500"
                    style={{ width: `${pct}%`, backgroundColor: color }}
                  />
                </div>
                <span className="text-[9px] text-muted-foreground w-16 text-right">
                  {count}次 · {totalChars}字
                </span>
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}

// ==================== 历史会话卡片 ====================

function SessionCard({
  session,
  onLoad,
  onDelete,
}: {
  session: RoundTableSession
  onLoad: () => void
  onDelete: () => void
}) {
  const roleKeysArr = session.roleKeys ? session.roleKeys.split(',') : []
  const dateStr = new Date(session.updateTime || session.createTime).toLocaleDateString('zh-CN', {
    month: 'short',
    day: 'numeric',
  })

  return (
    <div className="flex items-center gap-3 rounded-2xl border border-border/40 bg-card p-3 hover:border-border/60 hover:shadow-sm transition-all duration-200">
      <button onClick={onLoad} className="flex-1 text-left min-w-0">
        <p className="text-xs font-semibold truncate">{session.title || '圆桌派讨论'}</p>
        <div className="flex items-center gap-1.5 mt-1">
          <div className="flex -space-x-1">
            {roleKeysArr.slice(0, 5).map(key => (
              <span
                key={key}
                className="flex h-4 w-4 items-center justify-center rounded-full text-[8px] border border-background"
                style={{ backgroundColor: hexToRgba(ROLE_COLORS[key] || '#6B655C', 0.15) }}
              >
                {ROLE_ICONS[key] || '👤'}
              </span>
            ))}
            {roleKeysArr.length > 5 && (
              <span className="flex h-4 w-4 items-center justify-center rounded-full text-[7px] bg-muted text-muted-foreground border border-background">
                +{roleKeysArr.length - 5}
              </span>
            )}
          </div>
          <span className="text-[10px] text-muted-foreground">{dateStr}</span>
        </div>
      </button>
      <button
        onClick={onDelete}
        className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl text-muted-foreground hover:bg-red-500/10 hover:text-red-500 transition-colors"
      >
        <Trash2 className="h-3.5 w-3.5" />
      </button>
    </div>
  )
}

// ==================== 主页面 ====================

export default function RoundTablePage() {
  const { bookId } = useParams<{ bookId: string }>()
  const navigate = useNavigate()
  const id = Number(bookId)

  const [phase, setPhase] = useState<Phase>('loading')
  const [availableRoles, setAvailableRoles] = useState<RoundTableRole[]>([])
  const [selectedKeys, setSelectedKeys] = useState<Set<string>>(new Set())
  const [messages, setMessages] = useState<DisplayMessage[]>([])
  const [speakingKey, setSpeakingKey] = useState<string | null>(null)
  const [ttsEnabled, setTtsEnabled] = useState(false)
  const [speakingMsgId, setSpeakingMsgId] = useState<string | null>(null)
  const [currentRound, setCurrentRound] = useState(0)
  const [grabbingAnimation, setGrabbingAnimation] = useState<string | null>(null)
  const [pastSessions, setPastSessions] = useState<RoundTableSession[]>([])
  const [bookInfo, setBookInfo] = useState<Book | null>(null)
  const [showStats, setShowStats] = useState(false)
  const retryCountRef = useRef(0)

  const sessionIdRef = useRef<string | null>(null)
  const activeRolesRef = useRef<RoundTableRole[]>([])
  const messagesRef = useRef<DisplayMessage[]>([])

  const abortRef = useRef<AbortController | null>(null)
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const currentMsgIdRef = useRef<string | null>(null)
  const currentContentRef = useRef<string>('')
  const synthRef = useRef<SpeechSynthesis | null>(null)
  const userScrollingRef = useRef(false)
  const scrollContainerRef = useRef<HTMLDivElement>(null)
  const discussionLoopRef = useRef<boolean>(false)
  // 记录 TTS 打开时当前发言人，只有该发言人开始的内容才朗读
  const ttsEnabledSpeakerRef = useRef<string | null>(null)
  // 缓存中文语音列表，避免每次 getVoices()
  const zhVoicesRef = useRef<SpeechSynthesisVoice[]>([])
  // 角色到语音的映射缓存
  const roleVoiceMapRef = useRef<Map<string, SpeechSynthesisVoice>>(new Map())
  // ttsEnabled 的 ref 版本，供 SSE 闭包读取最新值
  const ttsEnabledRef = useRef(false)
  // 追踪最后一次完整朗读的消息在 messages 数组中的下标
  // -1 = 未读过 / 已重置；用于暂停后继续播放的定位
  const ttsLastReadIndexRef = useRef<number>(-1)

  const [, setSessionId] = useState<string | null>(null)
  const [, setActiveRoles] = useState<RoundTableRole[]>([])

  useEffect(() => { messagesRef.current = messages }, [messages])

  useEffect(() => {
    if (typeof window !== 'undefined' && window.speechSynthesis) {
      synthRef.current = window.speechSynthesis
      // 预加载语音列表（Chrome/Safari 中 voices 是异步加载的）
      const loadVoices = () => {
        const voices = window.speechSynthesis.getVoices()
        // 宽松匹配：zh、zh-CN、zh-Hans-CN、zh-Hans、zh-TW、zh-Hant-TW、zh-Hant 等
        const zhVoices = voices.filter(v => {
          const lang = (v.lang || '').toLowerCase()
          return lang.startsWith('zh') || lang.startsWith('cmn')
        })
        if (zhVoices.length > 0) zhVoicesRef.current = zhVoices
      }
      loadVoices()
      // Safari 可能不触发 onvoiceschanged，双重保险：延迟再加载一次
      window.setTimeout(loadVoices, 500)
      window.speechSynthesis.onvoiceschanged = loadVoices
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

  // 加载推荐角色 + 历史会话 + 图书信息
  useEffect(() => {
    if (!id) return
    loadRecommendedRoles()
    getRoundTableSessions(id).then((res: unknown) => {
      const data = (res as { data?: RoundTableSession[] })?.data ?? res as RoundTableSession[]
      if (Array.isArray(data)) setPastSessions(data)
    })
    getBook(id).then((res: unknown) => {
      const data = (res as { data?: Book })?.data ?? res as Book
      if (data) setBookInfo(data as Book)
    })
  }, [id])

  const loadRecommendedRoles = useCallback(async (refresh?: boolean) => {
    setPhase('loading')
    try {
      const res = await getRoundTableRoles(id, refresh)
      const data = (res as { data?: RoundTableRole[] })?.data ?? res as RoundTableRole[]
      if (Array.isArray(data) && data.length > 0) {
        setAvailableRoles(data)
        // 根据后端返回的 selected 字段初始化勾选
        const initialSelected = new Set<string>()
        data.forEach(r => {
          if (r.selected) initialSelected.add(r.key)
        })
        // 确保 HOST 始终选中
        if (!initialSelected.has('HOST')) initialSelected.add('HOST')
        setSelectedKeys(initialSelected)
        setPhase('select')
      }
    } catch {
      setPhase('select')
      toast.error('获取角色列表失败')
    }
  }, [id])

  useEffect(() => {
    if (!userScrollingRef.current && messages.length > 0) {
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
  }, [messages])

  const handleUserScroll = () => {
    const container = scrollContainerRef.current
    if (!container) return
    const { scrollTop, scrollHeight, clientHeight } = container
    userScrollingRef.current = scrollHeight - scrollTop - clientHeight > 50
  }

  const toggleRole = useCallback((key: string) => {
    if (key === 'HOST') return
    setSelectedKeys(prev => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }, [])

  // TTS — 消息完成后入队，按顺序逐条朗读
  // Chrome 的 SpeechSynthesis 对单段文本有 ~200 字的静默截断，
  // 需要按句子边界分块，确保跨浏览器完整朗读。
  const TTS_MAX_CHUNK = 180
  const splitLongText = (text: string, maxLen = TTS_MAX_CHUNK): string[] => {
    if (text.length <= maxLen) return [text]
    const chunks: string[] = []
    let start = 0
    while (start < text.length) {
      if (start + maxLen >= text.length) { chunks.push(text.slice(start)); break }
      let end = start + maxLen
      let found = false
      // 优先在句子结束符处断开：。！？! ? .
      for (let i = end; i > start; i--) {
        if (/[。！？!?.]/.test(text[i - 1])) { chunks.push(text.slice(start, i)); start = i; found = true; break }
      }
      if (found) continue
      // 其次在从句分隔符处断开：，；, ; 、 \n
      for (let i = end; i > start; i--) {
        if (/[，；,;、\n]/.test(text[i - 1])) { chunks.push(text.slice(start, i)); start = i; found = true; break }
      }
      if (!found) { chunks.push(text.slice(start, end)); start = end }
    }
    return chunks
  }

  const ttsQueueRef = useRef<{ text: string; roleKey: string; msgIndex: number }[]>([])
  const ttsSpeakingRef = useRef(false)
  const processTtsQueueRef = useRef<() => void>(() => {})

  const processTtsQueue = useCallback(() => {
    const synth = window.speechSynthesis
    if (!synth) return
    if (ttsSpeakingRef.current || ttsQueueRef.current.length === 0) return

    // ====== 跨浏览器兼容：speak 前的清理 ======
    // 1. 恢复被挂起的 synth（Chrome/Firefox 在标签页后台时会 pause）
    try { synth.resume() } catch {}
    // 2. Safari: onend 已触发但 synth.speaking 可能仍为 true，
    //    需要 cancel 重置状态。注意 cancel() 可能同步触发 onend →
    //    processTtsQueue 重入，因此 cancel 必须在 pop 之前执行。
    try { if (synth.speaking) synth.cancel() } catch {}

    // cancel() 可能触发了重入调用，重新检查守卫条件
    if (ttsSpeakingRef.current || ttsQueueRef.current.length === 0) return

    const { text, roleKey, msgIndex } = ttsQueueRef.current.shift()!
    ttsSpeakingRef.current = true

    // 创建 utterance
    const utterance = new SpeechSynthesisUtterance(text)
    const config = ROLE_TTS_CONFIG[roleKey] || { pitch: 1.0, rate: 1.0 }
    // pitch 钳制到 0.5-2，避免 Safari 不接受极端值
    utterance.pitch = Math.max(0.5, Math.min(2.0, config.pitch))
    utterance.rate = config.rate
    utterance.lang = 'zh-CN'
    utterance.volume = 1.0

    // 为角色分配语音
    let zhVoices = zhVoicesRef.current
    // Safari: speak 时再次刷新 voices（有时 onvoiceschanged 不触发）
    if (zhVoices.length === 0 && window.speechSynthesis) {
      try {
        const allVoices = window.speechSynthesis.getVoices()
        zhVoices = allVoices.filter(v => {
          const lang = (v.lang || '').toLowerCase()
          return lang.startsWith('zh') || lang.startsWith('cmn')
        })
        if (zhVoices.length > 0) zhVoicesRef.current = zhVoices
      } catch {}
    }
    if (zhVoices.length > 0) {
      // 优先从缓存取，否则用 hash 分配
      let voice = roleVoiceMapRef.current.get(roleKey)
      if (!voice) {
        let hash = 0
        for (let i = 0; i < roleKey.length; i++) hash = ((hash << 5) - hash + roleKey.charCodeAt(i)) | 0
        voice = zhVoices[Math.abs(hash) % zhVoices.length]
        roleVoiceMapRef.current.set(roleKey, voice)
      }
      // 再次验证 voice 仍然有效（在当前 voices 列表中）
      const stillValid = zhVoices.some(v => v.name === voice!.name && v.lang === voice!.lang)
      if (stillValid) {
        utterance.voice = voice
        utterance.lang = voice.lang
      }
    }

    utterance.onend = () => {
      if (msgIndex >= 0) ttsLastReadIndexRef.current = Math.max(ttsLastReadIndexRef.current, msgIndex)
      ttsSpeakingRef.current = false
      processTtsQueueRef.current()
    }
    utterance.onerror = (e) => {
      // Chrome: 'canceled' / 'interrupted' 是正常的取消操作，不打印警告
      if (e?.error !== 'canceled' && e?.error !== 'interrupted') {
        console.warn('[TTS] onerror:', e?.error, 'text:', text.slice(0, 40))
      }
      // 朗读出错也推进下标，避免卡在同一条消息上
      if (msgIndex >= 0) ttsLastReadIndexRef.current = Math.max(ttsLastReadIndexRef.current, msgIndex)
      ttsSpeakingRef.current = false
      processTtsQueueRef.current()
    }
    utterance.onstart = () => {
      console.log('[TTS] speak:', roleKey, 'voice:', (utterance.voice as SpeechSynthesisVoice | null)?.name || 'default')
    }

    try {
      synth.speak(utterance)
    } catch (e) {
      console.error('[TTS] speak exception:', e)
      ttsSpeakingRef.current = false
      processTtsQueueRef.current()
    }
  }, [])
  useEffect(() => { processTtsQueueRef.current = processTtsQueue }, [processTtsQueue])

  const enqueueTts = useCallback((text: string, roleKey: string, msgIndex?: number) => {
    // 清理 markdown
    const cleanText = text.replace(/```[\s\S]*?```/g, '').replace(/`[^`]+`/g, '')
      .replace(/\*\*([^*]+)\*\*/g, '$1').replace(/\*([^*]+)\*/g, '$1')
      .replace(/^#{1,6}\s+/gm, '').replace(/\[([^\]]+)\]\([^)]+\)/g, '$1').trim()
    if (!cleanText) return
    // 跨浏览器兼容：长文本分块，防止 Chrome 静默截断
    const chunks = splitLongText(cleanText)
    const realIdx = msgIndex ?? -1
    chunks.forEach((chunk, i) => {
      // 只有最后一块携带真实下标，中间块为 -1，
      // 确保暂停在中途时续播能回到本条消息开头
      ttsQueueRef.current.push({ text: chunk, roleKey, msgIndex: i === chunks.length - 1 ? realIdx : -1 })
    })
    processTtsQueueRef.current()
  }, [])
  const enqueueTtsRef = useRef(enqueueTts)
  useEffect(() => { enqueueTtsRef.current = enqueueTts }, [enqueueTts])

  // 单角色发言
  const speakAsCharacter = useCallback(async (roleKey: string, topic?: string): Promise<{ content: string; ended: boolean; saved: boolean }> => {
    return new Promise((resolve, reject) => {
      const sid = sessionIdRef.current
      if (!sid) { reject(new Error('无会话')); return }
      const role = activeRolesRef.current.find(r => r.key === roleKey)
      if (!role) { reject(new Error(`角色 ${roleKey} 不存在`)); return }

      const msgId = `msg-${Date.now()}-${roleKey}-${Math.random().toString(36).slice(2, 6)}`
      currentMsgIdRef.current = msgId; currentContentRef.current = ''
      const roleName = ROLE_NAMES[roleKey] || role.name
      const roleColor = ROLE_COLORS[roleKey] || role.color || '#6B655C'

      setMessages(prev => [...prev, { id: msgId, roleKey, roleName, roleColor, content: '', timestamp: Date.now(), streaming: true }])
      setSpeakingKey(roleKey)

      const { abortController, onMessage, onDone, onError } = streamCharacterSpeak(id, roleKey, sid, topic)
      abortRef.current = abortController

      let accumulated = ''
      onMessage((text: string) => {
        accumulated += text; currentContentRef.current = accumulated
        setMessages(prev => prev.map(m => m.id === msgId ? { ...m, content: accumulated } : m))
      })
      onDone(() => {
        const ended = accumulated.includes('[END]')
        const cleanContent = accumulated.replace(/\[END\]/g, '').trim()
        setMessages(prev => prev.map(m => m.id === msgId ? { ...m, content: cleanContent !== accumulated ? cleanContent : m.content, streaming: false } : m))
        setSpeakingKey(null); abortRef.current = null
        // TTS：消息完成后入队朗读
        if (ttsEnabledRef.current) {
          const idx = messagesRef.current.findIndex(m => m.id === msgId)
          enqueueTtsRef.current(cleanContent, roleKey, idx >= 0 ? idx : undefined)
        }
        // SSE done 事件触发时，后端消息已保存到数据库
        resolve({ content: cleanContent, ended, saved: true })
      })
      onError((err: Error) => {
        setMessages(prev => prev.map(m => m.id === msgId ? { ...m, streaming: false, content: accumulated || '（发言失败）' } : m))
        setSpeakingKey(null); abortRef.current = null
        reject(err)
      })
    })
  }, [id])

  // 下一轮发言
  const grabMicAndSpeak = useCallback(async (_lastSpeakerKey?: string) => {
    if (!discussionLoopRef.current) return
    const sid = sessionIdRef.current
    if (!sid) return

    try {
      // 等待一小段时间确保上一条消息已保存到数据库
      // 后端在发送 done 事件前已保存消息，但数据库同步可能有微小延迟
      await new Promise(r => setTimeout(r, 300))

      const res = await getNextSpeaker(sid)
      const nextSpeakerKey = ((res as { data?: string })?.data ?? res as string) as string

      // 安全校验：如果选中的角色和上一个发言者相同，强制换一个
      if (_lastSpeakerKey && nextSpeakerKey === _lastSpeakerKey) {
        const activeKeys = activeRolesRef.current.map(r => r.key).filter(k => k !== _lastSpeakerKey)
        if (activeKeys.length > 0) {
          const fallback = activeKeys[Math.floor(Math.random() * activeKeys.length)]
          console.warn(`[圆桌派] LLM 选择了连续发言者 ${_lastSpeakerKey}，强制切换为 ${fallback}`)
          const result = await speakAsCharacter(fallback, '请直接说出你的想法，不要先总结别人的观点。')
          setCurrentRound(prev => prev + 1)
          retryCountRef.current = 0
          if (result.ended) { discussionLoopRef.current = false; setPhase('paused'); return }
          if (discussionLoopRef.current) {
            setTimeout(() => grabMicAndSpeak(fallback), 1500 + Math.random() * 1000)
          }
          return
        }
      }

      setGrabbingAnimation(nextSpeakerKey)
      await new Promise(r => setTimeout(r, 800 + Math.random() * 600))
      setGrabbingAnimation(null)

      // 所有角色都传发言指令，避免乱接话
      let topic: string | undefined
      if (nextSpeakerKey === 'HOST') {
        topic = '请引导讨论：如果讨论在重复或钻牛角尖，请果断抛出新话题；如果讨论正常，可以简短回应或向某位嘉宾提问。绝对不要以「刚才大家...」开头。'
      } else {
        topic = '请直接说出你的想法，不要先总结别人的观点，绝对不要以「刚才大家...」开头。'
      }

      const result = await speakAsCharacter(nextSpeakerKey, topic)
      setCurrentRound(prev => prev + 1)
      retryCountRef.current = 0 // 成功后重置重试计数

      if (result.ended) { discussionLoopRef.current = false; setPhase('paused'); return }

      if (discussionLoopRef.current) {
        setTimeout(() => grabMicAndSpeak(nextSpeakerKey), 1500 + Math.random() * 1000)
      }
    } catch {
      retryCountRef.current++
      if (retryCountRef.current >= 5) {
        // 连续失败5次，停止讨论
        discussionLoopRef.current = false
        setPhase('paused')
        toast.error('讨论连续出错，已自动暂停')
        return
      }
      if (discussionLoopRef.current) {
        setTimeout(() => grabMicAndSpeak(), 3000)
      }
    }
  }, [speakAsCharacter])

  // 开始讨论
  const startDiscussion = useCallback(async () => {
    if (selectedKeys.size < 4) { toast.error('请至少选择4位嘉宾（含主持人）'); return }
    const roles = availableRoles.filter(r => selectedKeys.has(r.key))
    activeRolesRef.current = roles; setActiveRoles(roles)
    setPhase('discussing'); setMessages([]); setSpeakingKey(null); setCurrentRound(0)
    userScrollingRef.current = false; discussionLoopRef.current = true
    ttsEnabledSpeakerRef.current = null; ttsLastReadIndexRef.current = -1; retryCountRef.current = 0
    // 新讨论开始，重置 TTS 状态
    ttsEnabledRef.current = false; ttsQueueRef.current = []; ttsSpeakingRef.current = false
    try { window.speechSynthesis?.cancel() } catch {}
    setTtsEnabled(false)

    try {
      const roleKeys = Array.from(selectedKeys)
      const roleConfigs = JSON.stringify(roles.map(r => ({
        key: r.key, name: r.name, grabWeight: r.grabWeight, domainRelevance: r.domainRelevance,
        challenge: r.challenge, empathy: r.empathy, opinionated: r.opinionated,
        verbosity: r.verbosity, humor: r.humor, languageStyle: r.languageStyle || '',
      })))
      const sessionRes = await createRoundTableSession(id, roleKeys, roleConfigs)
      const session = (sessionRes as { data?: RoundTableSession })?.data ?? sessionRes as RoundTableSession
      const sid = (session as RoundTableSession).sessionId
      sessionIdRef.current = sid; setSessionId(sid)

      await speakAsCharacter('HOST', '请介绍这本书并抛出第一个讨论话题')
      setCurrentRound(1)
      if (discussionLoopRef.current) { setTimeout(() => grabMicAndSpeak('HOST'), 1500 + Math.random() * 1000) }
    } catch (err) { console.error('[圆桌派] 启动失败:', err); setPhase('error') }
  }, [id, selectedKeys, availableRoles, speakAsCharacter, grabMicAndSpeak])

  // 历史回放
  const loadHistorySession = useCallback(async (session: RoundTableSession) => {
    try {
      const res = await getRoundTableMessages(session.sessionId)
      const data = (res as { data?: RoundTableMessage[] })?.data ?? res as RoundTableMessage[]
      if (!Array.isArray(data) || data.length === 0) { toast.error('该会话没有消息记录'); return }
      const roleKeysArr = session.roleKeys ? session.roleKeys.split(',') : []
      const roles = roleKeysArr.map(key => {
        const found = availableRoles.find(r => r.key === key)
        return found || { key, name: ROLE_NAMES[key] || key, title: ROLE_TITLES[key] || '', color: ROLE_COLORS[key] || '#6B655C', roleGroup: '', grabWeight: 5, verbosity: 3, opinionated: 3, challenge: 3, empathy: 3, humor: 2, domainRelevance: 0, languageStyle: '' } as RoundTableRole
      })
      activeRolesRef.current = roles; sessionIdRef.current = session.sessionId
      setActiveRoles(roles); setSessionId(session.sessionId); setPhase('paused')
      setMessages(data.map(m => ({ id: `hist-${m.id}`, roleKey: m.roleKey, roleName: m.roleName, roleColor: ROLE_COLORS[m.roleKey] || '#6B655C', content: m.content, timestamp: new Date(m.createTime).getTime() })))
      setCurrentRound(data.length); discussionLoopRef.current = false
      // 加载历史会话时重置 TTS 朗读进度，从头开始
      ttsLastReadIndexRef.current = -1
      ttsEnabledRef.current = false; ttsQueueRef.current = []; ttsSpeakingRef.current = false
      try { window.speechSynthesis?.cancel() } catch {}
      setTtsEnabled(false)
    } catch { toast.error('加载历史记录失败') }
  }, [availableRoles])

  const handleDeleteSession = useCallback(async (sid: string) => {
    try { await deleteRoundTableSession(sid); setPastSessions(prev => prev.filter(s => s.sessionId !== sid)); toast.success('已删除') }
    catch { toast.error('删除失败') }
  }, [])

  const pauseDiscussion = useCallback(() => {
    discussionLoopRef.current = false; abortRef.current?.abort()
    setPhase('paused'); setSpeakingKey(null); setMessages(prev => prev.map(m => ({ ...m, streaming: false })))
  }, [])

  const stopDiscussion = useCallback(() => {
    discussionLoopRef.current = false; abortRef.current?.abort(); abortRef.current = null
    synthRef.current?.cancel(); ttsEnabledSpeakerRef.current = null
    setSpeakingMsgId(null); setPhase('paused'); setSpeakingKey(null)
    setMessages(prev => prev.map(m => ({ ...m, streaming: false })))
  }, [])

  // 离开页面时清理讨论状态
  useEffect(() => {
    return () => {
      stopDiscussion()
    }
  }, [stopDiscussion])

  const resumeDiscussion = useCallback(() => {
    discussionLoopRef.current = true; setPhase('discussing')
    const lastMsg = messagesRef.current[messagesRef.current.length - 1]
    setTimeout(() => grabMicAndSpeak(lastMsg?.roleKey), 1000)
  }, [grabMicAndSpeak])

  const restart = useCallback(() => {
    discussionLoopRef.current = false; abortRef.current?.abort(); abortRef.current = null
    synthRef.current?.cancel(); ttsEnabledSpeakerRef.current = null; ttsLastReadIndexRef.current = -1
    ttsEnabledRef.current = false; ttsQueueRef.current = []; ttsSpeakingRef.current = false
    setTtsEnabled(false)
    sessionIdRef.current = null; activeRolesRef.current = []; setSpeakingMsgId(null)
    setPhase('select'); setMessages([]); setSpeakingKey(null); setActiveRoles([]); setSessionId(null); setCurrentRound(0)
    getRoundTableSessions(id).then((res: unknown) => { const d = (res as { data?: RoundTableSession[] })?.data ?? res as RoundTableSession[]; if (Array.isArray(d)) setPastSessions(d) }).catch(() => {})
  }, [id])

  const handleToggleSpeak = useCallback((msgId: string, content: string, roleKey: string) => {
    const synth = synthRef.current; if (!synth) return
    if (speakingMsgId === msgId) { synth.cancel(); setSpeakingMsgId(null); return }
    synth.cancel(); setSpeakingMsgId(msgId)
    const cleanText = content.replace(/```[\s\S]*?```/g, '').replace(/`[^`]+`/g, '')
      .replace(/\*\*([^*]+)\*\*/g, '$1').replace(/\*([^*]+)\*/g, '$1')
      .replace(/^#{1,6}\s+/gm, '').replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
      .replace(/^[-*]\s+/gm, '').replace(/^>\s+/gm, '').trim()
    if (!cleanText) return
    // 跨浏览器兼容：长文本分块，防止 Chrome 静默截断
    const chunks = splitLongText(cleanText)
    const config = ROLE_TTS_CONFIG[roleKey] || { pitch: 1.0, rate: 1.0 }
    const voices = synth.getVoices()
    const zhVoice = voices.find(v => v.lang.startsWith('zh'))

    const speakChunk = (idx: number) => {
      if (idx >= chunks.length) { setSpeakingMsgId(null); return }
      const utterance = new SpeechSynthesisUtterance(chunks[idx])
      utterance.pitch = Math.max(0.5, Math.min(2.0, config.pitch))
      utterance.rate = config.rate
      utterance.lang = 'zh-CN'
      if (zhVoice) utterance.voice = zhVoice
      utterance.onend = () => speakChunk(idx + 1)
      // onerror 不递归，自然终止分块链（用户取消 / 切换时会触发）
      utterance.onerror = () => setSpeakingMsgId(null)
      try { synth.speak(utterance) } catch { setSpeakingMsgId(null) }
    }

    try { synth.resume() } catch {}
    speakChunk(0)
  }, [speakingMsgId])

  // ==================== 渲染：角色选择 ====================
  const renderSelectPhase = () => (
    <div className="flex flex-1 flex-col items-center px-4 py-6 overflow-y-auto">
      {/* 顶部装饰 */}
      <div className="mb-5 text-center">
        <div className="inline-flex h-14 w-14 items-center justify-center rounded-2xl bg-gradient-to-br from-brand-100 to-brand-200 mb-3 shadow-sm">
          <Users className="h-7 w-7 text-brand-500" />
        </div>
        <h2 className="text-lg font-bold text-foreground">选择讨论嘉宾</h2>
        <p className="mt-1 text-xs text-muted-foreground">
          AI 根据《{bookInfo?.title || '本书'}》推荐了 {availableRoles.length} 位嘉宾，已为你勾选 {selectedKeys.size - 1} 位
        </p>
      </div>

      {/* 书籍信息卡片 */}
      {bookInfo && (
        <div className="w-full max-w-2xl mb-5 flex items-center gap-3 rounded-2xl border border-border/40 bg-card p-3 shadow-sm">
          {bookInfo.coverUrl ? (
            <img src={bookInfo.coverUrl} alt={bookInfo.title} className="h-14 w-10 rounded-md object-cover shadow-sm" />
          ) : (
            <div className="flex h-14 w-10 items-center justify-center rounded-md bg-muted">
              <BookOpen className="h-5 w-5 text-muted-foreground" />
            </div>
          )}
          <div className="min-w-0 flex-1">
            <p className="text-sm font-semibold truncate">{bookInfo.title}</p>
            <p className="text-[11px] text-muted-foreground">{bookInfo.author}</p>
          </div>
          <Sparkles className="h-4 w-4 text-brand-400 shrink-0" />
        </div>
      )}

      {/* 角色网格 */}
      <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-2.5 w-full max-w-2xl">
        {availableRoles.map(role => (
          <RoleCard
            key={role.key}
            role={role}
            isSelected={selectedKeys.has(role.key)}
            isHost={role.key === 'HOST'}
            onToggle={() => toggleRole(role.key)}
          />
        ))}
      </div>

      {/* 操作按钮 */}
      <div className="mt-6 flex items-center gap-3">
        <button
          onClick={() => loadRecommendedRoles(true)}
          className="flex items-center gap-1.5 rounded-2xl border border-border/40 bg-card px-4 py-3 text-sm font-medium text-muted-foreground hover:text-foreground hover:border-border/60 transition-all duration-200 active:scale-[0.97]"
          title="重新邀请一组嘉宾"
        >
          <RefreshCw className="h-4 w-4" />
          重新邀请
        </button>
        <button
          onClick={startDiscussion}
          disabled={selectedKeys.size < 4}
          className="flex items-center gap-2 rounded-2xl bg-gradient-to-r from-brand-400 to-brand-500 px-8 py-3 text-sm font-semibold text-white shadow-lg shadow-brand-400/20 transition-all duration-200 hover:shadow-xl hover:shadow-brand-400/25 active:scale-[0.97] disabled:opacity-50 disabled:shadow-none"
        >
          <Play className="h-4 w-4" />
          开始讨论 ({selectedKeys.size}人)
        </button>
      </div>

      {/* 历史会话 */}
      {pastSessions.length > 0 && (
        <div className="mt-8 w-full max-w-2xl">
          <h3 className="mb-3 flex items-center gap-2 text-sm font-bold text-muted-foreground">
            <History className="h-4 w-4" />
            历史讨论
          </h3>
          <div className="space-y-2">
            {pastSessions.map(session => (
              <SessionCard
                key={session.sessionId}
                session={session}
                onLoad={() => loadHistorySession(session)}
                onDelete={() => handleDeleteSession(session.sessionId)}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  )

  // ==================== 计算发言统计 ====================
  const speakCounts = messages.reduce<Record<string, number>>((acc, msg) => {
    acc[msg.roleKey] = (acc[msg.roleKey] || 0) + 1
    return acc
  }, {})

  // ==================== 渲染：讨论中界面 ====================
  const renderDiscussion = () => {
    const roles = activeRolesRef.current.length > 0
      ? activeRolesRef.current
      : availableRoles.filter(r => selectedKeys.has(r.key))

    return (
      <div className="flex flex-1 flex-col overflow-hidden relative">
        {/* 发言统计面板 */}
        {showStats && (
          <SpeakStatsPanel
            roles={roles}
            messages={messages}
            onClose={() => setShowStats(false)}
          />
        )}

        {/* 横向角色条 */}
        <RoleBar
          roles={roles}
          speakCounts={speakCounts}
          speakingKey={speakingKey}
          grabbingKey={grabbingAnimation}
        />

        {/* 消息流 */}
        <div ref={scrollContainerRef} onScroll={handleUserScroll} className="flex-1 overflow-y-auto overscroll-y-contain">
          {messages.length === 0 && (
            <div className="flex flex-1 flex-col items-center justify-center py-12 text-muted-foreground">
              <div className="relative mb-4">
                <div className="h-16 w-16 rounded-full border-2 border-dashed border-brand-200 flex items-center justify-center">
                  <MessageSquare className="h-7 w-7 text-brand-300" />
                </div>
                <div className="absolute -bottom-1 -right-1 h-5 w-5 rounded-full bg-brand-400 flex items-center justify-center">
                  <Loader2 className="h-3 w-3 animate-spin text-white" />
                </div>
              </div>
              <p className="text-sm font-medium">主持人准备开场中...</p>
              <p className="text-[11px] text-muted-foreground/60 mt-1">请稍候，AI 正在组织讨论</p>
            </div>
          )}
          <div className="space-y-1 p-4 max-w-3xl mx-auto">
            {messages.map(msg => (
              <MessageBubble
                key={msg.id}
                msg={msg}
                isSpeaking={speakingMsgId === msg.id}
                onToggleSpeak={() => handleToggleSpeak(msg.id, msg.content, msg.roleKey)}
              />
            ))}
            <div ref={messagesEndRef} />
          </div>
        </div>

        {/* 底部操作栏 */}
        <div
          className="shrink-0 border-t border-border/20 bg-background/95 backdrop-blur-xl px-4 py-2.5"
          style={{ paddingBottom: 'calc(env(safe-area-inset-bottom, 0px) + 0.75rem)' }}
        >
          <div className="flex items-center gap-2 max-w-3xl mx-auto">
            <button
              onClick={() => {
                const currentPhase = phase // 捕获当前阶段（讨论中 / 已终止）
                if (!ttsEnabled) {
                  // ====== 打开语音 ======
                  ttsEnabledRef.current = true
                  // Safari: 在用户交互同步栈内刷新 voices 并立即 enqueue
                  // 第一次 speak 发生在本次点击内即可解锁权限
                  try {
                    const allVoices = window.speechSynthesis.getVoices()
                    const zhVs = allVoices.filter(v => {
                      const lang = (v.lang || '').toLowerCase()
                      return lang.startsWith('zh') || lang.startsWith('cmn')
                    })
                    if (zhVs.length > 0) zhVoicesRef.current = zhVs
                  } catch {}

                  const msgs = messagesRef.current
                  if (currentPhase === 'discussing') {
                    // 1.1 讨论进行中：从最后一条加载完成的发言开始，按顺序往后读
                    let startIdx = -1
                    for (let i = msgs.length - 1; i >= 0; i--) {
                      if (!msgs[i].streaming && msgs[i].content) { startIdx = i; break }
                    }
                    if (startIdx >= 0) {
                      // 重置下标：即将从头（最后一条已完成消息）开始读
                      ttsLastReadIndexRef.current = startIdx - 1
                      for (let i = startIdx; i < msgs.length; i++) {
                        const m = msgs[i]
                        if (!m.streaming && m.content) {
                          enqueueTtsRef.current(m.content, m.roleKey, i)
                        }
                      }
                    }
                  } else {
                    // 2.1 讨论终止：有下标则续播，否则从头开始
                    const resumeIdx = ttsLastReadIndexRef.current >= 0
                      ? ttsLastReadIndexRef.current + 1
                      : 0
                    for (let i = resumeIdx; i < msgs.length; i++) {
                      const m = msgs[i]
                      if (m.content) {
                        enqueueTtsRef.current(m.content, m.roleKey, i)
                      }
                    }
                  }
                } else {
                  // ====== 关闭语音 ======
                  ttsEnabledRef.current = false
                  ttsQueueRef.current = []; ttsSpeakingRef.current = false
                  try { window.speechSynthesis?.cancel() } catch {}
                  if (currentPhase === 'discussing') {
                    // 1.2 讨论进行中关闭：清空朗读下标，下次打开重新从最后一条开始
                    ttsLastReadIndexRef.current = -1
                  }
                  // 2.2 讨论终止关闭：保留下标，下次打开继续播放（暂停逻辑）
                }
                setTtsEnabled(!ttsEnabled)
              }}
              className={`flex items-center gap-1.5 rounded-xl px-3 py-2 text-[11px] font-medium transition-all duration-200 ${
                ttsEnabled
                  ? 'bg-brand-100 text-brand-500 border border-brand-200'
                  : 'bg-muted text-muted-foreground hover:text-foreground'
              }`}
            >
              <Volume2 className="h-3 w-3" />
              {ttsEnabled ? '朗读中' : '语音关'}
            </button>

            {phase === 'discussing' && (
              <button
                onClick={pauseDiscussion}
                className="flex items-center gap-1.5 rounded-xl bg-amber-500/10 px-3 py-2 text-[11px] font-medium text-amber-600 hover:bg-amber-500/20 transition-colors"
              >
                <Pause className="h-3 w-3" />
                暂停
              </button>
            )}

            {phase === 'paused' && sessionIdRef.current && messages.some(m => m.streaming === undefined) && (
              <button
                onClick={resumeDiscussion}
                className="flex items-center gap-1.5 rounded-xl bg-gradient-to-r from-brand-400 to-brand-500 px-3 py-2 text-[11px] font-semibold text-white shadow-md shadow-brand-400/20 active:scale-[0.97] transition-transform"
              >
                <Play className="h-3 w-3" />
                继续
              </button>
            )}

            {phase === 'error' && (
              <button
                onClick={restart}
                className="flex items-center gap-1.5 rounded-xl bg-gradient-to-r from-brand-400 to-brand-500 px-3 py-2 text-[11px] font-semibold text-white shadow-md shadow-brand-400/20 active:scale-[0.97] transition-transform"
              >
                重试
              </button>
            )}

            {phase === 'discussing' && (
              <button
                onClick={stopDiscussion}
                className="flex items-center gap-1.5 rounded-xl bg-red-500/10 px-3 py-2 text-[11px] font-medium text-red-500 hover:bg-red-500/20 transition-colors"
              >
                <Square className="h-3 w-3" />
                结束
              </button>
            )}

            <div className="flex-1" />

            {/* 统计按钮 */}
            {messages.length > 0 && (
              <button
                onClick={() => setShowStats(!showStats)}
                className={`flex items-center gap-1 rounded-xl px-2.5 py-2 text-[11px] transition-colors ${
                  showStats ? 'bg-primary/10 text-primary' : 'text-muted-foreground hover:text-foreground'
                }`}
              >
                <BarChart3 className="h-3 w-3" />
                统计
              </button>
            )}

            <span className="text-[9px] text-muted-foreground/60">
              {roles.length}人 · 第{currentRound}轮
            </span>
          </div>
        </div>
      </div>
    )
  }

  // ==================== 主布局 ====================
  return (
    <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col overflow-hidden bg-background page-enter">
      {/* 顶部导航 */}
      <header className="shrink-0 flex items-center gap-3 border-b border-border/30 bg-background/80 px-4 py-2.5 backdrop-blur-xl z-20">
        <button
          onClick={() => navigate(-1)}
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl hover:bg-muted transition-colors"
        >
          <ArrowLeft className="h-4 w-4" />
        </button>
        <div className="min-w-0 flex-1">
          <h1 className="text-sm font-bold text-foreground">圆桌派</h1>
          <p className="text-[10px] text-muted-foreground truncate">
            {phase === 'loading' ? '邀请嘉宾中...' :
             phase === 'select' ? '选择嘉宾，开启讨论' :
             phase === 'discussing' ? '讨论进行中' :
             phase === 'paused' ? '讨论已暂停' :
             phase === 'error' ? '讨论出错' : ''}
          </p>
        </div>
        {phase !== 'loading' && phase !== 'select' && (
          <button
            onClick={restart}
            className="flex items-center gap-1 rounded-lg bg-muted px-2.5 py-1.5 text-[10px] text-muted-foreground hover:text-foreground transition-colors"
          >
            重新选人
          </button>
        )}
      </header>

      {/* 内容区 */}
      {phase === 'loading' ? <LoadingPhase /> :
       phase === 'select' ? renderSelectPhase() :
       renderDiscussion()}
    </div>
  )
}
