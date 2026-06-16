import { useState, useEffect, useCallback, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  ArrowLeft, Sparkles, MessageSquare, Swords, Trash2, Loader2, History, X,
} from 'lucide-react'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import {
  getDebateTopics, createDebateSession, getDebateSessions, deleteDebateSession,
  optimizeDebateTopic,
} from '@/api/debate'
import { getBook } from '@/api/book'
import type { DebateTopic, DebateSession } from '@/types/debate'
import {
  DEBATE_PERSONALITY_COLORS, DEBATE_PERSONALITY_TITLES, DEBATE_PERSONALITY_ICONS,
} from '@/types/debate'
import type { Book } from '@/types/book'
import { toast } from 'sonner'
import { useIsMobile } from '@/hooks/use-mobile'

const CACHE_PREFIX = 'kbook:debate:topics:'
const CACHE_TTL = 24 * 60 * 60 * 1000 // 24h

/** 16 种纯辩论性格（无正反方区分，可重复选择） */
const PERSONALITY_OPTIONS = [
  { key: 'LOGICAL', label: '逻辑严谨型', desc: '冷静理性·层层推演' },
  { key: 'SHARP', label: '犀利毒舌型', desc: '一针见血·言辞犀利' },
  { key: 'HUMOROUS', label: '机智幽默型', desc: '妙语连珠·举重若轻' },
  { key: 'EMPATHETIC', label: '共情升华型', desc: '感同身受·升华主题' },
  { key: 'SENSITIVE', label: '感性温情型', desc: '情感丰富·以情动人' },
  { key: 'DOMINEERING', label: '强势暴躁型', desc: '气势凌人·言辞激烈' },
  { key: 'ERUDITE', label: '博学引经型', desc: '旁征博引·以理服人' },
  { key: 'PRACTICAL', label: '务实接地气型', desc: '脚踏实地·生活智慧' },
  { key: 'SKEPTICAL', label: '质疑批判型', desc: '打破砂锅·刨根问底' },
  { key: 'PASSIONATE', label: '慷慨激昂型', desc: '热血沸腾·振臂高呼' },
  { key: 'WITTY', label: '机敏风趣型', desc: '金句频出·妙趣横生' },
  { key: 'DEEP', label: '哲学思辨型', desc: '由表及里·层层追问' },
  { key: 'STORYTELLER', label: '叙事故事型', desc: '以小见大·娓娓道来' },
  { key: 'ANALYTICAL', label: '数据分析型', desc: '数字说话·证据为凭' },
  { key: 'DIPLOMATIC', label: '圆融调和型', desc: '兼容并包·化解冲突' },
  { key: 'REBEL', label: '反叛颠覆型', desc: '另辟蹊径·打破常规' },
] as const

