import { useState, useEffect, useRef, useCallback, useMemo } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  ArrowLeft, Volume2, Square, Loader2, BarChart3, FileText, Play, Pause, Mic,
} from 'lucide-react'
import { Sheet, SheetContent } from '@/components/ui/sheet'
import ScorePanel from '@/components/debate/ScorePanel'
import ReportPanel from '@/components/debate/ReportPanel'
import MarkdownRenderer from '@/components/ui/markdown-renderer'
import {
  getDebateMessages, getNextDebateSpeaker, advanceDebateRound,
  getDebateSession,
  streamDebateOpeningSpeech, streamDebateCrossExamSpeech, streamDebateRebuttalSpeech,
  streamDebateFreeSpeech, streamDebateClosingSpeech,
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
import { useAuthStore } from '@/store/auth'
import { getSortedChineseVoices, assignVoiceForRole } from '@/utils/browserTts'
import { speechService } from '@/utils/speechService'
import { getAzureVoiceForRole, DEBATE_AZURE_VOICE } from '@/utils/speechVoices'

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
  examRole?: string
}

/** 纯色转 rgba */
function hexToRgba(hex: string, alpha: number) {
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const c = parseInt(hex.slice(5, 7), 16)
  return `rgba(${r}, ${g}, ${c}, ${alpha})`
}

/** 将长文本分块，TTS 逐块朗读 */
function splitLongText(text: string, maxLen = 180): string[] {
  const chunks: string[] = []
  let i = 0
  while (i < text.length) {
    let end = Math.min(i + maxLen, text.length)
    if (end < text.length) {
      const nextPeriod = text.indexOf('。', i + Math.floor(maxLen * 0.6))
      if (nextPeriod > 0 && nextPeriod < end + 20) end = nextPeriod + 1
      else {
        const nextNewline = text.indexOf('\n', i + Math.floor(maxLen * 0.6))
        if (nextNewline > 0 && nextNewline < end + 10) end = nextNewline + 1
      }
    }
    chunks.push(text.slice(i, end).trim())
    i = end
  }
  return chunks.filter(Boolean)
}

const ROUND_SEQUENCE = ['OPENING', 'CROSS_EXAM', 'REBUTTAL', 'FREE', 'CLOSING'] as const
const ROUND_LABELS: Record<string, string> = {
  OPENING: '开篇立论',
  CROSS_EXAM: '交叉质询',
  REBUTTAL: '驳论',
  FREE: '自由辩论',
  CLOSING: '总结陈词',
}

/** 开篇立论发言顺序 — 仅一辩立论（主持人单独开场） */
const OPENING_ORDER = ['PRO_1', 'CON_1']

/** 交叉质询顺序 — 二辩质询对方一辩，各1轮（共2 Q&A对） */
interface CrossExamTurn { roleKey: string; examRole: 'QUESTIONER' | 'ANSWERER'; defenderKey: string }
const CROSS_EXAM_ORDER: CrossExamTurn[] = [
  { roleKey: 'PRO_2', examRole: 'QUESTIONER', defenderKey: 'CON_1' },
  { roleKey: 'CON_1', examRole: 'ANSWERER',   defenderKey: 'CON_1' },
  { roleKey: 'CON_2', examRole: 'QUESTIONER', defenderKey: 'PRO_1' },
  { roleKey: 'PRO_1', examRole: 'ANSWERER',   defenderKey: 'PRO_1' },
]

/** 驳论发言顺序 — 二辩 */
const REBUTTAL_ORDER = ['PRO_2', 'CON_2']

/** 总结陈词发言顺序 — 反方先，正方后 */
const CLOSING_ORDER = ['CON_4', 'PRO_4']

/** 自由辩论最大发言轮次 */
const MAX_FREE_EXCHANGES = 12

/** 刷新页面时重建主持人消息（前端预写，不存后端） */
function insertHostMessages(msgs: DisplayMessage[], bookTitle: string): DisplayMessage[] {
  const result: DisplayMessage[] = []
  let id = 0
  const host = (tag: string, content: string): DisplayMessage => ({
    id: `host-replay-${tag}-${id++}`, roleKey: 'HOST', roleName: '主持人',
    side: 'NEUTRAL', positionKey: 'HOST', content, timestamp: 0,
  })

  // INTRO 开场
  result.push(host('intro', bookTitle
    ? `欢迎来到奇葩说辩论。今天讨论的书籍是《${bookTitle}》。有请正方一辩开篇立论。`
    : `欢迎来到奇葩说辩论。有请正方一辩开篇立论。`))

  let prevRound = ''
  let crossExamIdx = 0
  for (let i = 0; i < msgs.length; i++) {
    const m = msgs[i]

    // 环节过渡
    if (m.roundType && m.roundType !== prevRound && prevRound !== '') {
      const toLabel = ROUND_LABELS[m.roundType] || m.roundType
      const next = m.roundType === 'CROSS_EXAM' ? '正方二辩' :
                   m.roundType === 'REBUTTAL' ? '正方二辩' :
                   m.roundType === 'FREE' ? '正方' :
                   m.roundType === 'CLOSING' ? '反方四辩' : '双方辩手'
      result.push(host(`trans-${m.roundType}`, `下面进入${toLabel}环节。请${next}准备。`))
      if (m.roundType === 'CROSS_EXAM') crossExamIdx = 0
    }
    prevRound = m.roundType || prevRound

    // 交叉质询：CON_1 回答完后，插入 CON_2 质询前的主持词
    if (m.roundType === 'CROSS_EXAM') crossExamIdx++
    if (crossExamIdx === 2) {
      result.push(host('cross-con2', '有请反方二辩质询正方一辩'))
    }

    // 开篇立论：PRO_1 后插入 CON_1 前主持词
    if (m.positionKey === 'PRO_1' && m.roundType === 'OPENING') {
      result.push(m)
      const next = msgs[i + 1]
      if (next?.positionKey === 'CON_1' && next?.roundType === 'OPENING') {
        result.push(host('opening-con1', '感谢正方一辩。有请反方一辩开篇立论'))
      }
      continue
    }
    // 总结陈词：CON_4 后插入 PRO_4 前主持词
    if (m.positionKey === 'CON_4' && m.roundType === 'CLOSING') {
      result.push(m)
      const next = msgs[i + 1]
      if (next?.positionKey === 'PRO_4' && next?.roundType === 'CLOSING') {
        result.push(host('closing-pro4', '感谢反方四辩。有请正方四辩总结陈词'))
      }
      continue
    }

    result.push(m)
  }

  return result
}


