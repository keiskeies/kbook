import { useState, useEffect, useRef, useCallback, useMemo } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  ArrowLeft, Volume2, Square, Loader2, BarChart3, FileText, Play,
} from 'lucide-react'
import { Sheet, SheetContent } from '@/components/ui/sheet'
import ScorePanel from '@/components/debate/ScorePanel'
import ReportPanel from '@/components/debate/ReportPanel'
import {
  getDebateMessages, getNextDebateSpeaker, advanceDebateRound,
  getDebateSession,
  streamDebateOpeningSpeech, streamDebateCrossExamSpeech, streamDebateRebuttalSpeech,
  streamDebateFreeSpeech, streamDebateClosingSpeech, streamDebateHostSpeech,
  getDebateScores, getDebateReport, triggerDebateReport,
} from '@/api/debate'
import { getBook } from '@/api/book'
import type { DebateScore, DebateReport, DebateMessage } from '@/types/debate'
import {
  DEBATE_ROLE_COLORS, DEBATE_ROLE_NAMES, DEBATE_ROLE_ICONS,
  DEBATE_PERSONALITY_NAMES,
  DEBATE_TTS_CONFIG, getPersonalityTitle,
} from '@/types/debate'
import { toast } from 'sonner'
import { useIsMobile } from '@/hooks/use-mobile'

type Phase = 'loading' | 'OPENING' | 'CROSS_EXAM' | 'REBUTTAL' | 'FREE' | 'CLOSING' | 'completed' | 'error'

interface DisplayMessage {
  id: string
  roleKey: string
  roleName: string
  personalityTitle?: string
  side: string
  positionKey?: string
  content: string
  timestamp: number
  roundType?: string
  roundNumber?: number
  streaming?: boolean
}

function hexToRgba(hex: string, alpha: number) {
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const c = parseInt(hex.slice(5, 7), 16)
  return `rgba(${r}, ${g}, ${c}, ${alpha})`
}

const ROUND_SEQUENCE = ['OPENING', 'CROSS_EXAM', 'REBUTTAL', 'FREE', 'CLOSING'] as const
const ROUND_LABELS: Record<string, string> = {
  OPENING: '开篇立论',
  CROSS_EXAM: '交叉质询',
  REBUTTAL: '驳论',
  FREE: '自由辩论',
  CLOSING: '总结陈词',
}

/** 开篇立论发言顺序 — 仅一辩立论 */
const OPENING_ORDER = ['HOST', 'PRO_1', 'CON_1']

/** 交叉质询顺序 — 二辩质询对方一辩，各2轮问答（Q→A→Q→A） */
interface CrossExamTurn { roleKey: string; examRole: 'QUESTIONER' | 'ANSWERER'; defenderKey: string }
const CROSS_EXAM_ORDER: CrossExamTurn[] = [
  { roleKey: 'PRO_2', examRole: 'QUESTIONER', defenderKey: 'CON_1' },
  { roleKey: 'CON_1', examRole: 'ANSWERER',   defenderKey: 'CON_1' },
  { roleKey: 'PRO_2', examRole: 'QUESTIONER', defenderKey: 'CON_1' },
  { roleKey: 'CON_1', examRole: 'ANSWERER',   defenderKey: 'CON_1' },
  { roleKey: 'CON_2', examRole: 'QUESTIONER', defenderKey: 'PRO_1' },
  { roleKey: 'PRO_1', examRole: 'ANSWERER',   defenderKey: 'PRO_1' },
  { roleKey: 'CON_2', examRole: 'QUESTIONER', defenderKey: 'PRO_1' },
  { roleKey: 'PRO_1', examRole: 'ANSWERER',   defenderKey: 'PRO_1' },
]

/** 驳论发言顺序 — 二辩 */
const REBUTTAL_ORDER = ['PRO_2', 'CON_2']

/** 总结陈词发言顺序 — 反方先，正方后 */
const CLOSING_ORDER = ['CON_4', 'PRO_4']

/** 自由辩论最大发言轮次 */
const MAX_FREE_EXCHANGES = 12



