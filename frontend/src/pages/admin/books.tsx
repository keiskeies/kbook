import { useState, useEffect, useRef, useCallback } from 'react'
import {
  AlertTriangle,
  ArrowLeft,
  BookOpen,
  Bot,
  CheckCircle2,
  Copy,
  Check,
  ChevronDown,
  ChevronUp,
  Database,
  File,
  FileText,
  History,
  Loader2,
  MessageCircle,
  Plus,
  RefreshCw,
  Scan,
  Search,
  Send,
  Sparkles,
  Upload,
  X,
  XCircle
} from 'lucide-react'
import DraggableFab from '@/components/DraggableFab'
import { useGoBack } from '@/hooks/useGoBack'
import { useScrollRestore } from '@/hooks/useScrollRestore'
import type {
  EmbeddingStats,
  EsReindexProgress,
  EsReindexResult,
  ScanError,
  ScanProgress,
  ScanResult,
} from '@/api/book'
import {
  clearContentVectors,
  getEmbeddingStats,
  getScanStatus,
  rebuildEsIndexStream,
  resetScanStatus,
  scanBooksStream,
  uploadBookWithProgress
} from '@/api/book'
import { createAdminSession, streamAdminChat, getAdminSessions, getAdminHistory, deleteAdminSession } from '@/api/adminAi'
import type { AiMessage, AiSessionItem } from '@/types/ai'
import ThinkingBlock from '@/components/ui/thinking-block'
import MarkdownRenderer from '@/components/ui/markdown-renderer'
import { toast } from 'sonner'

/** 管理员快捷指令 */
const ADMIN_QUICK_PROMPTS = [
  '作者排行 TOP20',
  '图书格式分布',
  '最近7天入库趋势',
  '评分低于3的图书',
  '扫描进度',
  '本周入库统计',
]