function hexToRgba(hex: string, alpha: number) {
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const b = parseInt(hex.slice(5, 7), 16)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

function SessionCard({ session, bookId, onDelete }: {
  session: DebateSession
  bookId: number
  onDelete: (id: string) => void
}) {
  const navigate = useNavigate()
  const dateStr = new Date(session.createdAt).toLocaleDateString('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
  return (
    <div
      onClick={() => navigate(`/book/${bookId}/debate/sessions/${session.sessionId}`)}
      className="rounded-2xl border border-border/50 bg-card p-3 hover:border-border/60 transition-colors cursor-pointer"
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0 flex-1">
          <p className="text-xs font-bold truncate">{session.topic}</p>
          <p className="text-xs text-muted-foreground mt-0.5">
            {session.status === 'COMPLETED' ? '已完成' : '进行中'}
            {' · '}第{session.currentRound}轮
            {' · '}{dateStr}
          </p>
        </div>
        <button
          onClick={(e) => { e.stopPropagation(); onDelete(session.sessionId) }}
          className="rounded-lg p-1 text-muted-foreground/50 hover:text-red-500 hover:bg-red-50 transition-colors shrink-0"
        >
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>
  )
}

/** 单人位性格选择器 — 点击按钮打开Modal选择 */
function SlotSelector({
  slotIndex,
  selectedKey,
  side,
  onToggle,
}: {
  slotIndex: number
  selectedKey: string | null
  side: 'PRO' | 'CON'
  onToggle: (index: number) => void
}) {
  const color = DEBATE_PERSONALITY_COLORS[selectedKey || ''] || (side === 'PRO' ? '#3B82F6' : '#EF4444')
  const label = side === 'PRO' ? '正' : '反'
  const slotLabels = ['一辩', '二辩', '三辩', '四辩']

  return (
    <button
      onClick={() => onToggle(slotIndex)}
      className="w-full rounded-xl bg-muted px-2 py-2 text-center transition-all hover:bg-muted/80 cursor-pointer"
    >
      <div className="flex flex-col items-center gap-0.5">
        {selectedKey ? (
          <>
            <div
              className="flex h-7 w-7 items-center justify-center rounded-full text-xs"
              style={{
                backgroundColor: hexToRgba(color, 0.12),
              }}
            >
              {DEBATE_PERSONALITY_ICONS[selectedKey] || '👤'}
            </div>
            <span className="text-xs font-semibold leading-tight" style={{ color }}>
              {label}{slotLabels[slotIndex]}
            </span>
            <span className="text-xs text-muted-foreground leading-tight">
              {DEBATE_PERSONALITY_TITLES[selectedKey]}
            </span>
          </>
        ) : (
          <>
            <div
              className="flex h-7 w-7 items-center justify-center rounded-full text-xs"
              style={{ backgroundColor: hexToRgba(color, 0.08) }}
            >
              <span style={{ color: hexToRgba(color, 0.35) }}>?</span>
            </div>
            <span className="text-xs font-semibold" style={{ color: hexToRgba(color, 0.45) }}>
              {label}{slotLabels[slotIndex]}
            </span>
          </>
        )}
      </div>
    </button>
  )
}

export default function DebatePage() {
  const { bookId } = useParams<{ bookId: string }>()
  const navigate = useNavigate()
  const bookIdNum = Number(bookId)
  const isMobile = useIsMobile()

  const [book, setBook] = useState<Book | null>(null)
  const [topics, setTopics] = useState<DebateTopic[]>([])
  const [sessions, setSessions] = useState<DebateSession[]>([])
  const [loadingTopics, setLoadingTopics] = useState(false)
  const [selectedTopic, setSelectedTopic] = useState<DebateTopic | null>(null)
  const [showManualInput, setShowManualInput] = useState(false)
  const [manualTopic, setManualTopic] = useState('')
  const [manualProArg, setManualProArg] = useState('')
  const [manualConArg, setManualConArg] = useState('')
  const [creating, setCreating] = useState(false)
  const [optimizing, setOptimizing] = useState(false)
  const [proRoleKeys, setProRoleKeys] = useState<string[]>(['LOGICAL', 'SHARP', 'HUMOROUS', 'EMPATHETIC'])
  const [conRoleKeys, setConRoleKeys] = useState<string[]>(['SENSITIVE', 'DOMINEERING', 'ERUDITE', 'PRACTICAL'])
  const [expandedSlot, setExpandedSlot] = useState<{ side: 'PRO' | 'CON'; index: number } | null>(null)
  const cacheKey = `${CACHE_PREFIX}${bookIdNum}`
  const loadedRef = useRef(false)

  useEffect(() => {
    if (!bookIdNum) return
    getBook(bookIdNum).then(setBook).catch(() => {})

    // 获取历史会话
    getDebateSessions(bookIdNum)
      .then(setSessions)
      .catch(() => {})
  }, [bookIdNum])

  // 辩题加载（含缓存）
  useEffect(() => {
    if (!bookIdNum || loadedRef.current) return
    loadedRef.current = true

    // 尝试从 localStorage 读取缓存
    try {
      const cached = localStorage.getItem(cacheKey)
      if (cached) {
        const parsed = JSON.parse(cached)
        if (parsed && parsed.expiry > Date.now() && Array.isArray(parsed.data)) {
          setTopics(parsed.data)
          return
        }
      }
    } catch { /* ignore */ }

    // 缓存未命中，从后端加载
    setLoadingTopics(true)
    getDebateTopics(bookIdNum)
      .then(data => {
        setTopics(data)
        try {
          localStorage.setItem(cacheKey, JSON.stringify({
            data,
            expiry: Date.now() + CACHE_TTL,
          }))
        } catch { /* ignore */ }
      })
      .catch(() => {})
      .finally(() => setLoadingTopics(false))
  }, [bookIdNum, cacheKey])


  const handleSlotToggle = useCallback((side: 'PRO' | 'CON', index: number) => {
    setExpandedSlot(prev =>
      prev?.side === side && prev.index === index ? null : { side, index }
    )
  }, [])

  const handleSlotSelect = useCallback((side: 'PRO' | 'CON', index: number, key: string) => {
    if (side === 'PRO') {
      setProRoleKeys(prev => {
        const next = [...prev]
        next[index] = key
        return next
      })
    } else {
      setConRoleKeys(prev => {
        const next = [...prev]
        next[index] = key
        return next
      })
    }
  }, [])

  const handleCreateSession = useCallback(async () => {
    const topic = selectedTopic?.topic || manualTopic.trim()
    if (!topic) {
      toast.error('请选择一个辩题或手动输入')
      return
    }

    // 手动输入校验
    if (!selectedTopic && showManualInput) {
      if (!manualProArg.trim() || !manualConArg.trim()) {
        toast.error('请填写正反双方的辩题内容')
        return
      }
    }

    if (proRoleKeys.length !== 4 || conRoleKeys.length !== 4) {
      toast.error('每方请各选4名辩手')
      return
    }

    setCreating(true)
    try {
      const bookContext = selectedTopic
        ? `正方：${selectedTopic.proArgument}\n反方：${selectedTopic.conArgument}`
        : `正方：${manualProArg.trim()}\n反方：${manualConArg.trim()}`

      const session = await createDebateSession(
        bookIdNum,
        topic,
        selectedTopic?.source || 'USER',
        bookContext,
        proRoleKeys.join(','),
        conRoleKeys.join(','),
      )
      navigate(`/book/${bookIdNum}/debate/sessions/${session.sessionId}`)
    } catch (e) {
      toast.error('创建辩论失败')
    } finally {
      setCreating(false)
    }
  }, [bookIdNum, selectedTopic, manualTopic, manualProArg, manualConArg, proRoleKeys, conRoleKeys, showManualInput, navigate])

  const handleOptimizeTopic = useCallback(async () => {
    if (!manualTopic.trim()) {
      toast.error('请先输入辩题')
      return
    }
    setOptimizing(true)
    try {
      const result = await optimizeDebateTopic(bookIdNum, manualTopic, manualProArg, manualConArg)
      setManualTopic(result.topic)
      if (result.proArgument) setManualProArg(result.proArgument)
      if (result.conArgument) setManualConArg(result.conArgument)
      toast.success('辩题已优化')
    } catch {
      toast.error('辩题优化失败')
    } finally {
      setOptimizing(false)
    }
  }, [bookIdNum, manualTopic, manualProArg, manualConArg])

  const handleDeleteSession = useCallback(async (sessionId: string) => {
    try {
      await deleteDebateSession(sessionId)
      setSessions(prev => prev.filter(s => s.sessionId !== sessionId))
      toast.success('已删除')
    } catch {
      toast.error('删除失败')
    }
  }, [])

  const renderSlotGroup = (side: 'PRO' | 'CON', keys: string[]) => {
    return (
      <div className="grid grid-cols-4 gap-2">
        {keys.map((key, i) => (
          <div key={`${side}-${i}`}>
            <SlotSelector
              slotIndex={i}
              selectedKey={key}
              side={side}
              onToggle={(index) => handleSlotToggle(side, index)}
            />
          </div>
        ))}
      </div>
    )
  }

  return (
    <div className="flex h-dvh flex-col bg-gradient-to-b from-background to-muted/30">
      {/* 顶部导航 */}
      <header className="shrink-0 flex items-center gap-3 border-b border-border/20 bg-background/80 backdrop-blur-xl px-4 py-3">
        <button onClick={() => navigate(-1)} className="rounded-xl p-1.5 hover:bg-muted transition-colors">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <div className="flex-1 min-w-0">
          <h1 className="text-sm font-bold">奇葩说</h1>
          {book && <p className="text-xs text-muted-foreground truncate">来自《{book.title}》</p>}
        </div>
        {/* 历史记录入口 */}
        {sessions.length > 0 && (
          <button
            onClick={() => {
              const el = document.getElementById('history-section')
              el?.scrollIntoView({ behavior: 'smooth' })
            }}
            className="flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-muted-foreground hover:text-foreground hover:bg-muted transition-colors"
          >
            <History className="h-3 w-3" />
            <span>{sessions.length}场历史</span>
          </button>
        )}
      </header>

      <div className="flex-1 overflow-y-auto overscroll-contain">
        {/* 辩题选择区 — 核心任务前置 */}
        <section className="px-4 pt-5 pb-3">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-sm font-bold flex items-center gap-2">
              <MessageSquare className="h-4 w-4 text-brand-500" />
              选择辩题
            </h2>
            <span className="text-xs text-muted-foreground">{topics.length} 个推荐</span>
          </div>

          {/* 辩题列表：PC纵向紧凑列表 + 移动端横向滑动卡片 */}
          {loadingTopics ? (
            <div className="flex items-center gap-2 text-xs text-muted-foreground py-4">
              <Loader2 className="h-4 w-4 animate-spin" />
              AI 正在从书中提炼辩题...
            </div>
          ) : (
            <>
              {/* PC端：双列网格 */}
              <div className="hidden md:grid md:grid-cols-2 gap-3">
                {topics.map((t, i) => (
                  <button
                    key={i}
                    onClick={() => { setSelectedTopic(t); setShowManualInput(false); setManualTopic('') }}
                    className={`text-left rounded-xl border p-3 transition-all ${
                      selectedTopic?.topic === t.topic
                        ? 'bg-primary text-primary-foreground border-primary'
                        : 'border-border/50 bg-card text-foreground hover:bg-muted'
                    }`}
                  >
                    <p className={`text-sm font-bold mb-2 ${selectedTopic?.topic === t.topic ? 'text-primary-foreground' : ''}`}>{t.topic}</p>
                    <div className="space-y-1.5">
                      <div className="flex items-start gap-1.5">
                        <span className={`text-xs font-semibold shrink-0 ${selectedTopic?.topic === t.topic ? 'text-primary-foreground/80' : 'text-[#4A7C6F]'}`}>正方</span>
                        <p className={`text-xs line-clamp-1 ${selectedTopic?.topic === t.topic ? 'text-primary-foreground/90' : 'text-foreground'}`}>{t.proArgument}</p>
                      </div>
                      <div className="flex items-start gap-1.5">
                        <span className={`text-xs font-semibold shrink-0 ${selectedTopic?.topic === t.topic ? 'text-primary-foreground/80' : 'text-[#B8704A]'}`}>反方</span>
                        <p className={`text-xs line-clamp-1 ${selectedTopic?.topic === t.topic ? 'text-primary-foreground/90' : 'text-foreground'}`}>{t.conArgument}</p>
                      </div>
                    </div>
                  </button>
                ))}
                <button
                  onClick={() => { setShowManualInput(true); setSelectedTopic(null) }}
                  className={`flex items-center justify-center gap-2 rounded-xl border p-3 transition-all ${
                    showManualInput
                      ? 'bg-primary text-primary-foreground border-primary'
                      : 'border-dashed border-border/50 bg-card text-foreground hover:bg-muted hover:border-border'
                  }`}
                >
                  <MessageSquare className={`h-4 w-4 ${showManualInput ? 'text-primary-foreground' : 'text-brand-500'}`} />
                  <span className={`text-xs font-medium ${showManualInput ? 'text-primary-foreground' : 'text-muted-foreground'}`}>自定义辩题</span>
                </button>
              </div>

              {/* 移动端：横向滑动卡片 */}
              <div className="md:hidden flex gap-3 overflow-x-auto pb-2 scrollbar-hide px-4">
                {topics.map((t, i) => (
                  <button
                    key={i}
                    onClick={() => { setSelectedTopic(t); setShowManualInput(false); setManualTopic('') }}
                    className={`shrink-0 w-72 text-left rounded-2xl border p-4 transition-all ${
                      selectedTopic?.topic === t.topic
                        ? 'bg-primary text-primary-foreground border-primary'
                        : 'border-border/50 bg-card text-foreground hover:bg-muted hover:border-border'
                    }`}
                  >
                    <p className={`text-sm font-bold mb-3 line-clamp-2 ${selectedTopic?.topic === t.topic ? 'text-primary-foreground' : ''}`}>{t.topic}</p>
                    <div className="space-y-2">
                      <div className={`rounded-lg border px-3 py-2 ${selectedTopic?.topic === t.topic ? 'border-primary-foreground/20 bg-primary-foreground/10' : 'border-teal-200 dark:border-teal-800 bg-teal-50 dark:bg-teal-950/20'}`}>
                        <span className={`text-xs font-semibold block mb-0.5 ${selectedTopic?.topic === t.topic ? 'text-primary-foreground/80' : 'text-[#4A7C6F]'}`}>正方</span>
                        <span className={`text-xs line-clamp-2 ${selectedTopic?.topic === t.topic ? 'text-primary-foreground/90' : 'text-foreground'}`}>{t.proArgument}</span>
                      </div>
                      <div className={`rounded-lg border px-3 py-2 ${selectedTopic?.topic === t.topic ? 'border-primary-foreground/20 bg-primary-foreground/10' : 'border-amber-200 dark:border-amber-800 bg-amber-50 dark:bg-amber-950/20'}`}>
                        <span className={`text-xs font-semibold block mb-0.5 ${selectedTopic?.topic === t.topic ? 'text-primary-foreground/80' : 'text-[#B8704A]'}`}>反方</span>
                        <span className={`text-xs line-clamp-2 ${selectedTopic?.topic === t.topic ? 'text-primary-foreground/90' : 'text-foreground'}`}>{t.conArgument}</span>
                      </div>
                    </div>
                  </button>
                ))}
                <button
                  onClick={() => { setShowManualInput(true); setSelectedTopic(null) }}
                  className={`shrink-0 w-40 flex flex-col items-center justify-center gap-2 rounded-2xl border p-4 transition-all ${
                    showManualInput
                      ? 'bg-primary text-primary-foreground border-primary'
                      : 'border-dashed border-border/50 bg-card text-foreground hover:bg-muted hover:border-border'
                  }`}
                >
                  <div className={`flex h-10 w-10 items-center justify-center rounded-full ${showManualInput ? 'bg-primary-foreground/20 text-primary-foreground' : 'bg-brand-100 text-brand-500'}`}>
                    <MessageSquare className="h-5 w-5" />
                  </div>
                  <span className={`text-xs font-medium ${showManualInput ? 'text-primary-foreground' : 'text-muted-foreground'}`}>自定义辩题</span>
                </button>
              </div>
            </>
          )}

          {/* 手动输入 */}
          {showManualInput && (
            <div className="mt-5 space-y-4 animate-in fade-in slide-in-from-top-2 duration-300">
              <div className="relative">
                <textarea
                  value={manualTopic}
                  onChange={e => setManualTopic(e.target.value)}
                  placeholder="输入你的辩题..."
                  className="w-full rounded-xl border border-border/50 bg-card p-4 text-sm resize-none focus:outline-none focus:border-brand-400 pr-12"
                  rows={2}
                />
                <button
                  onClick={handleOptimizeTopic}
                  disabled={optimizing || !manualTopic.trim()}
                  className="absolute bottom-3 right-3 flex h-8 w-8 items-center justify-center rounded-full bg-brand-100 text-brand-500 hover:bg-brand-200 disabled:opacity-30 disabled:cursor-not-allowed transition-all active:scale-90"
                  title="使用AI优化辩题"
                >
                  {optimizing ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <Sparkles className="h-4 w-4" />
                  )}
                </button>
              </div>
              <div className={`flex ${isMobile ? 'flex-col gap-3' : 'flex-row gap-3'}`}>
                <textarea
                  value={manualProArg}
                  onChange={e => setManualProArg(e.target.value)}
                  placeholder="正方观点..."
                  className="flex-1 rounded-xl border border-teal-300 dark:border-teal-800 bg-teal-50 dark:bg-teal-950/20 p-4 text-sm resize-none focus:outline-none focus:border-teal-500"
                  rows={2}
                />
                <textarea
                  value={manualConArg}
                  onChange={e => setManualConArg(e.target.value)}
                  placeholder="反方观点..."
                  className="flex-1 rounded-xl border border-amber-300 dark:border-amber-800 bg-amber-50 dark:bg-amber-950/20 p-4 text-sm resize-none focus:outline-none focus:border-amber-500"
                  rows={2}
                />
              </div>
            </div>
          )}
        </section>

        {/* 辩手阵容区 — VS 对抗布局 */}
        <section className="px-4 pt-6 pb-3">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-sm font-bold flex items-center gap-2">
              <Swords className="h-4 w-4 text-brand-500" />
              辩手阵容
            </h2>
            <div className="flex items-center gap-1.5">
              <span className="text-xs text-[#4A7C6F] font-medium">正方</span>
              <span className="text-xs font-bold text-muted-foreground/30">VS</span>
              <span className="text-xs text-[#B8704A] font-medium">反方</span>
            </div>
          </div>
          <div className={`flex ${isMobile ? 'flex-col gap-4' : 'flex-row gap-4'}`}>
            <div className={`${isMobile ? '' : 'flex-1'} rounded-2xl border border-border/50 bg-card p-4`}>
              <div className="flex items-center gap-1.5 mb-3 px-1">
                <div className="h-2 w-2 rounded-full bg-[#4A7C6F]" />
                <span className="text-xs font-bold text-[#4A7C6F]">正方阵容</span>
                <span className="text-xs text-muted-foreground ml-auto">{proRoleKeys.filter(Boolean).length}/4</span>
              </div>
              {renderSlotGroup('PRO', proRoleKeys)}
            </div>
            <div className={`${isMobile ? '' : 'flex-1'} rounded-2xl border border-border/50 bg-card p-4`}>
              <div className="flex items-center gap-1.5 mb-3 px-1">
                <div className="h-2 w-2 rounded-full bg-[#B8704A]" />
                <span className="text-xs font-bold text-[#B8704A]">反方阵容</span>
                <span className="text-xs text-muted-foreground ml-auto">{conRoleKeys.filter(Boolean).length}/4</span>
              </div>
              {renderSlotGroup('CON', conRoleKeys)}
            </div>
          </div>
        </section>

        {/* 开始按钮 — 底部悬浮感 */}
        <section className="px-4 pt-6 pb-8">
          <button
            onClick={handleCreateSession}
            disabled={creating || (!selectedTopic && !manualTopic.trim())}
            className="w-full flex items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-brand-400 to-brand-500 px-4 py-3.5 text-sm font-semibold text-white shadow-lg shadow-brand-500/25 disabled:opacity-50 disabled:cursor-not-allowed active:scale-[0.98] transition-all"
          >
            {creating ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Swords className="h-4 w-4" />
            )}
            {creating ? '创建中...' : '开始辩论'}
          </button>
        </section>

        {/* 已有辩论 — 折叠到底部 */}
        {sessions.length > 0 && (
          <section id="history-section" className="px-4 pt-4 pb-8 border-t border-border/10">
            <h2 className="text-sm font-bold mb-4 flex items-center gap-2">
              <History className="h-4 w-4 text-muted-foreground" />
              历史辩论 ({sessions.length})
            </h2>
            <div className="space-y-3">
              {sessions.map(s => (
                <SessionCard key={s.sessionId} session={s} bookId={bookIdNum} onDelete={handleDeleteSession} />
              ))}
            </div>
          </section>
        )}

      </div>

      {/* 性格选择 Modal — 抽卡式全屏 */}
      <Dialog open={expandedSlot !== null} onOpenChange={(v) => { if (!v) setExpandedSlot(null) }}>
        <DialogContent className="max-w-md rounded-2xl p-0 gap-0" showCloseButton={false}>
          <div className="flex items-center justify-between px-5 pt-5 pb-3">
            <div>
              <h3 className="text-sm font-bold">选择辩手性格</h3>
              <p className="text-xs text-muted-foreground mt-0.5">
                {expandedSlot ? `${expandedSlot.side === 'PRO' ? '正方' : '反方'}${['一辩','二辩','三辩','四辩'][expandedSlot.index]}` : ''}
              </p>
            </div>
            <button
              onClick={() => setExpandedSlot(null)}
              className="rounded-full p-1 hover:bg-muted transition-colors"
            >
              <X className="h-4 w-4 text-muted-foreground" />
            </button>
          </div>
          <div className="px-5 pb-5 max-h-[60vh] overflow-y-auto overscroll-contain">
            <div className="grid grid-cols-2 gap-3">
              {PERSONALITY_OPTIONS.map(p => {
                const isActive = expandedSlot
                  ? (expandedSlot.side === 'PRO' ? proRoleKeys : conRoleKeys)[expandedSlot.index] === p.key
                  : false
                const color = DEBATE_PERSONALITY_COLORS[p.key] || '#888'
                return (
                  <button
                    key={p.key}
                    onClick={() => {
                      if (expandedSlot) {
                        handleSlotSelect(expandedSlot.side, expandedSlot.index, p.key)
                        setExpandedSlot(null)
                      }
                    }}
                    className={`flex flex-col items-center text-center rounded-2xl p-4 transition-all ${
                      isActive
                        ? 'bg-primary text-primary-foreground shadow-sm'
                        : 'bg-card border border-border/50 hover:border-border hover:bg-muted/50'
                    }`}
                  >
                    <div
                      className="flex h-10 w-10 items-center justify-center rounded-full text-lg mb-2"
                      style={{
                        backgroundColor: isActive ? 'rgba(255,255,255,0.15)' : hexToRgba(color, 0.1),
                      }}
                    >
                      {DEBATE_PERSONALITY_ICONS[p.key] || '👤'}
                    </div>
                    <span className={`text-xs font-semibold leading-tight ${isActive ? 'text-primary-foreground' : 'text-foreground'}`}>{p.label}</span>
                    <span className={`text-xs leading-tight mt-1 ${isActive ? 'text-primary-foreground/70' : 'text-muted-foreground'}`}>{p.desc}</span>

                  </button>
                )
              })}
            </div>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  )
}