function RoleBar({ speakingKey, allRoleKeys, personalityTitles }: {
  speakCounts: Record<string, number>
  speakingKey: string | null
  allRoleKeys: string[]
  personalityTitles?: Record<string, string>
}) {
  const getTitle = (key: string) => {
    if (key === 'HOST') return ''
    return personalityTitles?.[key] || ''
  }
  const scrollRef = useRef<HTMLDivElement>(null)
  const snapTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const springRaf = useRef<number | null>(null)
  const isSpringing = useRef(false)

  /** 排列 反4-反1 → 主持人 → 正1-正4 */
  const proKeys = [...allRoleKeys.filter(k => k.startsWith('PRO'))].reverse() // PRO_4 → PRO_1
  const conKeys = allRoleKeys.filter(k => k.startsWith('CON'))               // CON_1 → CON_4
  const hostKeys = allRoleKeys.filter(k => k === 'HOST')

  /** 取消正在进行的弹簧动画 */
  const cancelSpring = useCallback(() => {
    isSpringing.current = false
    if (springRaf.current !== null) {
      cancelAnimationFrame(springRaf.current)
      springRaf.current = null
    }
  }, [])

  /** 弹簧物理回弹到中间 */
  const snapToCenter = useCallback(() => {
    cancelSpring()
    const el = scrollRef.current
    if (!el) return
    const target = (el.scrollWidth - el.clientWidth) / 2
    // 弹簧参数：stiffness 刚度, damping 阻尼
    const k = 0.15
    const damp = 0.85
    const threshold = 0.5
    let v = 0
    isSpringing.current = true

    function step() {
      const currentEl = scrollRef.current
      if (!currentEl) { springRaf.current = null; isSpringing.current = false; return }
      const x = currentEl.scrollLeft - target
      const force = -k * x
      v = v * damp + force
      currentEl.scrollLeft += v
      if (Math.abs(x) > threshold || Math.abs(v) > 0.1) {
        springRaf.current = requestAnimationFrame(step)
      } else {
        currentEl.scrollLeft = target
        springRaf.current = null
        isSpringing.current = false
      }
    }
    springRaf.current = requestAnimationFrame(step)
  }, [cancelSpring])

  const scheduleSnap = useCallback(() => {
    if (isSpringing.current) return  // 弹簧动画引起的 scroll 事件，不打断
    cancelSpring()
    if (snapTimer.current) clearTimeout(snapTimer.current)
    snapTimer.current = setTimeout(snapToCenter, 1000)
  }, [cancelSpring, snapToCenter])

  useEffect(() => {
    return () => {
      cancelSpring()
      if (snapTimer.current) clearTimeout(snapTimer.current)
    }
  }, [cancelSpring])

  /** 挂载时回到中间 */
  useEffect(() => {
    const raf = requestAnimationFrame(() => snapToCenter())
    return () => cancelAnimationFrame(raf)
  }, [snapToCenter])

  return (
    <div className="shrink-0 border-b border-border/20 bg-background/80 backdrop-blur-xl px-3 py-2">
      <div
        ref={scrollRef}
        onScroll={scheduleSnap}
        onTouchStart={cancelSpring}
        onTouchEnd={scheduleSnap}
        onMouseDown={cancelSpring}
        onMouseUp={scheduleSnap}
        className="flex items-center gap-1.5 overflow-x-auto scrollbar-hide cursor-grab active:cursor-grabbing md:justify-center"
      >
        {/* 正方（反向：4→1） */}
        {proKeys.map(key => {
          const color = DEBATE_ROLE_COLORS[key] || '#3B82F6'
          const isActive = speakingKey === key
          return (
            <div
              key={key}
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
                {DEBATE_ROLE_ICONS[key] || '👤'}
              </div>
              <div className="flex flex-col">
                <span
                  className="text-xs font-semibold leading-tight"
                  style={{ color: isActive ? color : undefined }}
                >
                  {DEBATE_ROLE_NAMES[key] || key}
                </span>
                {getTitle(key) && (
                  <span className="text-[8px] text-muted-foreground/50 leading-tight">{getTitle(key)}</span>
                )}
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
        {/* 主持人 */}
        {hostKeys.map(key => {
          const color = DEBATE_ROLE_COLORS[key] || '#D4A843'
          const isActive = speakingKey === key
          return (
            <div
              key={key}
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
                {DEBATE_ROLE_ICONS[key] || '🎙️'}
              </div>
              <div className="flex flex-col">
                <span
                  className="text-xs font-semibold leading-tight"
                  style={{ color: isActive ? color : undefined }}
                >
                  {DEBATE_ROLE_NAMES[key] || key}
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
        {/* 反方 */}
        {conKeys.map(key => {
          const color = DEBATE_ROLE_COLORS[key] || '#EF4444'
          const isActive = speakingKey === key
          return (
            <div
              key={key}
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
                {DEBATE_ROLE_ICONS[key] || '👤'}
              </div>
              <div className="flex flex-col">
                <span
                  className="text-xs font-semibold leading-tight"
                  style={{ color: isActive ? color : undefined }}
                >
                  {DEBATE_ROLE_NAMES[key] || key}
                </span>
                {getTitle(key) && (
                  <span className="text-[8px] text-muted-foreground/50 leading-tight">{getTitle(key)}</span>
                )}
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

export default function DebateSessionPage() {
  const { bookId, sessionId } = useParams<{ bookId: string; sessionId: string }>()
  const navigate = useNavigate()
  const bookIdNum = Number(bookId)
  const isMobile = useIsMobile()

  const [phase, setPhase] = useState<Phase>('loading')
  const [messages, setMessages] = useState<DisplayMessage[]>([])
  const [speakingKey, setSpeakingKey] = useState<string | null>(null)
  const [ttsEnabled, setTtsEnabled] = useState(false)
  const [, setSpeakingMsgId] = useState<string | null>(null)
  const [currentRound, setCurrentRound] = useState(1)
  const [currentPhase, setCurrentPhase] = useState<'OPENING' | 'CROSS_EXAM' | 'REBUTTAL' | 'FREE' | 'CLOSING'>('OPENING')
  const [scores, setScores] = useState<DebateScore[]>([])
  const [report, setReport] = useState<DebateReport | null>(null)
  const [reportGenerating, setReportGenerating] = useState(false)
  const [showScorePanel, setShowScorePanel] = useState(false)
  const [showReportPanel, setShowReportPanel] = useState(false)
  const [bookTitle, setBookTitle] = useState<string>('')
  const [sessionStatus, setSessionStatus] = useState<string>('ACTIVE')
  const abortRef = useRef<AbortController | null>(null)
  const reportPollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const handleAdvanceRoundRef = useRef<() => void>(() => {})
  const currentRoundRef = useRef(1)
  const currentPhaseRef = useRef<string>('OPENING')
  const allRoleKeys = useRef(['HOST', 'PRO_1', 'PRO_2', 'PRO_3', 'PRO_4', 'CON_1', 'CON_2', 'CON_3', 'CON_4'])
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const currentMsgIdRef = useRef<string | null>(null)
  const currentContentRef = useRef<string>('')
  const scrollContainerRef = useRef<HTMLDivElement>(null)
  const discussionLoopRef = useRef<boolean>(false)
  /** 防止 500ms 过渡间隙中重复点击「继续」 */
  const isChainActiveRef = useRef(false)
  /** 用户手动滚动离开底部时暂停自动滚动 */
  const userScrolledAwayRef = useRef(false)
  const lastScrollTopRef = useRef(0)

  // TTS ref
  const ttsRef = useRef<SpeechSynthesisUtterance | null>(null)

  // 发言次数统计
  const speakCounts = messages.reduce<Record<string, number>>((acc, m) => {
    acc[m.roleKey] = (acc[m.roleKey] || 0) + 1
    return acc
  }, {})

  // 加载图书信息
  useEffect(() => {
    if (!bookIdNum) return
    getBook(bookIdNum).then(book => {
      setBookTitle(book.title)
    }).catch(() => {})
  }, [bookIdNum])

  // 智能自动滚动：用户在底部时跟随新内容，手动拖走则暂停，拖回底部则恢复
  const isNearBottom = useCallback(() => {
    const el = scrollContainerRef.current
    if (!el) return true
    // PC 分屏模式：滚动容器是各列，追踪最长的列
    if (!isMobile) {
      const columns = el.querySelectorAll('.overflow-y-auto')
      let maxDist = 0
      columns.forEach(col => {
        const dist = col.scrollHeight - col.scrollTop - col.clientHeight
        if (dist > maxDist) maxDist = dist
      })
      return maxDist < 80
    }
    return el.scrollHeight - el.scrollTop - el.clientHeight < 80
  }, [isMobile])

  const handleScroll = useCallback(() => {
    const el = scrollContainerRef.current
    if (!el) return
    // 用户手动滚动：向上滚动时暂停自动跟随，拖到底部恢复
    if (isNearBottom()) {
      userScrolledAwayRef.current = false
    } else {
      userScrolledAwayRef.current = true
    }
    lastScrollTopRef.current = el.scrollTop
  }, [isNearBottom])

  // 离开页面时清理所有辩论定时器和 SSE 连接
  useEffect(() => {
    return () => {
      isChainActiveRef.current = false
      discussionLoopRef.current = false
      if (abortRef.current) {
        abortRef.current.abort()
        abortRef.current = null
      }
      if (reportPollRef.current) {
        clearInterval(reportPollRef.current)
        reportPollRef.current = null
      }
    }
  }, [])

  // 新内容到达时自动滚动到底部（仅当用户未手动离开时）
  useEffect(() => {
    if (userScrolledAwayRef.current) return

    if (isMobile) {
      // 移动端始终滚动到最新消息
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
    } else {
      if (!isNearBottom()) return
      // PC 分屏：各列独立滚动到最底部
      const columns = scrollContainerRef.current?.querySelectorAll('.overflow-y-auto')
      columns?.forEach(col => {
        col.scrollTop = col.scrollHeight
      })
    }
  }, [messages, isMobile, isNearBottom])

  // 初始化：加载已保存消息 + 会话状态
  useEffect(() => {
    if (!sessionId) return
    setPhase('loading')

    Promise.all([
      getDebateMessages(sessionId),
      getDebateScores(sessionId).catch(() => [] as DebateScore[]),
      getDebateSession(sessionId).catch(() => null),
    ])
      .then(([msgs, scoreData, session]) => {
        setScores(scoreData)

        if (session) {
          setSessionStatus(session.status)
          setCurrentRound(session.currentRound)
          setCurrentPhase(session.currentPhase as 'OPENING' | 'CROSS_EXAM' | 'REBUTTAL' | 'FREE' | 'CLOSING')
        }

        if (msgs.length > 0) {
          // 将 DebateMessage[] 转为 DisplayMessage[] 用于渲染
          const displayMsgs: DisplayMessage[] = msgs.map((m: DebateMessage) => ({
            id: `${m.positionKey || m.roleKey}-${m.id}`,
            roleKey: m.positionKey || m.roleKey,  // 流程键 = 位置键
            roleName: DEBATE_ROLE_NAMES[m.positionKey || ''] || DEBATE_PERSONALITY_NAMES[m.roleKey] || m.roleName,
            personalityTitle: getPersonalityTitle(m.roleKey),
            side: m.side,
            positionKey: m.positionKey,
            content: m.content,
            timestamp: new Date(m.createdAt).getTime(),
            roundType: m.roundType,
            roundNumber: m.roundNumber,
          }))
          setMessages(displayMsgs)

          if (!session) {
            const lastRound = msgs[msgs.length - 1].roundNumber
            const lastPhase = msgs[msgs.length - 1].roundType as 'OPENING' | 'CROSS_EXAM' | 'REBUTTAL' | 'FREE' | 'CLOSING'
            setCurrentRound(lastRound)
            setCurrentPhase(lastPhase)
          }
          setPhase(session?.status === 'COMPLETED' ? 'completed' : (session?.currentPhase || 'OPENING'))
        } else {
          setPhase('OPENING')
        }
      })
      .catch(() => {
        setPhase('OPENING')
      })
  }, [sessionId])

  // ==================== TTS ====================

  const speakTts = useCallback((text: string, roleKey: string) => {
    if (!window.speechSynthesis) return
    window.speechSynthesis.cancel()

    const config = DEBATE_TTS_CONFIG[roleKey] || { rate: 1, pitch: 1 }
    const utterance = new SpeechSynthesisUtterance(text)
    utterance.lang = 'zh-CN'
    utterance.rate = config.rate
    utterance.pitch = config.pitch
    utterance.onend = () => setSpeakingMsgId(null)
    ttsRef.current = utterance
    window.speechSynthesis.speak(utterance)
  }, [])

  const stopTts = useCallback(() => {
    window.speechSynthesis.cancel()
    setSpeakingMsgId(null)
  }, [])

  // ==================== 流式发音（增量更新） ====================

  const startStreaming = useCallback((
    streamFn: () => { abortController: AbortController; onMessage: (h: (t: string) => void) => void; onDone: (h: () => void) => void; onError: (h: (e: Error) => void) => void },
    roleKey: string,
    onDone: () => void,
  ) => {
    const { abortController, onMessage, onDone: onStreamDone, onError } = streamFn()
    abortRef.current = abortController
    setSpeakingKey(roleKey)

    // 立即插入一条空占位消息（显示"麦克风传递中..."）
    const roleName = DEBATE_ROLE_NAMES[roleKey] || roleKey
    const side = roleKey.startsWith('PRO') ? 'PRO' : roleKey.startsWith('CON') ? 'CON' : 'NEUTRAL'
    const msgId = `${roleKey}-stream-${Date.now()}`
    currentMsgIdRef.current = msgId
    currentContentRef.current = ''

    const placeholder: DisplayMessage = {
      id: msgId,
      roleKey,
      roleName,
      side,
      positionKey: roleKey,
      content: '',
      timestamp: Date.now(),
      streaming: true,
    }
    setMessages(prev => [...prev, placeholder])

    onMessage((text: string) => {
      currentContentRef.current += text
      // 实时更新消息内容
      setMessages(prev => prev.map(m =>
        m.id === msgId ? { ...m, content: currentContentRef.current } : m
      ))
    })

    onStreamDone(() => {
      setSpeakingKey(null)
      if (currentContentRef.current.trim()) {
        // 标记流式结束
        setMessages(prev => prev.map(m =>
          m.id === msgId ? { ...m, streaming: false } : m
        ))

        if (ttsEnabled) {
          setSpeakingMsgId(roleKey + Date.now())
          speakTts(currentContentRef.current.trim(), roleKey)
        }

        // 刷新评分
        if (sessionId) {
          getDebateScores(sessionId).then(setScores).catch(() => {})
        }
      } else {
        // 无内容，移除占位
        setMessages(prev => prev.filter(m => m.id !== msgId))
      }
      currentMsgIdRef.current = null
      currentContentRef.current = ''
      onDone()
    })

    onError((_: unknown) => {
      setSpeakingKey(null)
      // 移除占位消息
      setMessages(prev => prev.filter(m => m.id !== msgId))
      currentMsgIdRef.current = null
      currentContentRef.current = ''
      toast.error('发音失败')
      onDone()
    })
  }, [ttsEnabled, sessionId, speakTts])

  // ==================== 辩论流程控制 ====================

  const speakOpening = useCallback((index: number) => {
    if (!sessionId || index >= OPENING_ORDER.length) return

    const roleKey = OPENING_ORDER[index]
    startStreaming(
      () => streamDebateOpeningSpeech(bookIdNum, roleKey, sessionId, currentRoundRef.current),
      roleKey,
      () => {
        if (index + 1 < OPENING_ORDER.length) {
          setTimeout(() => speakOpening(index + 1), 500)
        } else {
          // 开篇立论结束 → 自动推送到交叉质询
          handleAdvanceRoundRef.current()
        }
      },
    )
  }, [bookIdNum, sessionId, startStreaming])

  // ==================== 交叉质询 (CROSS_EXAM) ====================

  // ==================== 主持人过渡发言 ====================

  const speakHostTransition = useCallback((fromPhase: string, toPhase: string): Promise<void> => {
    return new Promise<void>(resolve => {
      if (!sessionId) { resolve(); return }
      startStreaming(
        () => streamDebateHostSpeech(bookIdNum, sessionId, 'TRANSITION', fromPhase, toPhase),
        'HOST',
        () => { setTimeout(resolve, 500) },
      )
    })
  }, [bookIdNum, sessionId, startStreaming])

  // ==================== 交叉质询 (CROSS_EXAM) ====================

  const startCrossExamRound = useCallback(async () => {
    if (!sessionId) return

    // 继续历史时：跳过已完成回合（按 roleKey 匹配）
    const examMsgs = messages.filter(
      m => m.roundType === 'CROSS_EXAM' && m.roundNumber === currentRoundRef.current
    )
    const spokenCount = examMsgs.length
    let turnIndex = spokenCount
    if (turnIndex >= CROSS_EXAM_ORDER.length) {
      handleAdvanceRoundRef.current()
      return
    }

    // 获取双方一辩立论
    const pro1Msg = messages.find(m => m.positionKey === 'PRO_1' && m.roundType === 'OPENING')
    const con1Msg = messages.find(m => m.positionKey === 'CON_1' && m.roundType === 'OPENING')

    const speakNextTurn = () => {
      if (turnIndex >= CROSS_EXAM_ORDER.length) {
        handleAdvanceRoundRef.current()
        return
      }

      const turn = CROSS_EXAM_ORDER[turnIndex]
      const defenderOpening = turn.defenderKey.startsWith('PRO')
        ? pro1Msg?.content || '' : con1Msg?.content || ''

      // 如果是被质询方，从 messages 或当前流式内容中获取质询问题
      let questionContent = ''
      if (turn.examRole === 'ANSWERER') {
        // 优先从已保存的消息中查找最新的 CROSS_EXAM 消息
        const prevMsgs = messages.filter(
          m => m.roundType === 'CROSS_EXAM' && m.roundNumber === currentRoundRef.current
        )
        if (prevMsgs.length > 0 && prevMsgs[prevMsgs.length - 1].content.trim()) {
          questionContent = prevMsgs[prevMsgs.length - 1].content
        }
      }

      startStreaming(
        () => streamDebateCrossExamSpeech(bookIdNum, turn.roleKey, sessionId, currentRoundRef.current,
          turn.examRole, defenderOpening, questionContent),
        turn.roleKey,
        () => {
          turnIndex++
          setTimeout(speakNextTurn, 500)
        },
      )
    }

    speakNextTurn()
  }, [bookIdNum, sessionId, messages, startStreaming])

  // ==================== 驳论 (REBUTTAL) ====================

  const startRebuttalRound = useCallback(() => {
    if (!sessionId) return

    // 继续历史时：跳过已发言
    const rebuttalMsgs = messages.filter(
      m => m.roundType === 'REBUTTAL' && m.roundNumber === currentRoundRef.current
    )
    const spokenKeys = new Set(rebuttalMsgs.map(m => m.roleKey))
    let rebuttalIndex = REBUTTAL_ORDER.findIndex(k => !spokenKeys.has(k))
    if (rebuttalIndex < 0) {
      handleAdvanceRoundRef.current()
      return
    }

    // 获取对方一辩立论 + 质询内容
    const pro1Msg = messages.find(m => m.positionKey === 'PRO_1' && m.roundType === 'OPENING')
    const con1Msg = messages.find(m => m.positionKey === 'CON_1' && m.roundType === 'OPENING')
    const crossExamMsgs = messages.filter(
      m => m.roundType === 'CROSS_EXAM' && m.roundNumber === currentRoundRef.current - 1
    )
    const crossExamSummary = crossExamMsgs.map(m => `[${m.side}] ${m.roleName}: ${m.content}`).join('\n')

    const speakRebuttal = () => {
      if (rebuttalIndex >= REBUTTAL_ORDER.length) {
        handleAdvanceRoundRef.current()
        return
      }

      const roleKey = REBUTTAL_ORDER[rebuttalIndex]
      const opponentOpening = roleKey.startsWith('PRO') ? con1Msg?.content || '' : pro1Msg?.content || ''

      startStreaming(
        () => streamDebateRebuttalSpeech(bookIdNum, roleKey, sessionId, currentRoundRef.current,
          opponentOpening, crossExamSummary),
        roleKey,
        () => {
          rebuttalIndex++
          setTimeout(speakRebuttal, 500)
        },
      )
    }

    speakRebuttal()
  }, [bookIdNum, sessionId, messages, startStreaming])

  const startFreeRound = useCallback(async () => {
    if (!sessionId) return

    // 继续历史时：取上一条自由辩论发言作为 LLM 上下文
    const freeMsgs = messages.filter(
      m => m.roundType === 'FREE' && m.roundNumber === currentRoundRef.current
    )
    let lastContext = freeMsgs.length > 0 ? freeMsgs[freeMsgs.length - 1].content : ''
    const freeCount = freeMsgs.length

    // 自由辩论有最大发言次数限制
    if (freeCount >= MAX_FREE_EXCHANGES) {
      handleAdvanceRoundRef.current()
      return
    }

    try {
      const firstSpeaker = await getNextDebateSpeaker(sessionId)
      if (!firstSpeaker) {
        handleAdvanceRoundRef.current()
        return
      }

      let currentSpeaker = firstSpeaker
      const speakFree = () => {
        if (!currentSpeaker || !sessionId) {
          handleAdvanceRoundRef.current()
          return
        }

        startStreaming(
          () => streamDebateFreeSpeech(bookIdNum, currentSpeaker, sessionId, currentRoundRef.current, lastContext),
          currentSpeaker,
          () => {
            getNextDebateSpeaker(sessionId)
              .then(next => {
                const currentFreeMsgs = messages.filter(m => m.roundType === 'FREE' && m.roundNumber === currentRoundRef.current)
                if (next && currentFreeMsgs.length < MAX_FREE_EXCHANGES) {
                  currentSpeaker = next
                  setTimeout(speakFree, 500)
                } else {
                  handleAdvanceRoundRef.current()
                }
              })
              .catch(() => handleAdvanceRoundRef.current())
          },
        )
      }

      speakFree()
    } catch {
      handleAdvanceRoundRef.current()
    }
  }, [bookIdNum, sessionId, messages, startStreaming])

  const startClosingRound = useCallback(() => {
    if (!sessionId) return

    // 继续历史时：跳过已发言的总结陈词辩手
    const closingMsgs = messages.filter(
      m => m.roundType === 'CLOSING' && m.roundNumber === currentRoundRef.current
    )
    const spokenClosingKeys = new Set(closingMsgs.map(m => m.roleKey))
    let closeIndex = CLOSING_ORDER.findIndex(k => !spokenClosingKeys.has(k))
    if (closeIndex < 0) {
      setPhase('completed')
      setSessionStatus('COMPLETED')
      isChainActiveRef.current = false
      return
    }

    const speakClosing = () => {
      if (closeIndex >= CLOSING_ORDER.length) {
        // 辩论结束
        setPhase('completed')
        setSessionStatus('COMPLETED')
        isChainActiveRef.current = false
        return
      }

      const roleKey = CLOSING_ORDER[closeIndex]
      startStreaming(
        () => streamDebateClosingSpeech(bookIdNum, roleKey, sessionId, currentRoundRef.current),
        roleKey,
        () => {
          closeIndex++
          setTimeout(speakClosing, 500)
        },
      )
    }

    speakClosing()
  }, [bookIdNum, sessionId, messages, startStreaming])

  const handleAdvanceRound = useCallback(async () => {
    if (!sessionId) return

    try {
      const updated = await advanceDebateRound(sessionId)
      setCurrentRound(updated.currentRound)
      setCurrentPhase(updated.currentPhase as 'OPENING' | 'CROSS_EXAM' | 'REBUTTAL' | 'FREE' | 'CLOSING')

      if (updated.status === 'COMPLETED') {
        setPhase('completed')
        setSessionStatus('COMPLETED')
        isChainActiveRef.current = false
        return
      }

      // Host 过渡发言 → 启动对应环节
      const nextPhase = updated.currentPhase
      setPhase(nextPhase)

      ;(async () => {
        try {
          const idx = ROUND_SEQUENCE.indexOf(nextPhase as typeof ROUND_SEQUENCE[number])
          const prevPhase = idx > 0 ? ROUND_SEQUENCE[idx - 1] : 'OPENING'
          await speakHostTransition(prevPhase, nextPhase)

          switch (nextPhase) {
            case 'CROSS_EXAM':
              startCrossExamRound()
              break
            case 'REBUTTAL':
              startRebuttalRound()
              break
            case 'FREE':
              startFreeRound()
              break
            case 'CLOSING':
              startClosingRound()
              break
            default:
              console.warn('未知辩论阶段:', nextPhase)
              toast.error('未知阶段: ' + nextPhase + '，请重启后端服务')
              isChainActiveRef.current = false
          }
        } catch {
          console.warn('过渡发言失败，直接进入下一环节')
          isChainActiveRef.current = false
        }
      })()
    } catch {
      toast.error('轮次推进失败')
      isChainActiveRef.current = false
    }
  }, [sessionId, startCrossExamRound, startRebuttalRound, startFreeRound, startClosingRound, speakHostTransition])

  // 从 messages 构建 positionKey → personalityTitle 映射（组件顶层，非 JSX 内）
  const personalityMap = useMemo(() => {
    const map: Record<string, string> = {}
    for (const m of messages) {
      if (m.personalityTitle && m.roleKey !== 'HOST' && !map[m.roleKey]) {
        map[m.roleKey] = m.personalityTitle
      }
    }
    return map
  }, [messages])

  // 始终同步最新版到 ref，供内部闭包使用（避开 useCallback 闭包过期）
  handleAdvanceRoundRef.current = handleAdvanceRound
  currentRoundRef.current = currentRound
  currentPhaseRef.current = currentPhase

  // ==================== 开始辩论 ====================

  const handleStartDebate = useCallback(() => {
    isChainActiveRef.current = true
    setPhase('OPENING')
    setTimeout(() => speakOpening(0), 1000)
  }, [speakOpening])

  // ==================== 继续辩论 ====================

  const handleContinue = useCallback(() => {
    if (isChainActiveRef.current) return
    isChainActiveRef.current = true
    if (currentPhase === 'OPENING') {
      // 找到下一个未发言的开篇立论辩手
      const spokenOpeningKeys = new Set(
        messages.filter(m => m.roundType === 'OPENING' && m.roundNumber === currentRoundRef.current).map(m => m.roleKey)
      )
      const nextIndex = OPENING_ORDER.findIndex(key => !spokenOpeningKeys.has(key))
      if (nextIndex >= 0) {
        speakOpening(nextIndex)
      }
    } else if (currentPhase === 'CROSS_EXAM') {
      setPhase('CROSS_EXAM')
      setTimeout(() => startCrossExamRound(), 1000)
    } else if (currentPhase === 'REBUTTAL') {
      setPhase('REBUTTAL')
      setTimeout(() => startRebuttalRound(), 1000)
    } else if (currentPhase === 'FREE') {
      setPhase('FREE')
      setTimeout(() => startFreeRound(), 1000)
    } else if (currentPhase === 'CLOSING') {
      setPhase('CLOSING')
      setTimeout(() => startClosingRound(), 1000)
    }
  }, [currentPhase, messages, speakOpening, startCrossExamRound, startRebuttalRound, startFreeRound, startClosingRound])

  // ==================== 停止辩论 ====================

  const handleStop = useCallback(() => {
    discussionLoopRef.current = false
    isChainActiveRef.current = false
    if (abortRef.current) {
      abortRef.current.abort()
      abortRef.current = null
    }
    stopTts()
    setSpeakingKey(null)
    // 清理残留的流式占位消息
    setMessages(prev => prev.map(m =>
      m.streaming ? { ...m, streaming: false } : m
    ))
  }, [stopTts])

  // ==================== 报告 ====================

  const handleTriggerReport = useCallback(async () => {
    if (!sessionId) return
    setReportGenerating(true)
    try {
      await triggerDebateReport(sessionId)
      // 轮询
      reportPollRef.current = setInterval(async () => {
        const poll = reportPollRef.current
        if (!poll) return
        try {
          const r = await getDebateReport(sessionId)
          if (r && r.status !== 'GENERATING' && r.status !== 'PENDING') {
            setReport(r)
            setReportGenerating(false)
            clearInterval(poll)
            reportPollRef.current = null
          }
        } catch { /* ignore */ }
      }, 3000)
    } catch {
      setReportGenerating(false)
      toast.error('触发报告生成失败')
    }
  }, [sessionId])

  // ==================== 渲染 ====================

  const isStarted = messages.length > 0

  // 如果还未开始，显示开始界面
  if (phase === 'loading') {
    return (
      <div className="flex min-h-dvh items-center justify-center bg-gradient-to-b from-background to-muted/30">
        <Loader2 className="h-6 w-6 animate-spin text-orange-500" />
      </div>
    )
  }

  return (
    <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col overflow-hidden bg-background">
      {/* 顶部导航 — 与圆桌派一致：左侧图书信息 + 右上轮次指示 */}
      <header className="shrink-0 flex items-center gap-3 border-b border-border/30 bg-background/80 px-4 py-2.5 backdrop-blur-xl z-20">
        <button
          onClick={() => navigate(-1)}
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl hover:bg-muted transition-colors"
        >
          <ArrowLeft className="h-4 w-4" />
        </button>
        <div className="min-w-0 flex-1">
          <h1 className="text-sm font-bold text-foreground truncate">
            {bookTitle ? `《${bookTitle}》奇葩说辩论` : '奇葩说辩论'}
          </h1>
          <p className="text-[10px] text-muted-foreground truncate flex items-center gap-1.5">
            <span className={`inline-block h-1.5 w-1.5 rounded-full ${
              sessionStatus === 'COMPLETED' ? 'bg-green-500' : 'bg-orange-400'
            }`} />
            {sessionStatus === 'COMPLETED' ? '已完成' : '进行中'}
            {' · '}{ROUND_LABELS[currentPhase] || currentPhase}
            {' · '}第{currentRound}轮
            {speakingKey ? ' · 发言中' : ''}
          </p>
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden relative">
        <div className="flex flex-1 flex-col overflow-hidden relative">
          {/* 轮次指示器 */}
          <div className="shrink-0 flex items-center justify-center gap-2 px-4 py-2 border-b border-border/10">
            {ROUND_SEQUENCE.map((r, i) => (
              <div key={r} className="flex items-center gap-2">
                <div className={`flex items-center gap-1 ${
                  currentPhase === r ? 'text-orange-500 font-bold' : 'text-muted-foreground/50'
                }`}>
                  <div className={`h-2 w-2 rounded-full ${
                    currentPhase === r ? 'bg-orange-500' : 'bg-muted'
                  }`} />
                  <span className="text-[9px]">{ROUND_LABELS[r]}</span>
                </div>
                {i < ROUND_SEQUENCE.length - 1 && (
                  <div className="h-px w-4 bg-border/30" />
                )}
              </div>
            ))}
          </div>

          {/* RoleBar — 显示名称 + 称号 */}
          <RoleBar
            speakCounts={speakCounts}
            speakingKey={speakingKey}
            allRoleKeys={allRoleKeys.current}
            personalityTitles={personalityMap}
          />

          {/* 消息列表 */}
          <div ref={scrollContainerRef} className={`flex-1 ${isMobile ? 'overflow-y-auto overscroll-y-contain' : 'min-h-0 flex flex-col'}`}>
            {!isStarted ? (
              <div className="flex flex-1 flex-col items-center justify-center gap-4 px-4 py-12">
                <p className="text-sm text-muted-foreground">欢迎来到奇葩说！</p>
                <button
                  onClick={handleStartDebate}
                  className="rounded-xl bg-gradient-to-r from-orange-400 to-orange-500 px-6 py-3 text-sm font-bold text-white shadow-lg shadow-orange-500/25 active:scale-[0.97] transition-transform"
                >
                  开始辩论
                </button>
              </div>
            ) : (
              <>
                {/* PC端分屏 — 各列独立滚动 */}
                {!isMobile && (
                  <div className="flex-1 min-h-0 flex overflow-hidden">
                    {/* 正方区（左）— 更宽，2份 */}
                    <div onScroll={handleScroll} className="flex-[2] min-w-0 border-r border-border/10 bg-blue-50/20 dark:bg-blue-950/10 p-3 space-y-3 overflow-y-auto">
                      <div className="text-[10px] font-bold text-blue-500 uppercase tracking-wider">正方</div>
                      {messages.filter(m => m.side === 'PRO').map(m => (
                        <div key={m.id} className="rounded-xl bg-blue-500/5 border border-blue-200/30 p-3">
                          <div className="flex items-center gap-1.5 mb-1">
                            <span className="text-xs font-bold" style={{ color: DEBATE_ROLE_COLORS[m.roleKey] }}>
                              {m.roleName}
                            </span>
                          </div>
                          {m.streaming && !m.content ? (
                            <span className="flex items-center gap-1.5 text-[11px] text-blue-400">
                              <Loader2 className="h-3 w-3 animate-spin" />
                              麦克风传递中...
                            </span>
                          ) : (
                            <p className="text-xs leading-relaxed">{m.content}</p>
                          )}
                          {m.streaming && m.content && (
                            <span className="inline-flex items-center ml-1">
                              <span className="h-3 w-[2px] bg-blue-400 animate-pulse rounded-full" />
                            </span>
                          )}
                        </div>
                      ))}
                    </div>

                    {/* 中心区（主持人）— 更窄，固定宽度 */}
                    <div onScroll={handleScroll} className="w-72 shrink-0 p-3 space-y-3 overflow-y-auto">
                      {messages.filter(m => m.side === 'NEUTRAL').map(m => (
                        <div key={m.id} className="rounded-xl bg-yellow-50/50 dark:bg-yellow-500/5 border border-yellow-200/30 p-3 max-w-[230px] mx-auto text-center">
                          <div className="flex items-center justify-center gap-1.5 mb-1">
                            <span className="text-xs font-bold" style={{ color: DEBATE_ROLE_COLORS[m.roleKey] }}>
                              🎙️ {m.roleName}
                            </span>
                          </div>
                          {m.streaming && !m.content ? (
                            <span className="flex items-center justify-center gap-1.5 text-[11px] text-yellow-500">
                              <Loader2 className="h-3 w-3 animate-spin" />
                              麦克风传递中...
                            </span>
                          ) : (
                            <p className="text-xs leading-relaxed">{m.content}</p>
                          )}
                          {m.streaming && m.content && (
                            <span className="inline-flex items-center ml-1">
                              <span className="h-3 w-[2px] bg-yellow-400 animate-pulse rounded-full" />
                            </span>
                          )}
                        </div>
                      ))}
                    </div>

                    {/* 反方区（右）— 更宽，2份 */}
                    <div onScroll={handleScroll} className="flex-[2] min-w-0 border-l border-border/10 bg-red-50/20 dark:bg-red-950/10 p-3 space-y-3 overflow-y-auto">
                      <div className="text-[10px] font-bold text-red-500 uppercase tracking-wider">反方</div>
                      {messages.filter(m => m.side === 'CON').map(m => (
                        <div key={m.id} className="rounded-xl bg-red-500/5 border border-red-200/30 p-3">
                          <div className="flex items-center gap-1.5 mb-1">
                            <span className="text-xs font-bold" style={{ color: DEBATE_ROLE_COLORS[m.roleKey] }}>
                              {m.roleName}
                            </span>
                          </div>
                          {m.streaming && !m.content ? (
                            <span className="flex items-center gap-1.5 text-[11px] text-red-400">
                              <Loader2 className="h-3 w-3 animate-spin" />
                              麦克风传递中...
                            </span>
                          ) : (
                            <p className="text-xs leading-relaxed">{m.content}</p>
                          )}
                          {m.streaming && m.content && (
                            <span className="inline-flex items-center ml-1">
                              <span className="h-3 w-[2px] bg-red-400 animate-pulse rounded-full" />
                            </span>
                          )}
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {/* 移动端上下排列 */}
                {isMobile && (
                  <div onScroll={handleScroll} className="p-3 space-y-3">
                    {messages.map(m => {
                      const isPro = m.side === 'PRO'
                      const isCon = m.side === 'CON'
                      const color = DEBATE_ROLE_COLORS[m.roleKey] || '#888'
                      const bgColor = isPro ? 'bg-blue-500/5 border-blue-200/30' :
                        isCon ? 'bg-red-500/5 border-red-200/30' :
                        'bg-yellow-50/50 border-yellow-200/30'

                      return (
                        <div key={m.id} className={`rounded-xl border p-3 ${bgColor}`}
                          style={isPro ? { borderLeft: `3px solid ${color}` } : isCon ? { borderRight: `3px solid ${color}` } : {}}
                        >
                          <div className={`flex items-center gap-1.5 mb-1 ${isCon ? 'flex-row-reverse' : ''}`}>
                            <span className="font-bold" style={{ color, fontSize: '11px' }}>
                              {m.roleName}
                            </span>
                            {m.personalityTitle && m.roleKey !== 'HOST' && !m.streaming && (
                              <span className="text-[9px] text-muted-foreground/60 ml-1" style={{ color: color + '80' }}>
                                {m.personalityTitle}
                              </span>
                            )}
                            {m.streaming && (
                              <span className="flex items-end gap-[1px] h-2">
                                {[0, 1, 2].map(i => (
                                  <span key={i} className="w-[1.5px] rounded-full bg-foreground/20 animate-pulse" style={{ animationDelay: `${i * 150}ms`, height: `${3 + (i % 2) * 3}px` }} />
                                ))}
                              </span>
                            )}
                          </div>
                          {m.streaming && !m.content ? (
                            <span className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
                              <Loader2 className="h-3 w-3 animate-spin" />
                              麦克风传递中...
                            </span>
                          ) : (
                            <p className="text-xs leading-relaxed">{m.content}</p>
                          )}
                          {m.streaming && m.content && (
                            <span className="inline-flex items-center ml-1">
                              <span className="h-3 w-[2px] bg-foreground/30 animate-pulse rounded-full" />
                            </span>
                          )}
                        </div>
                      )
                    })}

                    {/* 滚动锚点 */}
                    <div ref={messagesEndRef} />
                  </div>
                )}
              </>
            )}
          </div>

          {/* 底部控制栏 — 与圆桌派一致 */}
          <div
            className="shrink-0 border-t border-border/20 bg-background/95 backdrop-blur-xl px-4 py-2.5"
            style={{ paddingBottom: 'calc(env(safe-area-inset-bottom, 0px) + 0.75rem)' }}
          >
            <div className="flex items-center gap-2 max-w-3xl mx-auto">
              {/* 左区：操作按钮 */}
              <div className="flex items-center gap-1">
                <button
                  onClick={() => {
                    if (ttsEnabled) {
                      stopTts()
                      setTtsEnabled(false)
                    } else {
                      setTtsEnabled(true)
                      // 自动朗读最后一条非流式消息
                    }
                  }}
                  className={`flex items-center justify-center gap-1.5 rounded-full sm:rounded-xl p-0 sm:px-3 py-2 sm:py-2 h-10 sm:h-auto w-10 sm:w-auto text-[11px] sm:text-xs font-medium transition-all duration-200 ${
                    ttsEnabled
                      ? 'bg-orange-100 text-orange-500 border border-orange-200'
                      : 'bg-muted text-muted-foreground hover:text-foreground'
                  }`}
                >
                  <Volume2 className="h-3 w-3 shrink-0" />
                  <span className="hidden sm:inline">{ttsEnabled ? '朗读中' : '语音关'}</span>
                </button>

                {phase !== 'completed' && speakingKey && (
                  <button
                    onClick={handleStop}
                    className="flex items-center justify-center gap-1.5 rounded-full sm:rounded-xl p-0 sm:px-3 py-2 sm:py-2 h-10 sm:h-auto w-10 sm:w-auto text-[11px] sm:text-xs font-medium bg-red-500/10 text-red-500 hover:bg-red-500/20 transition-colors"
                  >
                    <Square className="h-3 w-3 shrink-0" />
                    <span className="hidden sm:inline">结束</span>
                  </button>
                )}

                {/* 继续辩论 — 有消息但未完成且未在发言时显示 */}
                {isStarted && sessionStatus !== 'COMPLETED' && !speakingKey && !isChainActiveRef.current && (
                  <button
                    onClick={handleContinue}
                    className="flex items-center justify-center gap-1.5 rounded-full sm:rounded-xl p-0 sm:px-3 py-2 sm:py-2 h-10 sm:h-auto w-10 sm:w-auto text-[11px] sm:text-xs font-semibold bg-gradient-to-r from-orange-400 to-orange-500 text-white shadow-md shadow-orange-400/20 active:scale-[0.97] transition-transform"
                  >
                    <Play className="h-3 w-3 shrink-0" />
                    <span className="hidden sm:inline">继续</span>
                  </button>
                )}
              </div>

              <div className="flex-1" />

              {/* 右区：信息开关 */}
              <div className="flex items-center gap-1">
                {isStarted && (
                  <>
                    <button
                      onClick={() => {
                        if (showScorePanel) { setShowScorePanel(false); return }
                        // 打开评分面板时自动加载评分
                        if (sessionId && scores.length === 0) {
                          getDebateScores(sessionId).then(setScores).catch(() => {})
                        }
                        setShowScorePanel(true)
                      }}
                      className={`flex items-center justify-center gap-1 rounded-full sm:rounded-xl p-0 sm:px-2.5 py-2 sm:py-2 h-10 sm:h-auto w-10 sm:w-auto text-[11px] sm:text-xs transition-colors ${
                        showScorePanel ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground hover:text-foreground'
                      }`}
                    >
                      <BarChart3 className="h-3 w-3 shrink-0" />
                      <span className="hidden sm:inline">评分</span>
                    </button>

                    <button
                      onClick={() => {
                        if (showReportPanel) { setShowReportPanel(false); return }
                        setShowReportPanel(true)
                        if (!report && sessionId) {
                          getDebateReport(sessionId).then(setReport).catch(() => {})
                        }
                      }}
                      className={`flex items-center justify-center gap-1 rounded-full sm:rounded-xl p-0 sm:px-2.5 py-2 sm:py-2 h-10 sm:h-auto w-10 sm:w-auto text-[11px] sm:text-xs transition-colors ${
                        showReportPanel ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground hover:text-foreground'
                      }`}
                    >
                      {reportGenerating || report?.status === 'GENERATING' ? (
                        <Loader2 className="h-3 w-3 shrink-0 animate-spin" />
                      ) : (
                        <FileText className="h-3 w-3 shrink-0" />
                      )}
                      <span className="hidden sm:inline">报告</span>
                    </button>
                  </>
                )}

                <span className="text-xs text-muted-foreground/60 shrink-0 tabular-nums">
                  {allRoleKeys.current.filter(k => !k.startsWith('HOST')).length}人·{currentRound}轮
                </span>
              </div>
            </div>
          </div>
        </div>

        {/* PC端评分/报告面板 — 圆桌派式动画宽度 */}
        {!isMobile && (
          <>
            <div className={`shrink-0 overflow-hidden transition-all duration-300 ease-out flex flex-col border-l border-border/20 bg-background/95 backdrop-blur-xl ${showScorePanel ? 'w-80' : 'w-0'}`}>
              {showScorePanel && (
                <ScorePanel scores={scores} onClose={() => setShowScorePanel(false)} onRefresh={() => sessionId ? getDebateScores(sessionId).then(setScores) : null} isMobile={isMobile} />
              )}
            </div>
            <div className={`shrink-0 overflow-hidden transition-all duration-300 ease-out border-l border-border/20 bg-background/95 backdrop-blur-xl ${showReportPanel ? 'w-[420px] max-w-[90vw]' : 'w-0'}`}>
              {showReportPanel && (
                <ReportPanel
                  report={report}
                  isGenerating={reportGenerating}
                  onTrigger={handleTriggerReport}
                  onClose={() => setShowReportPanel(false)}
                />
              )}
            </div>
          </>
        )}
      </div>

      {/* 移动端评分/报告面板 — 底部 Sheet */}
      {isMobile && showScorePanel && (
        <Sheet open={showScorePanel} onOpenChange={(v) => !v && setShowScorePanel(false)}>
          <SheetContent side="bottom" className="rounded-t-2xl p-0 max-h-[80vh]">
            <ScorePanel scores={scores} onClose={() => setShowScorePanel(false)} onRefresh={() => sessionId ? getDebateScores(sessionId).then(setScores) : null} isMobile={isMobile} />
          </SheetContent>
        </Sheet>
      )}
      {isMobile && showReportPanel && (
        <Sheet open={showReportPanel} onOpenChange={(v) => !v && setShowReportPanel(false)}>
          <SheetContent side="bottom" className="rounded-t-2xl p-0 max-h-[80vh]">
            <ReportPanel
              report={report}
              isGenerating={reportGenerating}
              onTrigger={handleTriggerReport}
              onClose={() => setShowReportPanel(false)}
            />
          </SheetContent>
        </Sheet>
      )}
    </div>
  )
}