export default function AdminBooksPage() {
  const goBack = useGoBack()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const chatScrollRef = useRef<HTMLDivElement>(null)
  const chatAbortRef = useRef<AbortController | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const userScrollingRef = useRef(false)
  const { handleScroll } = useScrollRestore(scrollRef)

  // 扫描状态
  const [scanning, setScanning] = useState(false)
  const [progress, setProgress] = useState<ScanProgress | null>(null)
  const [scanResult, setScanResult] = useState<ScanResult | null>(null)
  const [showErrors, setShowErrors] = useState(false)
  const [skipBeforeId, setSkipBeforeId] = useState('')

  // 多文件上传状态
  type UploadItem = {
    id: number
    file: File
    name: string
    percent: number
    status: 'pending' | 'uploading' | 'done' | 'error'
    message?: string
  }
  const [uploadQueue, setUploadQueue] = useState<UploadItem[]>([])
  const uploadIdRef = useRef(0)
  const uploadingRef = useRef(false)
  const queueRef = useRef<UploadItem[]>([])

  // 内容向量管理状态
  const [embedStats, setEmbedStats] = useState<EmbeddingStats | null>(null)
  const [statsLoading, setStatsLoading] = useState(false)

  const [contentVectorsClearing, setContentVectorsClearing] = useState(false)

  // ES 索引刷新状态
  const [esReindexing, setEsReindexing] = useState(false)
  const [esProgress, setEsProgress] = useState<EsReindexProgress | null>(null)
  const [esResult, setEsResult] = useState<EsReindexResult | null>(null)
  const esAbortRef = useRef<AbortController | null>(null)

  // AI 管理员对话状态
  const [showChat, setShowChat] = useState(false)
  const [chatMessages, setChatMessages] = useState<AiMessage[]>([])
  const [chatInput, setChatInput] = useState('')
  const [chatLoading, setChatLoading] = useState(false)
  const [chatSessionId, setChatSessionId] = useState('')
  const [copiedId, setCopiedId] = useState<string | null>(null)
  const [showHistory, setShowHistory] = useState(false)
  const [historySessions, setHistorySessions] = useState<AiSessionItem[]>([])

  // 停止轮询
  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current)
      pollRef.current = null
    }
  }, [])

  // 开始轮询扫描进度
  const startPolling = useCallback(() => {
    stopPolling()
    pollRef.current = setInterval(async () => {
      try {
        const res = await getScanStatus() as any
        if (res.scanning) {
          setProgress(prev => ({
            current: res.current,
            total: res.total,
            added: res.added,
            updated: res.updated,
            skipped: res.skipped,
            failed: res.failed,
            errors: res.errors || [],
            currentFile: res.currentFile || prev?.currentFile || '扫描中...',
            status: res.current >= res.total && res.total > 0 ? 'completed' : 'scanning',
          }))
        } else {
          stopPolling()
          setScanning(false)
          if (res.total > 0) {
            setScanResult({
              added: res.added,
              updated: res.updated,
              skipped: res.skipped,
              failed: res.failed,
              errors: res.errors || [],
              elapsed: 0,
            })
          }
        }
      } catch { /* ignore */ }
    }, 3000)
  }, [stopPolling])

  // 页面加载检查
  useEffect(() => {
    let cancelled = false
    getScanStatus().then(res => {
      if (cancelled) return
      const data = res as any
      if (data.scanning) {
        setScanning(true)
        setProgress({
          current: data.current || 0,
          total: data.total || 0,
          added: data.added || 0,
          updated: data.updated || 0,
          skipped: data.skipped || 0,
          failed: data.failed || 0,
          errors: data.errors || [],
          currentFile: data.currentFile || '恢复扫描连接中...',
          status: 'scanning',
        })
        startScanStream()
        startPolling()
      }
    }).catch(() => {})
    loadEmbedStats().then(() => {})
    return () => {
      cancelled = true
      stopPolling()
      abortRef.current?.abort()
      chatAbortRef.current?.abort()
      esAbortRef.current?.abort()
    }
  }, [])

  // 滚动到底部
  useEffect(() => {
    if (showChat && !userScrollingRef.current) {
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
  }, [chatMessages, showChat])

  const handleChatScroll = () => {
    const container = chatScrollRef.current
    if (!container) return
    const { scrollTop, scrollHeight, clientHeight } = container
    const isAtBottom = scrollHeight - scrollTop - clientHeight < 50
    userScrollingRef.current = !isAtBottom
  }

  const startScanStream = useCallback(() => {
    const skipId = skipBeforeId ? parseInt(skipBeforeId, 10) : undefined
    abortRef.current = scanBooksStream(
        (data) => {
          setProgress(data)
          if (data.status === 'completed') {
            stopPolling()
            setScanning(false)
          }
        },
        (data) => {
          stopPolling()
          setScanResult(data)
          setScanning(false)
          const failMsg = data.failed > 0 ? `，${data.failed} 本未处理` : ''
          toast.success(`扫描完成：新增 ${data.added} 本，更新 ${data.updated} 本，跳过 ${data.skipped} 本${failMsg}`)
        },
        (err) => {
          console.warn('SSE 断开，切换轮询:', err.message)
          startPolling()
        },
        skipId,
    )
    startPolling()
  }, [skipBeforeId])

  const handleScan = async () => {
    if (scanning) return
    setScanning(true)
    setProgress(null)
    setScanResult(null)
    setShowErrors(false)
    startScanStream()
  }

  const handleResetScan = async () => {
    stopPolling()
    if (abortRef.current) {
      abortRef.current.abort()
      abortRef.current = null
    }
    try { await resetScanStatus() } catch { /* empty */ }
    setScanning(false)
    setProgress(null)
    toast.info('已重置扫描状态')
  }

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files
    if (!files || files.length === 0) return

    const validExts = ['EPUB', 'PDF', 'TXT']
    const newItems: UploadItem[] = []
    for (let i = 0; i < files.length; i++) {
      const file = files[i]
      const ext = file.name.split('.').pop()?.toUpperCase()
      if (!validExts.includes(ext || '')) {
        toast.error(`${file.name} 格式不支持，已跳过`)
        continue
      }
      newItems.push({
        id: ++uploadIdRef.current,
        file,
        name: file.name,
        percent: 0,
        status: 'pending',
      })
    }
    if (newItems.length === 0) return

    queueRef.current = [...queueRef.current, ...newItems]
    setUploadQueue([...queueRef.current])
    if (fileInputRef.current) fileInputRef.current.value = ''

    if (uploadingRef.current) return
    uploadingRef.current = true
    runUploadQueue()
  }

  const updateItem = (id: number, patch: Partial<UploadItem>) => {
    queueRef.current = queueRef.current.map(i => i.id === id ? { ...i, ...patch } : i)
    setUploadQueue([...queueRef.current])
  }

  const runUploadQueue = async () => {
    const CONCURRENT = 3
    const takeNext = (): UploadItem | undefined => {
      return queueRef.current.find(i => i.status === 'pending')
    }

    const workers = Array.from({ length: CONCURRENT }, async () => {
      while (true) {
        const item = takeNext()
        if (!item) break

        updateItem(item.id, { status: 'uploading', percent: 0 })
        try {
          const result = await uploadBookWithProgress(
            item.file,
            undefined,
            (percent) => updateItem(item.id, { percent }),
          )
          updateItem(item.id, { status: 'done', percent: 100, message: (result as any)?.title })
        } catch (err: any) {
          updateItem(item.id, { status: 'error', message: err.message || '上传失败' })
        }
      }
    })

    await Promise.all(workers)
    uploadingRef.current = false
  }

  const clearFinishedUploads = () => {
    queueRef.current = queueRef.current.filter(i => i.status === 'pending' || i.status === 'uploading')
    setUploadQueue([...queueRef.current])
  }

  // ==================== 内容向量管理 ====================

  const loadEmbedStats = useCallback(async () => {
    setStatsLoading(true)
    try {
      const stats = await getEmbeddingStats() as any
      setEmbedStats(stats)
    } catch { /* ignore */ }
    finally { setStatsLoading(false) }
  }, [])

  const handleClearContentVectors = async () => {
    if (contentVectorsClearing) return
    if (!confirm('确定要清空内容向量库（kbook_content）吗？此操作不可恢复。')) {
      return
    }
    setContentVectorsClearing(true)
    try {
      const result = await clearContentVectors() as any
      toast.success(`内容向量库已清空，删除 ${result.deletedCount} 条向量`)
      loadEmbedStats()
    } catch (err: any) {
      toast.error(err.message || '清空失败')
    } finally { setContentVectorsClearing(false) }
  }

  // ==================== ES 索引刷新 ====================

  const handleEsReindex = () => {
    if (esReindexing) return
    setEsReindexing(true)
    setEsProgress(null)
    setEsResult(null)

    esAbortRef.current = rebuildEsIndexStream(
      (data) => {
        setEsProgress(data)
      },
      (data) => {
        setEsReindexing(false)
        setEsResult(data)
        toast.success(`ES 索引重建完成，耗时 ${(data.elapsed / 1000).toFixed(1)}s`)
      },
      (err) => {
        setEsReindexing(false)
        toast.error(err.message || 'ES 重建失败')
      },
    )
  }

  const handleCancelEsReindex = () => {
    if (esAbortRef.current) {
      esAbortRef.current.abort()
      esAbortRef.current = null
    }
    setEsReindexing(false)
    setEsProgress(null)
    toast.info('已取消 ES 索引重建')
  }

  // ==================== AI 管理员对话 ====================

  const handleChatSend = useCallback(async (text?: string) => {
    const message = (text || chatInput).trim()
    if (!message || chatLoading) return

    // 清除用户手动滚动标识，恢复自动滚动
    userScrollingRef.current = false

    // 确保有会话
    let sessionId = chatSessionId
    if (!sessionId) {
      try {
        const data = await createAdminSession()
        sessionId = data.sessionId
        setChatSessionId(sessionId)
      } catch {
        toast.error('创建对话会话失败')
        return
      }
    }

    const userMsg: AiMessage = {
      id: `u-${Date.now()}`,
      role: 'user',
      content: message,
      timestamp: Date.now(),
    }
    setChatMessages(prev => [...prev, userMsg])
    setChatInput('')
    setChatLoading(true)

    const assistantMsg: AiMessage = {
      id: `a-${Date.now()}`,
      role: 'assistant',
      content: '',
      timestamp: Date.now(),
      streaming: true,
    }
    setChatMessages(prev => [...prev, assistantMsg])

    chatAbortRef.current = streamAdminChat(
        {sessionId, message},
        (chunk) => {
          setChatMessages(prev =>
              prev.map(m => m.id === assistantMsg.id ? {
                ...m,
                content: m.content + chunk,
                thinkingStatus: undefined
              } : m)
          )
        },
        () => {
          setChatMessages(prev =>
              prev.map(m => m.id === assistantMsg.id ? {...m, streaming: false, thinkingStatus: undefined} : m)
          )
          setChatLoading(false)
        },
        (error) => {
          setChatMessages(prev =>
              prev.map(m =>
                  m.id === assistantMsg.id
                      ? {
                        ...m,
                        content: `抱歉，AI 助理暂时无法回复：${error.message}`,
                        streaming: false,
                        thinkingStatus: undefined
                      }
                      : m
              )
          )
          setChatLoading(false)
        },
        (status) => {
          setChatMessages(prev =>
              prev.map(m => m.id === assistantMsg.id ? {...m, thinkingStatus: status} : m)
          )
        },
        (chunk) => {
          setChatMessages(prev =>
              prev.map(m => m.id === assistantMsg.id ? {...m, thinkingContent: (m.thinkingContent || '') + chunk} : m)
          )
        },
    )
  }, [chatInput, chatLoading, chatSessionId])

  const handleChatKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleChatSend()
    }
  }

  const handleNewAdminChat = async () => {
    try {
      const data = await createAdminSession()
      setChatSessionId(data.sessionId)
      setChatMessages([])
    } catch { /* ignore */ }
  }

  const handleRegenerate = useCallback(() => {
    if (chatLoading) return

    if (chatAbortRef.current) {
      chatAbortRef.current.abort()
      chatAbortRef.current = null
    }

    let lastAssistantIdx = -1
    for (let i = chatMessages.length - 1; i >= 0; i--) {
      if (chatMessages[i].role === 'assistant' && !chatMessages[i].streaming) {
        lastAssistantIdx = i
        break
      }
    }
    if (lastAssistantIdx === -1) return

    let userMsgContent = ''
    if (lastAssistantIdx > 0 && chatMessages[lastAssistantIdx - 1].role === 'user') {
      userMsgContent = chatMessages[lastAssistantIdx - 1].content
    }

    const cutIdx = lastAssistantIdx > 0 && chatMessages[lastAssistantIdx - 1].role === 'user'
      ? lastAssistantIdx - 1
      : lastAssistantIdx
    setChatMessages(chatMessages.slice(0, cutIdx))

    if (userMsgContent) {
      requestAnimationFrame(() => {
        handleChatSend(userMsgContent)
      })
    }
  }, [chatMessages, chatLoading, handleChatSend])

  const loadHistorySessions = useCallback(async () => {
    try {
      const data = await getAdminSessions() as any
      setHistorySessions(data)
    } catch { /* ignore */ }
  }, [])

  const loadSessionHistory = useCallback(async (targetSessionId: string) => {
    try {
      const data = await getAdminHistory(targetSessionId)
      if (data && data.length > 0) {
        const history: AiMessage[] = data
          .filter((c: any) => c.role === 'user' || c.role === 'assistant')
          .map((c: any) => ({
            id: `h-${c.id}`,
            role: c.role as 'user' | 'assistant',
            content: c.content,
            timestamp: new Date(c.createdAt).getTime(),
            thinkingContent: c.thinkingContent || undefined,
          }))
        setChatMessages(history)
        setChatSessionId(targetSessionId)
      }
      setShowHistory(false)
    } catch { /* ignore */ }
  }, [])

  const handleDeleteSession = useCallback(async (sessionId: string, e: React.MouseEvent) => {
    e.stopPropagation()
    try {
      await deleteAdminSession(sessionId)
      setHistorySessions(prev => prev.filter(s => s.sessionId !== sessionId))
      if (sessionId === chatSessionId) {
        setChatMessages([])
        setChatSessionId('')
      }
    } catch { /* ignore */ }
  }, [chatSessionId])

  // ==================== 渲染辅助 ====================

  const formatIcon = (fmt: string) => {
    switch (fmt) {
      case 'PDF': return <File className="h-5 w-5 text-red-500" />
      case 'EPUB': return <BookOpen className="h-5 w-5 text-info" />
      case 'TXT': return <FileText className="h-5 w-5 text-green-500" />
      default: return <File className="h-5 w-5" />
    }
  }

  const progressPercent = progress && progress.total > 0
    ? Math.round((progress.current / progress.total) * 100)
    : 0

  const currentErrors: ScanError[] = scanResult?.errors || progress?.errors || []

  return (
    <div className="absolute inset-0 flex flex-col overflow-hidden bg-background">
      {/* 顶部 */}
      <header className="shrink-0 z-10 border-b border-border/50 bg-background/80 backdrop-blur-xl">
        <div className="flex items-center gap-3 px-4 md:px-6 lg:px-8 py-3">
          <button onClick={() => goBack()} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted">
            <ArrowLeft className="h-5 w-5" />
          </button>
          <h1 className="text-lg font-semibold">图书管理</h1>
        </div>
      </header>

      <div ref={scrollRef} onScroll={handleScroll} className="flex-1 overflow-y-auto overscroll-contain px-4 md:px-6 lg:px-8 py-4">
        {/* PC 两栏布局 */}
        <div className="md:grid md:grid-cols-2 md:gap-4 space-y-4 md:space-y-0">
          {/* 左栏：图书操作与说明 */}
          <div className="space-y-4">
            {/* 扫描图书 */}
            <section className="rounded-xl bg-card p-4 shadow-xs">
              <div className="flex items-center gap-3 mb-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary/10">
                  <Scan className="h-5 w-5 text-primary" />
                </div>
                <div>
                  <h3 className="text-sm font-semibold">扫描图书目录</h3>
                  <p className="text-xs text-muted-foreground">自动扫描 EPUB/PDF/TXT 目录并入库</p>
                </div>
              </div>
              <div className="flex gap-2 text-xs text-muted-foreground mb-3">
                <span className="rounded bg-red-50 px-2 py-1 text-red-600">EPUB</span>
                <span className="rounded bg-info/10 px-2 py-1 text-info">PDF</span>
                <span className="rounded bg-green-50 px-2 py-1 text-green-600">TXT</span>
              </div>

              {/* 断点续扫配置 */}
              <div className="mb-3 flex items-center gap-2">
                <label className="shrink-0 text-xs text-muted-foreground whitespace-nowrap">跳过 ID &lt;</label>
                <input
                  type="number"
                  min="1"
                  value={skipBeforeId}
                  onChange={(e) => setSkipBeforeId(e.target.value)}
                  placeholder="如 1800"
                  disabled={scanning}
                  className="h-8 w-28 rounded-lg border border-border bg-background px-2.5 text-xs outline-none focus:ring-1 focus:ring-primary disabled:opacity-50"
                />
                <span className="text-[10px] text-muted-foreground">断点续扫：跳过 ID 小于此值的已有图书</span>
              </div>

              {/* 进度条 */}
              {scanning && progress && (
                <div className="mb-3 space-y-2">
                  <div className="flex items-center justify-between text-xs text-muted-foreground">
                    <span className="truncate max-w-[60%]">{progress.currentFile}</span>
                    <span>{progress.current}/{progress.total} ({progressPercent}%)</span>
                  </div>
                  <div className="h-2.5 w-full overflow-hidden rounded-full bg-muted">
                    <div
                      className="h-full rounded-full bg-primary transition-all duration-300 ease-out"
                      style={{ width: `${progressPercent}%` }}
                    />
                  </div>
                  <div className="flex gap-3 text-xs text-muted-foreground">
                    <span className="text-green-600">+{progress.added} 新增</span>
                    <span className="text-info">↑{progress.updated} 更新</span>
                    <span>○{progress.skipped} 跳过</span>
                    {progress.failed > 0 && <span className="text-red-600">✕{progress.failed} 失败</span>}
                  </div>
                  {progress.failed > 0 && progress.errors && progress.errors.length > 0 && (
                    <button
                      onClick={() => setShowErrors(!showErrors)}
                      className="flex items-center gap-1 text-xs text-red-500 hover:text-red-600"
                    >
                      <AlertTriangle className="h-3 w-3" />
                      查看错误详情
                      {showErrors ? <ChevronUp className="h-3 w-3" /> : <ChevronDown className="h-3 w-3" />}
                    </button>
                  )}
                </div>
              )}

              {/* 错误详情 */}
              {showErrors && currentErrors.length > 0 && (
                <div className="mb-3 max-h-48 overflow-y-auto overscroll-y-contain rounded-lg bg-red-50 p-3 text-xs space-y-2 dark:bg-red-950/20">
                  {currentErrors.map((err, i) => (
                    <div key={i} className="flex gap-2">
                      <span className="shrink-0 text-red-400">{i + 1}.</span>
                      <div className="min-w-0">
                        <span className="font-medium text-red-700 dark:text-red-400 break-all">{err.file}</span>
                        <p className="text-red-500 dark:text-red-400/80 break-all mt-0.5">{err.reason}</p>
                      </div>
                    </div>
                  ))}
                </div>
              )}

              {/* 扫描完成结果 */}
              {scanResult && !scanning && (
                <div className="mb-3 space-y-2">
                  <div className="rounded-lg bg-green-50 p-3 text-xs space-y-1 dark:bg-green-950/30">
                    <div className="flex items-center gap-1.5 font-medium text-green-700 dark:text-green-400">
                      <CheckCircle2 className="h-4 w-4" />
                      扫描完成
                    </div>
                    <div className="flex gap-3 text-green-600 dark:text-green-400">
                      <span>+{scanResult.added} 新增</span>
                      <span>↑{scanResult.updated} 更新</span>
                      <span>○{scanResult.skipped} 跳过</span>
                      {scanResult.failed > 0 && <span className="text-red-500">✕{scanResult.failed} 失败</span>}
                    </div>
                    {scanResult.elapsed > 0 && (
                      <span className="text-muted-foreground">耗时 {(scanResult.elapsed / 1000).toFixed(1)}s</span>
                    )}
                  </div>
                  {scanResult.failed > 0 && scanResult.errors && scanResult.errors.length > 0 && (
                    <div>
                      <button
                        onClick={() => setShowErrors(!showErrors)}
                        className="flex items-center gap-1 text-xs text-red-500 hover:text-red-600"
                      >
                        <AlertTriangle className="h-3 w-3" />
                        {showErrors ? '收起错误详情' : `查看 ${scanResult.failed} 个错误详情`}
                        {showErrors ? <ChevronUp className="h-3 w-3" /> : <ChevronDown className="h-3 w-3" />}
                      </button>
                      {showErrors && (
                        <div className="mt-2 max-h-48 overflow-y-auto overscroll-y-contain rounded-lg bg-red-50 p-3 text-xs space-y-2 dark:bg-red-950/20">
                          {scanResult.errors.map((err, i) => (
                            <div key={i} className="flex gap-2">
                              <span className="shrink-0 text-red-400">{i + 1}.</span>
                              <div className="min-w-0">
                                <span className="font-medium text-red-700 dark:text-red-400 break-all">{err.file}</span>
                                <p className="text-red-500 dark:text-red-400/80 break-all mt-0.5">{err.reason}</p>
                              </div>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )}

              <div className="flex gap-2">
                <button
                  onClick={handleScan}
                  disabled={scanning}
                  className="flex flex-1 items-center justify-center gap-2 rounded-xl bg-primary py-2.5 text-sm font-medium text-primary-foreground disabled:opacity-50"
                >
                  <RefreshCw className={`h-4 w-4 ${scanning ? 'animate-spin' : ''}`} />
                  {scanning ? '扫描中...' : '开始扫描'}
                </button>
                {scanning && (
                  <button
                    onClick={handleResetScan}
                    className="flex items-center justify-center gap-1.5 rounded-xl border border-red-200 px-3 py-2.5 text-sm font-medium text-red-600 hover:bg-red-50"
                  >
                    <XCircle className="h-4 w-4" />
                    重置
                  </button>
                )}
              </div>
            </section>

            {/* 上传图书 */}
            <section className="rounded-xl bg-card p-4 shadow-xs">
              <div className="flex items-center gap-3 mb-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-orange-50">
                  <Upload className="h-5 w-5 text-orange-500" />
                </div>
                <div>
                  <h3 className="text-sm font-semibold">上传图书</h3>
                  <p className="text-xs text-muted-foreground">支持多选，可同时上传多个 EPUB/PDF/TXT 文件</p>
                </div>
              </div>
              <input
                ref={fileInputRef}
                type="file"
                accept=".epub,.pdf,.txt"
                multiple
                onChange={handleUpload}
                className="hidden"
              />
              <button
                onClick={() => fileInputRef.current?.click()}
                disabled={uploadingRef.current}
                className="flex w-full items-center justify-center gap-2 rounded-xl bg-orange-500 py-2.5 text-sm font-medium text-white disabled:opacity-50"
              >
                <Upload className={`h-4 w-4 ${uploadingRef.current ? 'animate-bounce' : ''}`} />
                {uploadQueue.some(i => i.status === 'uploading') ? '上传中...' : '选择文件上传'}
              </button>

              {/* 上传进度列表 */}
              {uploadQueue.length > 0 && (
                <div className="mt-3 space-y-2">
                  {uploadQueue.map(item => (
                    <div key={item.id} className="rounded-lg border bg-background px-3 py-2">
                      <div className="flex items-center justify-between gap-2 mb-1.5">
                        <div className="flex items-center gap-2 min-w-0">
                          {item.status === 'done' && <CheckCircle2 className="h-4 w-4 shrink-0 text-green-500" />}
                          {item.status === 'error' && <XCircle className="h-4 w-4 shrink-0 text-red-500" />}
                          {item.status === 'uploading' && <Loader2 className="h-4 w-4 shrink-0 text-orange-500 animate-spin" />}
                          {item.status === 'pending' && <div className="h-4 w-4 shrink-0 rounded-full border-2 border-muted-foreground/30" />}
                          <span className="text-xs truncate" title={item.name}>
                            {item.name}
                          </span>
                        </div>
                        <div className="flex items-center gap-2 shrink-0">
                          {item.status === 'done' && (
                            <span className="text-xs text-green-600">完成</span>
                          )}
                          {item.status === 'error' && (
                            <span className="text-xs text-red-500 truncate max-w-[120px]" title={item.message}>{item.message}</span>
                          )}
                          {(item.status === 'uploading' || item.status === 'pending') && (
                            <span className="text-xs text-muted-foreground">{item.percent}%</span>
                          )}
                        </div>
                      </div>
                      {/* 进度条 */}
                      <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
                        <div
                          className={`h-full rounded-full transition-all duration-300 ${
                            item.status === 'done' ? 'bg-green-500' :
                            item.status === 'error' ? 'bg-red-400' : 'bg-orange-500'
                          }`}
                          style={{ width: `${item.percent}%` }}
                        />
                      </div>
                    </div>
                  ))}

                  {/* 清除已完成项 */}
                  {uploadQueue.some(i => i.status === 'done' || i.status === 'error') && (
                    <button
                      onClick={clearFinishedUploads}
                      className="text-xs text-muted-foreground hover:text-foreground transition-colors"
                    >
                      清除已完成
                    </button>
                  )}
                </div>
              )}
            </section>

            {/* 说明 */}
            <section className="rounded-xl bg-muted/50 p-4">
              <h3 className="mb-2 text-sm font-semibold">说明</h3>
              <ul className="space-y-1.5 text-xs text-muted-foreground">
                <li className="flex items-center gap-2">
                  {formatIcon('EPUB')}
                  <span><strong>EPUB</strong> — 自动提取作者、封面图片、简介</span>
                </li>
                <li className="flex items-center gap-2">
                  {formatIcon('PDF')}
                  <span><strong>PDF</strong> — 首页自动渲染为封面图片</span>
                </li>
                <li className="flex items-center gap-2">
                  {formatIcon('TXT')}
                  <span><strong>TXT</strong> — 无封面，自动计算字符数</span>
                </li>
                <li>书名默认使用文件名（不含扩展名）</li>
                <li>扫描已入库的文件会自动跳过</li>
                <li>AI 标签会在入库后自动生成</li>
                <li>重建功能会完整覆盖图书的元数据、AI 数据、向量数据</li>
                <li className="flex items-center gap-1">
                  <Sparkles className="h-3 w-3 text-purple-500" />
                  <span>点击右下角紫色圆圈唤醒 AI 管理员，用自然语言管理图书</span>
                </li>
              </ul>
            </section>
          </div>

          {/* 右栏：配置与向量管理 */}
          <div className="space-y-4">
            {/* ES 索引管理 */}
            <section className="rounded-xl bg-card p-4 shadow-xs">
              <div className="flex items-center gap-3 mb-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-warning/10">
                  <Search className="h-5 w-5 text-warning" />
                </div>
                <div>
                  <h3 className="text-sm font-semibold">ES 索引管理</h3>
                  <p className="text-xs text-muted-foreground">全量刷新 Elasticsearch 搜索索引</p>
                </div>
              </div>

              {/* 进度条 */}
              {esReindexing && esProgress && (
                <div className="mb-3 space-y-2">
                  <div className="flex items-center justify-between text-xs text-muted-foreground">
                    <span>正在重建索引...</span>
                    <span>{esProgress.current}/{esProgress.total} ({Math.round((esProgress.current / esProgress.total) * 100)}%)</span>
                  </div>
                  <div className="h-2.5 w-full overflow-hidden rounded-full bg-muted">
                    <div
                      className="h-full rounded-full bg-warning transition-all duration-300 ease-out"
                      style={{ width: `${esProgress.total > 0 ? (esProgress.current / esProgress.total) * 100 : 0}%` }}
                    />
                  </div>
                </div>
              )}

              {/* 完成结果 */}
              {esResult && !esReindexing && (
                <div className="mb-3 rounded-lg bg-green-50 p-3 text-xs dark:bg-green-950/30">
                  <div className="flex items-center gap-1.5 font-medium text-green-700 dark:text-green-400">
                    <CheckCircle2 className="h-4 w-4" />
                    ES 索引重建完成
                  </div>
                  <div className="mt-1 text-green-600 dark:text-green-400">
                    耗时 {(esResult.elapsed / 1000).toFixed(1)}s
                  </div>
                </div>
              )}

              <div className="flex gap-2">
                <button
                  onClick={handleEsReindex}
                  disabled={esReindexing}
                  className="flex flex-1 items-center justify-center gap-2 rounded-xl bg-warning py-2.5 text-sm font-medium text-white disabled:opacity-50"
                >
                  <RefreshCw className={`h-4 w-4 ${esReindexing ? 'animate-spin' : ''}`} />
                  {esReindexing ? '重建中...' : '全量刷新 ES'}
                </button>
                {esReindexing && (
                  <button
                    onClick={handleCancelEsReindex}
                    className="flex items-center justify-center gap-1.5 rounded-xl border border-red-200 px-3 py-2.5 text-sm font-medium text-red-600 hover:bg-red-50"
                  >
                    <XCircle className="h-4 w-4" />
                    取消
                  </button>
                )}
              </div>
            </section>

            {/* 向量管理 */}
            <section className="rounded-xl bg-card p-4 shadow-xs">
              <div className="flex items-center gap-3 mb-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-success/10">
                  <Database className="h-5 w-5 text-success" />
                </div>
                <div>
                  <h3 className="text-sm font-semibold">向量管理</h3>
                  <p className="text-xs text-muted-foreground">管理 Qdrant 向量存储</p>
                </div>
              </div>

              {/* 统计信息 */}
              {embedStats && (
                <div className="mb-3 grid grid-cols-3 gap-2 text-xs">
                  <div className="rounded-lg bg-muted/50 p-2.5 text-center">
                    <div className="text-lg font-bold text-foreground">{embedStats.totalBooks}</div>
                    <div className="text-muted-foreground">总书籍</div>
                  </div>
                  <div className="rounded-lg bg-success/10 dark:bg-success/10 p-2.5 text-center">
                    <div className="text-lg font-bold text-success">{embedStats.embeddedBooks}</div>
                    <div className="text-success/80">已嵌入</div>
                  </div>
                  <div className="rounded-lg bg-muted/50 p-2.5 text-center">
                    <div className="text-lg font-bold text-foreground">{embedStats.totalContentVectors.toLocaleString()}</div>
                    <div className="text-muted-foreground">向量总数</div>
                  </div>
                </div>
              )}

              {/* 操作按钮 */}
              <div className="space-y-2">
                <button
                  onClick={loadEmbedStats}
                  disabled={statsLoading}
                  className="flex w-full items-center justify-center gap-2 rounded-xl border border-border py-2.5 text-sm font-medium hover:bg-muted disabled:opacity-50"
                >
                  <Database className={`h-4 w-4 ${statsLoading ? 'animate-pulse' : ''}`} />
                  {statsLoading ? '加载中...' : '刷新统计'}
                </button>

                {/* 清空内容信息 */}
                <button
                  onClick={handleClearContentVectors}
                  disabled={contentVectorsClearing}
                  className="flex w-full items-center justify-center gap-2 rounded-xl border border-red-200 py-2.5 text-sm font-medium text-red-600 hover:bg-red-50 disabled:opacity-50"
                >
                  <RefreshCw className={`h-4 w-4 ${contentVectorsClearing ? 'animate-spin' : ''}`} />
                  {contentVectorsClearing ? '清空中...' : '清空内容信息'}
                </button>
              </div>
            </section>
          </div>
        </div>
      </div>

      {/* ===== 可拖动浮动 AI 圆圈 ===== */}
      <DraggableFab
        onClick={() => setShowChat(true)}
        size={56}
        edgePadding={16}
        className="bg-gradient-to-br from-purple-500 to-purple-600 text-white shadow-lg shadow-purple-500/30 hover:shadow-xl hover:shadow-purple-500/40"
        title="AI 智能图书管理员"
      >
        <MessageCircle className="h-6 w-6" />
      </DraggableFab>

      {/* ===== AI 对话弹窗：移动端底部80%高度，PC端右侧抽屉 ===== */}
      {showChat && (
        <div className="fixed inset-0 z-50 flex items-end md:items-stretch md:justify-end">
          <div className="absolute inset-0 bg-black/40" onClick={() => setShowChat(false)} />
          <div className="relative flex w-full flex-col overflow-hidden bg-background h-[80vh] md:h-full md:w-[420px] md:max-w-[420px] rounded-t-2xl md:rounded-none border-t md:border-l md:border-t-0 shadow-xl">
            {/* 标题栏 */}
            <div className="flex items-center gap-3 border-b px-4 py-3">
              <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-purple-500 to-purple-600 text-white">
                <Bot className="h-5 w-5" />
              </div>
              <div className="flex-1 min-w-0">
                <h3 className="text-sm font-semibold">AI 图书管理员</h3>
                <p className="text-xs text-muted-foreground">小管随时为你服务</p>
              </div>
              {chatSessionId && (
                <button
                  onClick={handleNewAdminChat}
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
                onClick={() => setShowChat(false)}
                className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            {/* 历史记录 / 消息区域 */}
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
                        className={`w-full px-4 py-3 text-left transition-colors hover:bg-muted/50 group ${
                          session.sessionId === chatSessionId ? 'bg-muted' : ''
                        }`}
                        onClick={() => loadSessionHistory(session.sessionId)}
                      >
                        <div className="flex items-center justify-between">
                          <p className="truncate text-sm font-medium flex-1 min-w-0">{session.title || '未命名对话'}</p>
                          <span
                            className="ml-2 shrink-0 rounded p-1 text-muted-foreground opacity-0 transition-opacity hover:text-red-500 group-hover:opacity-100"
                            onClick={(e) => handleDeleteSession(session.sessionId, e)}
                            title="删除"
                          >
                            <X className="h-3.5 w-3.5" />
                          </span>
                        </div>
                        <p className="mt-0.5 text-xs text-muted-foreground">
                          {session.updatedAt ? new Date(session.updatedAt).toLocaleDateString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : ''}
                        </p>
                      </button>
                    ))}
                  </div>
                )}
              </div>
            ) : (
            <div
              ref={chatScrollRef}
              onScroll={handleChatScroll}
              className="flex-1 overflow-y-auto overscroll-y-contain p-4 space-y-3"
            >
              {chatMessages.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-8 text-center">
                  <div className="mb-3 flex h-14 w-14 items-center justify-center rounded-2xl bg-purple-50 dark:bg-purple-900/20">
                    <Bot className="h-7 w-7 text-purple-500" />
                  </div>
                  <h4 className="mb-1 text-sm font-semibold">你好，我是小管</h4>
                  <p className="mb-5 text-xs text-muted-foreground">AI 图书管理员，帮你高效管理图书库</p>
                </div>
              ) : (
                <div className="space-y-3">
                  {(() => {
                    let lastAssistantId = ''
                    for (let i = chatMessages.length - 1; i >= 0; i--) {
                      if (chatMessages[i].role === 'assistant') {
                        lastAssistantId = chatMessages[i].id
                        break
                      }
                    }
                    return chatMessages.map((msg) => (
                    <div
                      key={msg.id}
                      className={msg.role === 'user' ? 'flex justify-end' : ''}
                    >
                      <div
                        className={`${msg.role === 'user' ? 'max-w-[85%] rounded-2xl bg-purple-500 text-white px-3.5 py-2.5' : 'w-full rounded-xl border border-border/50 bg-muted/50 px-3.5 py-2.5'} text-sm leading-relaxed`}
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
                        {msg.role === 'assistant' && !msg.streaming && (
                          <div className="mt-1.5 flex items-center gap-1">
                            {msg.content && (
                              <button
                                className="flex h-7 items-center gap-1 rounded-md px-2 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground active:scale-95"
                                onClick={() => {
                                  const idx = chatMessages.indexOf(msg)
                                  const userMsg = idx > 0 ? chatMessages[idx - 1] : null
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
                                disabled={chatLoading}
                                title="重新生成"
                              >
                                <RefreshCw className="h-3.5 w-3.5" />
                                <span>重新生成</span>
                              </button>
                            )}
                          </div>
                        )}
                      </div>
                    </div>
                  ))})()}
                  <div ref={messagesEndRef} />
                </div>
              )}
            </div>
            )}

            {/* 预设问题（输入框上方，可左右滑动） */}
            <div className="shrink-0 px-4 pt-2">
              <div className="flex gap-2 overflow-x-auto scrollbar-none pb-1">
                {ADMIN_QUICK_PROMPTS.map((hint) => (
                  <button
                    key={hint}
                    className="shrink-0 whitespace-nowrap rounded-full border border-purple-200 bg-purple-50 px-3 py-1.5 text-xs text-purple-600 transition-colors hover:border-purple-400 hover:bg-purple-100 dark:border-purple-800 dark:bg-purple-950/30 dark:text-purple-400 dark:hover:bg-purple-950/50 active:scale-[0.97]"
                    onClick={() => handleChatSend(hint)}
                    disabled={chatLoading}
                  >
                    {hint}
                  </button>
                ))}
              </div>
            </div>

            {/* 输入区域 */}
            <div className="shrink-0 border-t px-4 py-3">
              <div className="flex items-center gap-2">
                <input
                  type="text"
                  value={chatInput}
                  onChange={(e) => setChatInput(e.target.value)}
                  onKeyDown={handleChatKeyDown}
                  placeholder="告诉小管你要做什么..."
                  disabled={chatLoading}
                  className="flex-1 rounded-full bg-muted px-4 py-2.5 text-sm outline-none placeholder:text-muted-foreground disabled:opacity-50"
                />
                <button
                  onClick={() => handleChatSend()}
                  disabled={chatLoading || !chatInput.trim()}
                  className="flex h-10 w-10 items-center justify-center rounded-full bg-purple-500 text-white disabled:opacity-50 transition-transform active:scale-95"
                >
                  {chatLoading ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <Send className="h-4 w-4" />
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