/** 角色状态条 — 嵌入 header，带性格标签 */
function RoleStatusBar({ speakingKey, allRoleKeys, personalityMap }: {
  speakingKey: string | null
  allRoleKeys: string[]
  personalityMap: Record<string, string>
}) {
  const proKeys = allRoleKeys.filter(k => k.startsWith('PRO'))
  const conKeys = allRoleKeys.filter(k => k.startsWith('CON'))

  return (
    <div className="flex items-center gap-2">
      {/* 正方圆点 */}
      <div className="flex items-center gap-1">
        {proKeys.map(key => {
          const isActive = speakingKey === key
          const color = DEBATE_ROLE_COLORS[key] || '#6B8FA8'
          const personality = personalityMap[key]
          const label = `${DEBATE_ROLE_NAMES[key] || key}${personality ? ` · ${personality}` : ''}`
          return (
            <div
              key={key}
              className="relative flex items-center justify-center group"
              title={label}
            >
              <div
                className={`rounded-full transition-all duration-300 ${
                  isActive ? 'h-2.5 w-2.5 scale-110' : 'h-2 w-2'
                }`}
                style={{
                  backgroundColor: isActive ? color : hexToRgba(color, 0.25),
                  boxShadow: isActive ? `0 0 10px ${hexToRgba(color, 0.6)}` : 'none',
                }}
              />
              {/* tooltip */}
              <div className="absolute -top-7 left-1/2 -translate-x-1/2 hidden group-hover:block whitespace-nowrap z-50">
                <div className="px-1.5 py-0.5 rounded-md bg-popover border border-border/20 shadow-sm">
                  <span className="text-[10px] text-popover-foreground">{label}</span>
                </div>
              </div>
            </div>
          )
        })}
      </div>

      {/* VS 分隔 */}
      <span className="text-[10px] font-bold text-muted-foreground/40">VS</span>

      {/* 反方圆点 */}
      <div className="flex items-center gap-1">
        {conKeys.map(key => {
          const isActive = speakingKey === key
          const color = DEBATE_ROLE_COLORS[key] || '#C75B5B'
          const personality = personalityMap[key]
          const label = `${DEBATE_ROLE_NAMES[key] || key}${personality ? ` · ${personality}` : ''}`
          return (
            <div
              key={key}
              className="relative flex items-center justify-center group"
              title={label}
            >
              <div
                className={`rounded-full transition-all duration-300 ${
                  isActive ? 'h-2.5 w-2.5 scale-110' : 'h-2 w-2'
                }`}
                style={{
                  backgroundColor: isActive ? color : hexToRgba(color, 0.25),
                  boxShadow: isActive ? `0 0 10px ${hexToRgba(color, 0.6)}` : 'none',
                }}
              />
              {/* tooltip */}
              <div className="absolute -top-7 left-1/2 -translate-x-1/2 hidden group-hover:block whitespace-nowrap z-50">
                <div className="px-1.5 py-0.5 rounded-md bg-popover border border-border/20 shadow-sm">
                  <span className="text-[10px] text-popover-foreground">{label}</span>
                </div>
              </div>
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
  const [speakingMsgId, setSpeakingMsgId] = useState<string | null>(null)
  const [currentRound, setCurrentRound] = useState(1)
  const [currentPhase, setCurrentPhase] = useState<'OPENING' | 'CROSS_EXAM' | 'REBUTTAL' | 'FREE' | 'CLOSING'>('OPENING')
  const [scores, setScores] = useState<DebateScore[]>([])
  const [report, setReport] = useState<DebateReport | null>(null)
  const [reportGenerating, setReportGenerating] = useState(false)
  const [showScorePanel, setShowScorePanel] = useState(false)
  const [showReportPanel, setShowReportPanel] = useState(false)
  const [bookTitle, setBookTitle] = useState<string>('')
  const [sessionStatus, setSessionStatus] = useState<string>('ACTIVE')
  const [sessionProKeys, setSessionProKeys] = useState('')
  const [sessionConKeys, setSessionConKeys] = useState('')
  const [isOwner, setIsOwner] = useState(false)
  const [useOverlay, setUseOverlay] = useState(false)
  const abortRef = useRef<AbortController | null>(null)
  const mainLayoutRef = useRef<HTMLDivElement>(null)
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
  /** 最近一次流式发言的完整内容 — 解决闭包陷阱：回调链中 messages 是旧快照 */
  const lastStreamedContentRef = useRef('')

  // TTS refs
  const ttsQueueRef = useRef<{ text: string; roleKey: string; msgIndex: number }[]>([])
  const ttsSpeakingRef = useRef(false)
  const roleVoiceMapRef = useRef<Map<string, SpeechSynthesisVoice>>(new Map())
  const zhVoicesRef = useRef<SpeechSynthesisVoice[]>([])
  const speechEnabledRef = useRef(false)

  // 发言次数统计（调试用）
  useMemo(() => messages.reduce<Record<string, number>>((acc, m) => {
    acc[m.roleKey] = (acc[m.roleKey] || 0) + 1
    return acc
  }, {}), [messages])

  // 加载图书信息
  useEffect(() => {
    if (!bookIdNum) return
    getBook(bookIdNum).then(book => {
      setBookTitle(book.title)
    }).catch(() => {})
  }, [bookIdNum])

  // ==================== 响应式布局检测 ====================
  useEffect(() => {
    const checkLayout = () => {
      const width = mainLayoutRef.current?.clientWidth || window.innerWidth
      // 辩论内容最小需要 640px，评分面板 320px，报告面板 420px
      // 如果打开面板后内容区小于 640px，就用覆盖层模式
      setUseOverlay(width < 1100)
    }
    checkLayout()
    window.addEventListener('resize', checkLayout)
    return () => window.removeEventListener('resize', checkLayout)
  }, [])

  // ==================== 智能自动滚动 ====================
  // PC 双栏：每列独立追踪是否「在底部」和「用户是否手动滚动」
  const colScrollStateRef = useRef<Map<HTMLElement, { nearBottom: boolean; userScrolled: boolean }>>(new Map())

  const isColNearBottom = useCallback((col: HTMLElement) => {
    return col.scrollHeight - col.scrollTop - col.clientHeight < 100
  }, [])

  const handleColScroll = useCallback((col: HTMLElement) => {
    const near = isColNearBottom(col)
    const state = colScrollStateRef.current.get(col) || { nearBottom: true, userScrolled: false }
    // 用户向上滚动（远离底部）→ 标记为手动滚动
    if (!near && state.nearBottom) {
      state.userScrolled = true
    }
    // 用户拖回底部 → 恢复自动跟随
    if (near && state.userScrolled) {
      state.userScrolled = false
    }
    state.nearBottom = near
    colScrollStateRef.current.set(col, state)
  }, [isColNearBottom])

  const handleScroll = useCallback(() => {
    const el = scrollContainerRef.current
    if (!el) return
    if (!isMobile) {
      const columns = el.querySelectorAll('.debate-column')
      columns.forEach(col => handleColScroll(col as HTMLElement))
    } else {
      const near = el.scrollHeight - el.scrollTop - el.clientHeight < 100
      if (near) {
        userScrolledAwayRef.current = false
      } else if (el.scrollTop < lastScrollTopRef.current) {
        // 移动端向上滚动
        userScrolledAwayRef.current = true
      }
      lastScrollTopRef.current = el.scrollTop
    }
  }, [isMobile, handleColScroll])

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

  // 语音服务初始化
  useEffect(() => {
    speechService.init().then(() => {
      speechEnabledRef.current = speechService.activeProvider !== 'browser'
    })
  }, [])

  // 新内容到达时自动滚动到底部（仅当用户未手动离开时）
  useEffect(() => {
    if (isMobile) {
      if (userScrolledAwayRef.current) return
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
      return
    }

    // PC 双栏：只滚动「有新增消息且在底部」的列
    const cols = scrollContainerRef.current?.querySelectorAll('.debate-column')
    cols?.forEach(col => {
      const state = colScrollStateRef.current.get(col as HTMLElement) || { nearBottom: true, userScrolled: false }
      if (!state.userScrolled) {
        ;(col as HTMLElement).scrollTop = (col as HTMLElement).scrollHeight
      }
    })
  }, [messages, isMobile])

  // 初始加载完成后，滚动到底部（有历史消息时）
  const initialScrollDone = useRef(false)
  useEffect(() => {
    if (phase === 'loading' || initialScrollDone.current) return
    if (messages.length === 0) return
    initialScrollDone.current = true

    requestAnimationFrame(() => {
      if (isMobile) {
        messagesEndRef.current?.scrollIntoView({ block: 'end' })
      } else {
        const cols = scrollContainerRef.current?.querySelectorAll('.debate-column')
        cols?.forEach(col => {
          const c = col as HTMLElement
          c.scrollTop = c.scrollHeight
          colScrollStateRef.current.set(c, { nearBottom: true, userScrolled: false })
        })
      }
    })
  }, [phase, isMobile, messages.length])

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
          setSessionProKeys(session.proRoleKeys || '')
          setSessionConKeys(session.conRoleKeys || '')
        }

        // 判断是否为会话创建者
        const currentUserId = useAuthStore.getState().userInfo?.id
        setIsOwner(currentUserId != null && session != null && session.userId === currentUserId)

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
            examRole: m.examRole,
          }))

          // 插入主持人消息（预写文本，刷新后重建）
          const withHost = insertHostMessages(displayMsgs, bookTitle)
          setMessages(withHost)

          let isCompleted = session?.status === 'COMPLETED'

          if (!session && msgs.length > 0) {
            const lastMsg = msgs[msgs.length - 1]
            setCurrentRound(lastMsg.roundNumber)
            setCurrentPhase(lastMsg.roundType as 'OPENING' | 'CROSS_EXAM' | 'REBUTTAL' | 'FREE' | 'CLOSING')
            if (lastMsg.roundType === 'CLOSING') {
              isCompleted = true
              setSessionStatus('COMPLETED')
            }
          }
          setPhase(isCompleted ? 'completed' : (session?.currentPhase || 'OPENING'))

          // 已完成辩论进入页面时自动打开报告面板并加载报告
          if (isCompleted && sessionId) {
            setShowReportPanel(true)
            getDebateReport(sessionId).then(setReport).catch(() => {})
          }
        } else {
          setPhase('OPENING')
        }
      })
      .catch(() => {
        setPhase('OPENING')
      })
  }, [sessionId])

  // ==================== TTS 队列 + 角色音色 ====================

  const processTtsQueue = useCallback(() => {
    const synth = window.speechSynthesis
    if (!synth) return
    if (ttsSpeakingRef.current || ttsQueueRef.current.length === 0) return

    try { synth.resume() } catch {}
    try { if (synth.speaking) synth.cancel() } catch {}
    if (ttsSpeakingRef.current || ttsQueueRef.current.length === 0) return

    const { text, roleKey } = ttsQueueRef.current.shift()!
    ttsSpeakingRef.current = true

    if (speechEnabledRef.current) {
      const voiceName = getAzureVoiceForRole(roleKey, DEBATE_AZURE_VOICE)
      const ttsCfg = DEBATE_TTS_CONFIG[roleKey] || { pitch: 1.0, rate: 1.0 }
      speechService.speak(text, voiceName, ttsCfg.rate, ttsCfg.pitch, () => {
        ttsSpeakingRef.current = false
        processTtsQueue()
      })
      return
    }

    const utterance = new SpeechSynthesisUtterance(text)
    const config = DEBATE_TTS_CONFIG[roleKey] || { pitch: 1.0, rate: 1.0 }
    utterance.pitch = Math.max(0.5, Math.min(2.0, config.pitch))
    utterance.rate = config.rate
    utterance.lang = 'zh-CN'
    utterance.volume = 1.0

    // 为不同角色分配不同语音
    let zhVoices = zhVoicesRef.current
    if (zhVoices.length === 0 && window.speechSynthesis) {
      try {
        zhVoices = getSortedChineseVoices(window.speechSynthesis)
        if (zhVoices.length > 0) zhVoicesRef.current = zhVoices
      } catch {}
    }
    if (zhVoices.length > 0) {
      const voice = assignVoiceForRole(roleKey, roleVoiceMapRef.current, zhVoices)
      if (voice) {
        utterance.voice = voice
        utterance.lang = voice.lang
      }
    }

    utterance.onend = () => {
      ttsSpeakingRef.current = false
      processTtsQueue()
    }
    utterance.onerror = () => {
      ttsSpeakingRef.current = false
      processTtsQueue()
    }

    try { synth.speak(utterance) } catch { ttsSpeakingRef.current = false }
  }, [])

  const enqueueTts = useCallback((text: string, roleKey: string, msgIndex: number) => {
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
  }, [processTtsQueue])

  const stopTts = useCallback(() => {
    speechService.stop()
    try { window.speechSynthesis?.cancel() } catch {}
    setSpeakingMsgId(null)
    ttsQueueRef.current = []
    ttsSpeakingRef.current = false
  }, [])

  const handleToggleSpeak = useCallback((msgId: string, content: string, roleKey: string) => {
    const synth = window.speechSynthesis
    if (!synth) return
    if (speakingMsgId === msgId) { synth.cancel(); setSpeakingMsgId(null); return }
    synth.cancel()
    setSpeakingMsgId(msgId)
    const cleanText = content.replace(/```[\s\S]*?```/g, '').replace(/`[^`]+`/g, '')
      .replace(/\*\*([^*]+)\*\*/g, '$1').replace(/\*([^*]+)\*/g, '$1')
      .replace(/^#{1,6}\s+/gm, '').replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
      .replace(/^[-*]\s+/gm, '').replace(/^>\s+/gm, '').trim()
    if (!cleanText) return
    const chunks = splitLongText(cleanText)

    if (speechEnabledRef.current) {
      const voiceName = getAzureVoiceForRole(roleKey, DEBATE_AZURE_VOICE)
      const ttsCfg = DEBATE_TTS_CONFIG[roleKey] || { pitch: 1.0, rate: 1.0 }
      speechService.speak(cleanText, voiceName, ttsCfg.rate, ttsCfg.pitch, () => setSpeakingMsgId(null))
      return
    }

    const config = DEBATE_TTS_CONFIG[roleKey] || { pitch: 1.0, rate: 1.0 }

    // 为角色选择不同的语音
    let zhVoices = zhVoicesRef.current
    if (zhVoices.length === 0) {
      try {
        zhVoices = getSortedChineseVoices(synth)
        if (zhVoices.length > 0) zhVoicesRef.current = zhVoices
      } catch {}
    }
    const roleVoice = assignVoiceForRole(roleKey, roleVoiceMapRef.current, zhVoices)

    const speakChunk = (idx: number) => {
      if (idx >= chunks.length) { setSpeakingMsgId(null); return }
      const utterance = new SpeechSynthesisUtterance(chunks[idx])
      utterance.pitch = Math.max(0.5, Math.min(2.0, config.pitch))
      utterance.rate = config.rate
      utterance.lang = 'zh-CN'
      if (roleVoice) utterance.voice = roleVoice
      utterance.onend = () => speakChunk(idx + 1)
      utterance.onerror = () => setSpeakingMsgId(null)
      try { synth.speak(utterance) } catch { setSpeakingMsgId(null) }
    }

    try { synth.resume() } catch {}
    speakChunk(0)
  }, [speakingMsgId])

  // personalityMap 的 ref 版本，供 startStreaming 使用（避免依赖顺序问题）
  const personalityMapRef = useRef<Record<string, string>>({})

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
      personalityTitle: roleKey !== 'HOST' ? personalityMapRef.current[roleKey] : undefined,
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
          m.id === msgId ? { ...m, streaming: false, personalityTitle: roleKey !== 'HOST' ? personalityMap[roleKey] : undefined } : m
        ))

        if (ttsEnabled) {
          setSpeakingMsgId(roleKey + Date.now())
          enqueueTts(currentContentRef.current.trim(), roleKey, -1)
        }

        // 刷新评分
        if (sessionId) {
          getDebateScores(sessionId).then(setScores).catch(() => {})
        }
      } else {
        // 无内容，移除占位
        setMessages(prev => prev.filter(m => m.id !== msgId))
      }
      // 保存最近一次发言内容到 ref，供后续回调链读取（解决闭包陷阱）
      lastStreamedContentRef.current = currentContentRef.current.trim()
      currentMsgIdRef.current = null
      currentContentRef.current = ''
      // 页面已退出/链已停止，不再触发回调链
      if (!discussionLoopRef.current && !isChainActiveRef.current) return
      onDone()
    })

    onError((_: unknown) => {
      setSpeakingKey(null)
      // 移除占位消息
      setMessages(prev => prev.filter(m => m.id !== msgId))
      currentMsgIdRef.current = null
      currentContentRef.current = ''
      // 页面已退出/链已停止，不再触发回调链
      if (!discussionLoopRef.current && !isChainActiveRef.current) {
        toast.error('发音失败')
        return
      }
      toast.error('发音失败')
      onDone()
    })
  }, [ttsEnabled, sessionId, enqueueTts])

  // ==================== 辩论流程控制 ====================

  const speakOpening = useCallback((index: number) => {
    if (!sessionId || index >= OPENING_ORDER.length) {
      handleAdvanceRoundRef.current()
      return
    }

    const roleKey = OPENING_ORDER[index]

    // 第二个辩手开始前，插入主持人串场（预写文本，不用 LLM）
    if (index > 0) {
      const prevName = DEBATE_ROLE_NAMES[OPENING_ORDER[index - 1]]
      const nextName = DEBATE_ROLE_NAMES[roleKey]
      setMessages(prev => [...prev, {
        id: `host-opening-${index}`,
        roleKey: 'HOST', roleName: '主持人', side: 'NEUTRAL', positionKey: 'HOST',
        content: `感谢${prevName}。有请${nextName}开篇立论`,
        timestamp: Date.now(),
      }])
    }

    startStreaming(
      () => streamDebateOpeningSpeech(bookIdNum, roleKey, sessionId, currentRoundRef.current),
      roleKey,
      () => {
        setTimeout(() => speakOpening(index + 1), 500)
      },
    )
  }, [bookIdNum, sessionId, startStreaming])

  // ==================== 交叉质询 (CROSS_EXAM) ====================

  // ==================== 主持人过渡发言 ====================

  const speakHostTransition = useCallback((_fromPhase: string, toPhase: string): Promise<void> => {
    return new Promise<void>(resolve => {
      if (!sessionId) { resolve(); return }
      // 预写过渡串场词——不靠 LLM，杜绝编造
      const toLabel = ROUND_LABELS[toPhase] || toPhase
const nextSpeaker = toPhase === 'CROSS_EXAM' ? '正方二辩' :
                           toPhase === 'REBUTTAL' ? '正方二辩' :
                           toPhase === 'FREE' ? '正方' :
                           toPhase === 'CLOSING' ? '反方四辩' : '双方辩手'
      setMessages(prev => [...prev, {
        id: `host-trans-${toPhase}`,
        roleKey: 'HOST', roleName: '主持人', side: 'NEUTRAL', positionKey: 'HOST',
        content: `下面进入${toLabel}环节。请${nextSpeaker}准备。`,
        timestamp: Date.now(),
      }])
      setTimeout(resolve, 800)
    })
  }, [sessionId])

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
      if (turnIndex >= CROSS_EXAM_ORDER.length || !isChainActiveRef.current) {
        handleAdvanceRoundRef.current()
        return
      }

      const turn = CROSS_EXAM_ORDER[turnIndex]

      // 两对 Q&A 之间插入主持串场（turnIndex=2 是 CON_2 质询 PRO_1 的开头）
      if (turnIndex === 2) {
        setMessages(prev => [...prev, {
          id: `host-crossexam`,
          roleKey: 'HOST', roleName: '主持人', side: 'NEUTRAL', positionKey: 'HOST',
          content: '接下来有请反方二辩质询正方一辩',
          timestamp: Date.now(),
        }])
      }

      const defenderOpening = turn.defenderKey.startsWith('PRO')
        ? pro1Msg?.content || '' : con1Msg?.content || ''

      // 如果是被质询方，优先从 ref 获取最近流式内容（解决闭包陷阱），回退到 messages
      let questionContent = ''
      if (turn.examRole === 'ANSWERER') {
        if (lastStreamedContentRef.current) {
          questionContent = lastStreamedContentRef.current
        } else {
          const prevMsgs = messages.filter(
            m => m.roundType === 'CROSS_EXAM' && m.roundNumber === currentRoundRef.current
          )
          if (prevMsgs.length > 0 && prevMsgs[prevMsgs.length - 1].content.trim()) {
            questionContent = prevMsgs[prevMsgs.length - 1].content
          }
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
    // 优先从 ref 获取最近流式内容（解决闭包陷阱），回退到 messages
    let lastContext = lastStreamedContentRef.current || (freeMsgs.length > 0 ? freeMsgs[freeMsgs.length - 1].content : '')
    let freeCount = freeMsgs.length

    // 自由辩论有最大发言次数限制
    if (freeCount >= MAX_FREE_EXCHANGES) {
      handleAdvanceRoundRef.current()
      return
    }

    try {
      let firstSpeaker: string
      try {
        const res = await getNextDebateSpeaker(sessionId)
        firstSpeaker = typeof res === 'string' ? res : (res as { data?: string })?.data ?? res as string
        // 标准赛制：自由辩论正方先发言 — 如果 LLM 返回反方，强制使用正方
        if (firstSpeaker && !firstSpeaker.startsWith('PRO')) {
          firstSpeaker = 'PRO_1'
        }
      } catch { firstSpeaker = '' }
      if (!firstSpeaker) {
        handleAdvanceRoundRef.current()
        return
      }

      let currentSpeaker = firstSpeaker
      const speakFree = () => {
        if (!currentSpeaker || !sessionId || !isChainActiveRef.current) {
          handleAdvanceRoundRef.current()
          return
        }

        startStreaming(
          () => streamDebateFreeSpeech(bookIdNum, currentSpeaker, sessionId, currentRoundRef.current, lastContext),
          currentSpeaker,
          () => {
            // 从 ref 更新上下文，确保下一位辩手能看到上一位的发言内容
            lastContext = lastStreamedContentRef.current || lastContext
            freeCount++
            getNextDebateSpeaker(sessionId)
              .then(nextRaw => {
                const next = typeof nextRaw === 'string' ? nextRaw : (nextRaw as { data?: string })?.data ?? nextRaw as string
                if (next && freeCount < MAX_FREE_EXCHANGES && isChainActiveRef.current) {
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
        // 先通知后端辩论结束，持久化 COMPLETED 状态
        advanceDebateRound(sessionId).catch(() => {})
        if (!isChainActiveRef.current) return
        // 预写结束语
        setMessages(prev => [...prev, {
          id: `host-wrapup`,
          roleKey: 'HOST', roleName: '主持人', side: 'NEUTRAL', positionKey: 'HOST',
          content: '本场辩论到此结束。感谢双方辩手的精彩表现！',
          timestamp: Date.now(),
        }])
        setPhase('completed')
        setSessionStatus('COMPLETED')
        isChainActiveRef.current = false
        return
      }
      if (!isChainActiveRef.current) return

      const roleKey = CLOSING_ORDER[closeIndex]
      // 反方四辩结束后，主持人串场再请正方四辩
      if (closeIndex === 1) {
        const prevName = DEBATE_ROLE_NAMES[CLOSING_ORDER[0]]
        setMessages(prev => [...prev, {
          id: `host-closing-1`,
          roleKey: 'HOST', roleName: '主持人', side: 'NEUTRAL', positionKey: 'HOST',
          content: `感谢${prevName}。有请${DEBATE_ROLE_NAMES[roleKey]}总结陈词`,
          timestamp: Date.now(),
        }])
      }

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

  // 从 session 数据 + messages 构建 positionKey → personalityTitle 映射
  const personalityMap = useMemo(() => {
    const map: Record<string, string> = {}
    // 1. 优先从 session 的 proRoleKeys/conRoleKeys 构建（进页面立即可用）
    const proKeys = sessionProKeys.split(',').map(k => k.trim()).filter(Boolean)
    for (let i = 0; i < proKeys.length && i < 4; i++) {
      map[`PRO_${i + 1}`] = getPersonalityTitle(proKeys[i])
    }
    const conKeys = sessionConKeys.split(',').map(k => k.trim()).filter(Boolean)
    for (let i = 0; i < conKeys.length && i < 4; i++) {
      map[`CON_${i + 1}`] = getPersonalityTitle(conKeys[i])
    }
    // 2. 再用 messages 的 personalityTitle 覆盖（以实际 DB 数据为准）
    for (const m of messages) {
      if (m.personalityTitle && m.roleKey !== 'HOST') {
        map[m.roleKey] = m.personalityTitle
      }
    }
    personalityMapRef.current = map
    return map
  }, [messages, sessionProKeys, sessionConKeys])

  // 无消息时自动开始辩论（预写开场白，不用 LLM）
  const autoStartTriggered = useRef(false)
  useEffect(() => {
    if (phase !== 'OPENING' || messages.length > 0 || autoStartTriggered.current || !isOwner) return
    if (!sessionId || !bookIdNum) return
    autoStartTriggered.current = true
    const timer = setTimeout(() => {
      isChainActiveRef.current = true
      // 预写主持人开场——绝不编造观点
      const introText = bookTitle
        ? `欢迎来到奇葩说辩论。今天讨论的书籍是《${bookTitle}》。有请正方一辩开篇立论。`
        : `欢迎来到奇葩说辩论。有请正方一辩开篇立论。`
      setMessages(prev => [...prev, {
        id: `host-intro`,
        roleKey: 'HOST', roleName: '主持人', side: 'NEUTRAL', positionKey: 'HOST',
        content: introText, timestamp: Date.now(),
      }])
      setTimeout(() => speakOpening(0), 600)
    }, 600)
    return () => clearTimeout(timer)
  }, [phase, messages.length, sessionId, bookIdNum, speakOpening, bookTitle, isOwner])

  // 始终同步最新版到 ref，供内部闭包使用（避开 useCallback 闭包过期）
  handleAdvanceRoundRef.current = handleAdvanceRound
  currentRoundRef.current = currentRound
  currentPhaseRef.current = currentPhase

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
      } else {
        handleAdvanceRoundRef.current()
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
    speechService.stop()
    stopTts()
    setSpeakingKey(null)
    // 清理残留的流式占位消息
    setMessages(prev => prev.map(m =>
      m.streaming ? { ...m, streaming: false } : m
    ))
  }, [stopTts])

  const pauseDiscussion = useCallback(() => {
    handleStop()
  }, [handleStop])

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
        <Loader2 className="h-6 w-6 animate-spin text-brand-500" />
      </div>
    )
  }

  return (
    <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col overflow-hidden bg-background">
      {/* 顶部导航 — 整合角色状态 + 环节标签 */}
      <header className="shrink-0 flex items-center gap-3 border-b border-border/30 bg-navbar/95 px-4 py-2.5 backdrop-blur-xl z-20">
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
          <div className="flex items-center gap-2 mt-0.5">
            <p className="text-xs text-muted-foreground truncate flex items-center gap-1.5">
              {sessionStatus === 'COMPLETED' ? (
                <>
                  <span className="inline-block h-1.5 w-1.5 rounded-full bg-brand-500" />
                  已完成
                </>
              ) : (
                <>
                  <span className="inline-block h-1.5 w-1.5 rounded-full bg-brand-400 animate-pulse" />
                  进行中
                </>
              )}
            </p>
            {/* 极简角色状态条 */}
            <div className="hidden md:flex">
              <RoleStatusBar
                speakingKey={speakingKey}
                allRoleKeys={allRoleKeys.current}
                personalityMap={personalityMap}
              />
            </div>
          </div>
        </div>
        {/* 环节标签页 */}
        <div className="hidden md:flex items-center gap-1 bg-background rounded-lg p-0.5">
          {ROUND_SEQUENCE.map((r) => (
            <button
              key={r}
              onClick={() => {
                // 点击跳转到该环节第一条消息
                const firstMsg = messages.find(m => m.roundType === r)
                if (firstMsg && !isMobile) {
                  const cols = scrollContainerRef.current?.querySelectorAll('.debate-column')
                  cols?.forEach(col => {
                    const el = col.querySelector(`[data-msg-id*="${firstMsg.id}"]`)
                    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
                  })
                }
              }}
              className={`px-2 py-1 rounded-md text-[11px] font-medium transition-all ${
                sessionStatus === 'COMPLETED'
                  ? 'text-brand-500'
                  : currentPhase === r
                  ? 'bg-background text-brand-500 shadow-sm'
                  : 'text-muted-foreground/50 hover:text-muted-foreground'
              }`}
            >
              {ROUND_LABELS[r]}
            </button>
          ))}
          <div className={`px-2 py-1 rounded-md text-[11px] font-medium transition-all ${
            sessionStatus === 'COMPLETED' ? 'bg-background text-brand-500 shadow-sm' : 'text-muted-foreground/30'
          }`}>
            结束
          </div>
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden relative" ref={mainLayoutRef}>
        <div className="flex flex-1 flex-col overflow-hidden relative min-w-0">
          {/* 移动端环节指示器 — 简化版 */}
          <div className="md:hidden shrink-0 flex items-center justify-center gap-0.5 px-2 py-1.5 border-b border-border/10">
            {ROUND_SEQUENCE.map((r, i) => (
              <div key={r} className="flex items-center gap-0.5">
                <div className={`flex items-center gap-0.5 ${
                  sessionStatus === 'COMPLETED' ? 'text-brand-500' :
                  currentPhase === r ? 'text-brand-500 font-bold' : 'text-muted-foreground/50'
                }`}>
                  <div className={`h-1.5 w-1.5 rounded-full ${
                    sessionStatus === 'COMPLETED' ? 'bg-brand-500' :
                    currentPhase === r ? 'bg-brand-500' : 'bg-muted'
                  }`} />
                  <span className="text-[10px]">{ROUND_LABELS[r]}</span>
                </div>
                {i < ROUND_SEQUENCE.length - 1 && (
                  <div className="h-px w-1 bg-border/30" />
                )}
              </div>
            ))}
          </div>

          {/* 消息列表 */}
          <div ref={scrollContainerRef} className={`flex-1 ${isMobile ? 'overflow-y-auto overscroll-y-contain' : 'min-h-0 flex flex-col'}`}>
            {!isStarted ? (
              <div className="flex flex-1 flex-col items-center justify-center gap-4 px-4 py-12">
                <Loader2 className="h-8 w-8 animate-spin text-brand-400" />
                <p className="text-sm text-muted-foreground">辩论即将开始...</p>
              </div>
            ) : (
              <>
                {/* PC端双栏对抗 — 正方左 / 反方右 / 主持人全宽穿插 */}
                {!isMobile && (
                  <div className="flex-1 min-h-0 flex overflow-hidden">
                    {/* 正方区（左） */}
                    <div onScroll={handleScroll} className="debate-column flex-1 min-w-0 border-r border-border/10 p-3 space-y-3 overflow-y-auto">
                      <div className="flex items-center justify-between mb-2">
                        <div className="flex items-center gap-1.5">
                          <div className="h-3 w-3 rounded-full bg-[#4A7C6F]" />
                          <span className="text-xs font-bold text-[#4A7C6F] uppercase tracking-wider">正方</span>
                        </div>
                        <span className="text-[10px] text-muted-foreground/50">
                          {messages.filter(m => m.side === 'PRO').length} 条发言
                        </span>
                      </div>
                      {messages.filter(m => m.side === 'PRO').map(m => {
                        const isActive = speakingKey === m.roleKey && m.streaming
                        return (
                          <div
                            key={m.id}
                            data-msg-id={m.id}
                            className={`rounded-xl border p-3 group transition-all duration-300 ${
                              isActive
                                ? 'bg-card border-teal-400 dark:border-teal-500 shadow-sm'
                                : 'bg-card border-border/50 hover:border-teal-300 dark:hover:border-teal-700'
                            }`}
                            style={isActive ? { borderLeftWidth: '3px', borderLeftColor: '#4A7C6F' } : undefined}
                          >
                            <div className="flex items-center gap-1.5 mb-1.5">
                              <div
                                className="flex h-6 w-6 items-center justify-center rounded-full text-xs"
                                style={{
                                  backgroundColor: hexToRgba(DEBATE_ROLE_COLORS[m.roleKey] || '#4A7C6F', 0.12),
                                  border: `1.5px solid ${DEBATE_ROLE_COLORS[m.roleKey] || '#4A7C6F'}`,
                                }}
                              >
                                {DEBATE_ROLE_ICONS[m.roleKey] || '👤'}
                              </div>
                              <span className="text-xs font-bold" style={{ color: DEBATE_ROLE_COLORS[m.roleKey] }}>
                                {m.roleName}
                              </span>
                              {m.personalityTitle && m.roleKey !== 'HOST' && !m.streaming && (
                                <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-muted text-muted-foreground">
                                  {m.personalityTitle}
                                </span>
                              )}
                              {isActive && (
                                <span className="flex items-center gap-[2px] ml-auto">
                                  <Mic className="h-3 w-3 text-teal-400" />
                                  <span className="flex items-end gap-[1px] h-2">
                                    {[0, 1, 2].map(i => (
                                      <span key={i} className="w-[2px] rounded-full bg-teal-400 animate-pulse" style={{ animationDelay: `${i * 120}ms`, height: `${3 + (i % 2) * 3}px` }} />
                                    ))}
                                  </span>
                                </span>
                              )}
                            </div>
                            {m.streaming && !m.content ? (
                              <span className="flex items-center gap-1.5 text-xs text-teal-400">
                                <Loader2 className="h-3 w-3 animate-spin" />
                                麦克风传递中...
                              </span>
                            ) : (
                              <MarkdownRenderer content={m.content} className="!text-detail !leading-relaxed" />
                            )}
                            {m.streaming && m.content && (
                              <span className="inline-flex items-center ml-1">
                                <span className="h-3 w-[2px] bg-teal-400 animate-pulse rounded-full" />
                              </span>
                            )}
                            {!m.streaming && m.content && (
                              <button
                                onClick={() => handleToggleSpeak(m.id, m.content, m.roleKey)}
                                className={`mt-1.5 flex items-center gap-1 rounded-lg px-2 py-1 text-xs transition-all duration-200 opacity-0 group-hover:opacity-100 ${
                                  speakingMsgId === m.id
                                    ? 'text-primary bg-primary/10 opacity-100'
                                    : 'text-muted-foreground/60 hover:text-muted-foreground'
                                }`}
                              >
                                {speakingMsgId === m.id ? <><Square className="h-2.5 w-2.5 fill-current" />停止</> : <><Volume2 className="h-2.5 w-2.5" />朗读</>}
                              </button>
                            )}
                          </div>
                        )
                      })}
                    </div>

                    {/* 反方区（右） */}
                    <div onScroll={handleScroll} className="debate-column flex-1 min-w-0 border-l border-border/10 p-3 space-y-3 overflow-y-auto">
                      <div className="flex items-center justify-between mb-2">
                        <div className="flex items-center gap-1.5">
                          <div className="h-3 w-3 rounded-full bg-[#B8704A]" />
                          <span className="text-xs font-bold text-[#B8704A] uppercase tracking-wider">反方</span>
                        </div>
                        <span className="text-[10px] text-muted-foreground/50">
                          {messages.filter(m => m.side === 'CON').length} 条发言
                        </span>
                      </div>
                      {messages.filter(m => m.side === 'CON').map(m => {
                        const isActive = speakingKey === m.roleKey && m.streaming
                        return (
                          <div
                            key={m.id}
                            data-msg-id={m.id}
                            className={`rounded-xl border p-3 group transition-all duration-300 ${
                              isActive
                                ? 'bg-card border-amber-400 dark:border-amber-500 shadow-sm'
                                : 'bg-card border-border/50 hover:border-amber-300 dark:hover:border-amber-700'
                            }`}
                            style={isActive ? { borderRightWidth: '3px', borderRightColor: '#B8704A' } : undefined}
                          >
                            <div className="flex items-center gap-1.5 mb-1.5">
                              <div
                                className="flex h-6 w-6 items-center justify-center rounded-full text-xs"
                                style={{
                                  backgroundColor: hexToRgba(DEBATE_ROLE_COLORS[m.roleKey] || '#B8704A', 0.12),
                                  border: `1.5px solid ${DEBATE_ROLE_COLORS[m.roleKey] || '#B8704A'}`,
                                }}
                              >
                                {DEBATE_ROLE_ICONS[m.roleKey] || '👤'}
                              </div>
                              <span className="text-xs font-bold" style={{ color: DEBATE_ROLE_COLORS[m.roleKey] }}>
                                {m.roleName}
                              </span>
                              {m.personalityTitle && m.roleKey !== 'HOST' && !m.streaming && (
                                <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-muted text-muted-foreground">
                                  {m.personalityTitle}
                                </span>
                              )}
                              {isActive && (
                                <span className="flex items-center gap-[2px] ml-auto">
                                  <Mic className="h-3 w-3 text-amber-400" />
                                  <span className="flex items-end gap-[1px] h-2">
                                    {[0, 1, 2].map(i => (
                                      <span key={i} className="w-[2px] rounded-full bg-amber-400 animate-pulse" style={{ animationDelay: `${i * 120}ms`, height: `${3 + (i % 2) * 3}px` }} />
                                    ))}
                                  </span>
                                </span>
                              )}
                            </div>
                            {m.streaming && !m.content ? (
                              <span className="flex items-center gap-1.5 text-xs text-amber-400">
                                <Loader2 className="h-3 w-3 animate-spin" />
                                麦克风传递中...
                              </span>
                            ) : (
                              <MarkdownRenderer content={m.content} className="!text-detail !leading-relaxed" />
                            )}
                            {m.streaming && m.content && (
                              <span className="inline-flex items-center ml-1">
                                <span className="h-3 w-[2px] bg-amber-400 animate-pulse rounded-full" />
                              </span>
                            )}
                            {!m.streaming && m.content && (
                              <button
                                onClick={() => handleToggleSpeak(m.id, m.content, m.roleKey)}
                                className={`mt-1.5 flex items-center gap-1 rounded-lg px-2 py-1 text-xs transition-all duration-200 opacity-0 group-hover:opacity-100 ${
                                  speakingMsgId === m.id
                                    ? 'text-primary bg-primary/10 opacity-100'
                                    : 'text-muted-foreground/60 hover:text-muted-foreground'
                                }`}
                              >
                                {speakingMsgId === m.id ? <><Square className="h-2.5 w-2.5 fill-current" />停止</> : <><Volume2 className="h-2.5 w-2.5" />朗读</>}
                              </button>
                            )}
                          </div>
                        )
                      })}
                    </div>
                  </div>
                )}

                {/* 移动端上下排列 — 主持人全宽穿插 */}
                {isMobile && (
                  <div onScroll={handleScroll} className="p-3 space-y-3">
                    {messages.map(m => {
                      const isPro = m.side === 'PRO'
                      const isCon = m.side === 'CON'
                      const isHost = m.side === 'NEUTRAL'
                      const color = DEBATE_ROLE_COLORS[m.roleKey] || '#888'

                      if (isHost) {
                        return (
                          <div key={m.id} className="flex items-center justify-center py-2">
                            <div className="flex items-center gap-2 px-4 py-2 rounded-full bg-muted border border-border/50">
                              <span className="text-xs">🎙️</span>
                              <span className="text-xs text-muted-foreground">{m.content}</span>
                            </div>
                          </div>
                        )
                      }

                      return (
                        <div key={m.id} className={`rounded-xl border p-3 group transition-all duration-300 bg-card border-border/50 hover:border-border`}
                          style={isPro ? { borderLeft: `3px solid #4A7C6F` } : { borderRight: `3px solid #B8704A` }}
                        >
                          <div className={`flex items-center gap-1.5 mb-1.5 ${isCon ? 'flex-row-reverse' : ''}`}>
                            <div
                              className="flex h-5 w-5 items-center justify-center rounded-full text-[10px]"
                              style={{
                                backgroundColor: hexToRgba(color, 0.12),
                                border: `1px solid ${color}`,
                              }}
                            >
                              {DEBATE_ROLE_ICONS[m.roleKey] || '👤'}
                            </div>
                            <span className="font-bold" style={{ color, fontSize: '11px' }}>
                              {m.roleName}
                            </span>
                            {m.personalityTitle && m.roleKey !== 'HOST' && !m.streaming && (
                              <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-muted text-muted-foreground">
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
                            <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
                              <Loader2 className="h-3 w-3 animate-spin" />
                              麦克风传递中...
                            </span>
                          ) : (
                            <MarkdownRenderer content={m.content} className="!text-detail !leading-relaxed" />
                          )}
                          {m.streaming && m.content && (
                            <span className="inline-flex items-center ml-1">
                              <span className="h-3 w-[2px] bg-foreground/30 animate-pulse rounded-full" />
                            </span>
                          )}
                          {!m.streaming && m.content && (
                            <button
                              onClick={() => handleToggleSpeak(m.id, m.content, m.roleKey)}
                              className={`mt-1.5 flex items-center gap-1 rounded-lg px-2 py-1 text-xs transition-all duration-200 opacity-0 group-hover:opacity-100 ${
                                speakingMsgId === m.id
                                  ? 'text-primary bg-primary/10 opacity-100'
                                  : 'text-muted-foreground/60 hover:text-muted-foreground'
                              }`}
                            >
                              {speakingMsgId === m.id ? <><Square className="h-2.5 w-2.5 fill-current" />停止</> : <><Volume2 className="h-2.5 w-2.5" />朗读</>}
                            </button>
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

          {/* 底部控制栏 — 1+1 原则：主操作唯一 + 次要操作折叠 */}
          <div
            className="shrink-0 border-t border-border/20 bg-navbar/95 backdrop-blur-xl px-4 py-2.5"
            style={{ paddingBottom: 'calc(env(safe-area-inset-bottom, 0px) + 0.75rem)' }}
          >
            <div className="flex items-center gap-2 max-w-3xl mx-auto">
              {/* 主操作区 */}
              <div className="flex items-center gap-2">
                {/* 语音开关 */}
                <button
                  onClick={() => {
                    if (ttsEnabled) {
                      stopTts()
                      setTtsEnabled(false)
                    } else {
                      setTtsEnabled(true)
                    }
                  }}
                  className={`flex items-center justify-center gap-1.5 rounded-xl px-3 py-2 text-xs font-medium transition-all duration-200 ${
                    ttsEnabled
                      ? 'bg-brand-100 text-brand-500 border border-brand-200'
                      : 'bg-muted text-muted-foreground hover:text-foreground'
                  }`}
                  title={ttsEnabled ? '关闭语音' : '开启语音'}
                >
                  <Volume2 className="h-3.5 w-3.5 shrink-0" />
                  <span className="hidden sm:inline">{ttsEnabled ? '朗读中' : '语音'}</span>
                </button>

                {/* 暂停 / 继续 — 互斥显示，主操作（仅主人可见） */}
                {isOwner && phase !== 'completed' && (speakingKey || isChainActiveRef.current) ? (
                  <button
                    onClick={pauseDiscussion}
                    className="flex items-center justify-center gap-1.5 rounded-xl px-4 py-2 text-xs font-semibold bg-amber-500/10 text-amber-600 hover:bg-amber-500/20 border border-amber-200/50 transition-colors"
                  >
                    <Pause className="h-3.5 w-3.5 shrink-0" />
                    <span>暂停辩论</span>
                  </button>
                ) : isOwner && isStarted && sessionStatus !== 'COMPLETED' ? (
                  <button
                    onClick={handleContinue}
                    className="flex items-center justify-center gap-1.5 rounded-xl px-4 py-2 text-xs font-semibold bg-gradient-to-r from-brand-400 to-brand-500 text-white shadow-md shadow-brand-400/20 active:scale-[0.97] transition-transform"
                  >
                    <Play className="h-3.5 w-3.5 shrink-0" />
                    <span>继续辩论</span>
                  </button>
                ) : !isOwner && sessionStatus !== 'COMPLETED' ? (
                  <span className="text-xs text-muted-foreground italic">观摩模式</span>
                ) : null}
              </div>

              <div className="flex-1" />

              {/* 次要操作：评分 + 报告 + 统计 */}
              <div className="flex items-center gap-1">
                {isStarted && (
                  <>
                    <button
                      onClick={() => {
                        if (showScorePanel) { setShowScorePanel(false); return }
                        if (sessionId && scores.length === 0) {
                          getDebateScores(sessionId).then(setScores).catch(() => {})
                        }
                        setShowScorePanel(true)
                      }}
                      className={`flex items-center justify-center gap-1 rounded-xl px-2.5 py-2 text-xs transition-colors ${
                        showScorePanel ? 'bg-brand-100 text-brand-500' : 'bg-muted text-muted-foreground hover:text-foreground'
                      }`}
                      title="评分面板"
                    >
                      <BarChart3 className="h-3.5 w-3.5 shrink-0" />
                      <span className="hidden sm:inline">评分</span>
                    </button>

                    {sessionStatus === 'COMPLETED' && (
                      <button
                        onClick={() => {
                          if (showReportPanel) { setShowReportPanel(false); return }
                          setShowReportPanel(true)
                          if (!report && sessionId) {
                            getDebateReport(sessionId).then(setReport).catch(() => {})
                          }
                        }}
                        className={`flex items-center justify-center gap-1 rounded-xl px-2.5 py-2 text-xs transition-colors ${
                          showReportPanel ? 'bg-brand-100 text-brand-500' : 'bg-muted text-muted-foreground hover:text-foreground'
                        }`}
                        title="辩论报告"
                      >
                        {reportGenerating || report?.status === 'GENERATING' ? (
                          <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin" />
                        ) : (
                          <FileText className="h-3.5 w-3.5 shrink-0" />
                        )}
                        <span className="hidden sm:inline">报告</span>
                      </button>
                    )}
                  </>
                )}

                <span className="text-xs text-muted-foreground/60 shrink-0 tabular-nums ml-1">
                  {currentRound}轮
                </span>
              </div>
            </div>
          </div>
        </div>

        {/* PC端评分/报告面板 — 智能布局：宽屏并排，窄屏覆盖 */}
        {!isMobile && (
          <>
            {/* 评分面板：宽屏并排，窄屏覆盖 */}
            {useOverlay && showScorePanel && (
              <div className="absolute inset-0 z-30 flex justify-end bg-black/20" onClick={() => setShowScorePanel(false)}>
                <div className="h-full w-80 border-l border-border/20 bg-navbar/95 backdrop-blur-xl shadow-xl" onClick={e => e.stopPropagation()}>
                  <ScorePanel scores={scores} onClose={() => setShowScorePanel(false)} onRefresh={() => sessionId ? getDebateScores(sessionId).then(setScores) : null} isMobile={isMobile} />
                </div>
              </div>
            )}
            {!useOverlay && (
              <div className={`shrink-0 overflow-hidden transition-all duration-300 ease-out flex flex-col border-l border-border/20 bg-navbar/95 backdrop-blur-xl ${showScorePanel ? 'w-80' : 'w-0'}`}>
                {showScorePanel && (
                  <ScorePanel scores={scores} onClose={() => setShowScorePanel(false)} onRefresh={() => sessionId ? getDebateScores(sessionId).then(setScores) : null} isMobile={isMobile} />
                )}
              </div>
            )}
            {/* 报告面板：始终覆盖 */}
            {showReportPanel && (
              <div className="absolute inset-0 z-30 flex justify-end bg-black/20" onClick={() => setShowReportPanel(false)}>
                <div className="h-full w-[420px] max-w-[80vw] border-l border-border/20 bg-navbar/95 backdrop-blur-xl shadow-xl" onClick={e => e.stopPropagation()}>
                  <ReportPanel
                    report={report}
                    isGenerating={reportGenerating}
                    isOwner={isOwner}
                    onTrigger={handleTriggerReport}
                    onClose={() => setShowReportPanel(false)}
                  />
                </div>
              </div>
            )}
          </>
        )}
      </div>

      {/* 移动端评分/报告面板 — 底部 Sheet */}
      {isMobile && showScorePanel && (
        <Sheet open={showScorePanel} onOpenChange={(v) => !v && setShowScorePanel(false)}>
          <SheetContent side="bottom" className="rounded-t-2xl p-0 max-h-[85vh] [&>button]:hidden">
            <ScorePanel scores={scores} onClose={() => setShowScorePanel(false)} onRefresh={() => sessionId ? getDebateScores(sessionId).then(setScores) : null} isMobile={isMobile} />
          </SheetContent>
        </Sheet>
      )}
      {isMobile && showReportPanel && (
        <Sheet open={showReportPanel} onOpenChange={(v) => !v && setShowReportPanel(false)}>
          <SheetContent side="bottom" className="rounded-t-2xl p-0 max-h-[85vh] [&>button]:hidden">
            <ReportPanel
              report={report}
              isGenerating={reportGenerating}
              isOwner={isOwner}
              onTrigger={handleTriggerReport}
              onClose={() => setShowReportPanel(false)}
            />
          </SheetContent>
        </Sheet>
      )}
    </div>
  )
}
