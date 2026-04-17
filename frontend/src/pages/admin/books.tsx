import { useState, useRef, useEffect, useCallback } from 'react'
import { ArrowLeft, RefreshCw, Upload, Scan, BookOpen, FileText, File, CheckCircle2, XCircle, ChevronDown, ChevronUp, AlertTriangle, Bot, User, Send, Loader2, Sparkles, X, MessageCircle } from 'lucide-react'
import DraggableFab from '@/components/DraggableFab'
import { useNavigate } from 'react-router-dom'
import { scanBooksStream, getScanStatus, resetScanStatus, uploadBook } from '@/api/book'
import type { ScanProgress, ScanResult, ScanError } from '@/api/book'
import { createAdminSession, streamAdminChat, getAdminHistory, getAdminSessions, deleteAdminSession } from '@/api/adminAi'
import type { AiMessage } from '@/types/ai'
import { toast } from 'sonner'

/** 管理员快捷指令 */
const ADMIN_QUICK_PROMPTS = [
  '帮我查看阅读排行榜',
  '搜索《三体》',
  '最近有什么热门书？',
  '帮我找找有没有重复的书',
]

/** 简易 Markdown 渲染 — 支持 [BOOK:id=X]《书名》 图书链接 */
function renderMarkdown(text: string) {
  return text
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/\[BOOK:id=(\d+)\]《(.+?)》/g, (_match, bookId, title) => {
      return `<span class="inline-flex items-center gap-1 rounded-md bg-primary/10 px-2 py-0.5 align-middle">` +
        `<a href="/book/${bookId}" class="text-primary font-medium hover:underline">《${title}》</a>` +
        `</span>`
    })
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    .replace(/`(.+?)`/g, '<code class="rounded bg-muted px-1 py-0.5 text-xs">$1</code>')
    .replace(/《(.+?)》/g, '<span class="text-primary font-medium">《$1》</span>')
    .replace(/\n/g, '<br/>')
}

export default function AdminBooksPage() {
  const navigate = useNavigate()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const chatAbortRef = useRef<AbortController | null>(null)

  // 扫描状态
  const [scanning, setScanning] = useState(false)
  const [progress, setProgress] = useState<ScanProgress | null>(null)
  const [scanResult, setScanResult] = useState<ScanResult | null>(null)
  const [uploading, setUploading] = useState(false)
  const [uploadTitle, setUploadTitle] = useState('')
  const [showErrors, setShowErrors] = useState(false)

  // AI 管理员对话状态
  const [showChat, setShowChat] = useState(false)
  const [chatMessages, setChatMessages] = useState<AiMessage[]>([])
  const [chatInput, setChatInput] = useState('')
  const [chatLoading, setChatLoading] = useState(false)
  const [chatSessionId, setChatSessionId] = useState('')

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
        const res = await getScanStatus()
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
    }, 1000)
  }, [stopPolling])

  // 页面加载检查
  useEffect(() => {
    getScanStatus().then(res => {
      if (res.scanning) {
        setScanning(true)
        setProgress({
          current: res.current || 0,
          total: res.total || 0,
          added: res.added || 0,
          updated: res.updated || 0,
          skipped: res.skipped || 0,
          failed: res.failed || 0,
          errors: res.errors || [],
          currentFile: res.currentFile || '恢复扫描连接中...',
          status: 'scanning',
        })
        startScanStream()
        startPolling()
      }
    }).catch(() => {})
    return () => { stopPolling(); chatAbortRef.current?.abort() }
  }, [])

  // 滚动到底部
  useEffect(() => {
    if (showChat) {
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
  }, [chatMessages, showChat])

  const startScanStream = () => {
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
        const failMsg = data.failed > 0 ? `，${data.failed} 本失败` : ''
        toast.success(`扫描完成：新增 ${data.added} 本，更新 ${data.updated} 本，跳过 ${data.skipped} 本${failMsg}`)
      },
      (err) => {
        console.warn('SSE 断开，切换轮询:', err.message)
        startPolling()
      },
    )
    startPolling()
  }

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
    try { await resetScanStatus() } catch {}
    setScanning(false)
    setProgress(null)
    toast.info('已重置扫描状态')
  }

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    const ext = file.name.split('.').pop()?.toUpperCase()
    if (!['EPUB', 'PDF', 'TXT'].includes(ext || '')) {
      toast.error('仅支持 EPUB/PDF/TXT 格式')
      return
    }
    setUploading(true)
    try {
      const result = await uploadBook(file, uploadTitle || undefined)
      toast.success(`上传成功：《${result.title}》`)
      setUploadTitle('')
    } catch (err: any) {
      toast.error(err.message || '上传失败')
    } finally {
      setUploading(false)
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  // ==================== AI 管理员对话 ====================

  const handleChatSend = useCallback(async (text?: string) => {
    const message = (text || chatInput).trim()
    if (!message || chatLoading) return

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

    const controller = streamAdminChat(
      { sessionId, message },
      (chunk) => {
        setChatMessages(prev =>
          prev.map(m => m.id === assistantMsg.id ? { ...m, content: m.content + chunk } : m)
        )
      },
      () => {
        setChatMessages(prev =>
          prev.map(m => m.id === assistantMsg.id ? { ...m, streaming: false } : m)
        )
        setChatLoading(false)
      },
      (error) => {
        setChatMessages(prev =>
          prev.map(m =>
            m.id === assistantMsg.id
              ? { ...m, content: `抱歉，AI 助理暂时无法回复：${error.message}`, streaming: false }
              : m
          )
        )
        setChatLoading(false)
      },
    )
    chatAbortRef.current = controller
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

  // ==================== 渲染辅助 ====================

  const formatIcon = (fmt: string) => {
    switch (fmt) {
      case 'PDF': return <File className="h-5 w-5 text-red-500" />
      case 'EPUB': return <BookOpen className="h-5 w-5 text-blue-500" />
      case 'TXT': return <FileText className="h-5 w-5 text-green-500" />
      default: return <File className="h-5 w-5" />
    }
  }

  const progressPercent = progress && progress.total > 0
    ? Math.round((progress.current / progress.total) * 100)
    : 0

  const currentErrors: ScanError[] = scanResult?.errors || progress?.errors || []

  return (
    <div className="min-h-screen bg-background">
      {/* 顶部 */}
      <header className="sticky top-0 z-10 border-b border-border/50 bg-background/80 backdrop-blur-xl">
        <div className="flex items-center gap-3 px-4 py-3">
          <button onClick={() => navigate(-1)} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted">
            <ArrowLeft className="h-5 w-5" />
          </button>
          <h1 className="text-lg font-semibold">图书管理</h1>
        </div>
      </header>

      <div className="px-4 py-4 space-y-4">
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
            <span className="rounded bg-blue-50 px-2 py-1 text-blue-600">PDF</span>
            <span className="rounded bg-green-50 px-2 py-1 text-green-600">TXT</span>
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
                <span className="text-blue-600">↑{progress.updated} 更新</span>
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
            <div className="mb-3 max-h-48 overflow-y-auto rounded-lg bg-red-50 p-3 text-xs space-y-2 dark:bg-red-950/20">
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
                    <div className="mt-2 max-h-48 overflow-y-auto rounded-lg bg-red-50 p-3 text-xs space-y-2 dark:bg-red-950/20">
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
              <p className="text-xs text-muted-foreground">手动上传 EPUB/PDF/TXT 文件</p>
            </div>
          </div>
          <div className="mb-3">
            <input
              type="text"
              value={uploadTitle}
              onChange={(e) => setUploadTitle(e.target.value)}
              placeholder="自定义书名（可选，默认使用文件名）"
              className="w-full rounded-lg border bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-primary/20"
            />
          </div>
          <input
            ref={fileInputRef}
            type="file"
            accept=".epub,.pdf,.txt"
            onChange={handleUpload}
            className="hidden"
          />
          <button
            onClick={() => fileInputRef.current?.click()}
            disabled={uploading}
            className="flex w-full items-center justify-center gap-2 rounded-xl bg-orange-500 py-2.5 text-sm font-medium text-white disabled:opacity-50"
          >
            <Upload className={`h-4 w-4 ${uploading ? 'animate-bounce' : ''}`} />
            {uploading ? '上传中...' : '选择文件上传'}
          </button>
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
            <li className="flex items-center gap-1">
              <Sparkles className="h-3 w-3 text-purple-500" />
              <span>点击右下角紫色圆圈唤醒 AI 管理员，用自然语言管理图书</span>
            </li>
          </ul>
        </section>
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

      {/* ===== AI 对话弹窗 ===== */}
      {showChat && (
        <div className="fixed inset-0 z-50 flex items-end justify-end p-5 sm:items-center sm:justify-center">
          {/* 遮罩（仅移动端） */}
          <div
            className="absolute inset-0 bg-black/40 sm:hidden"
            onClick={() => setShowChat(false)}
          />

          {/* 对话窗口 */}
          <div className="relative flex w-full max-w-md flex-col overflow-hidden rounded-2xl border bg-card shadow-2xl sm:max-h-[600px] max-h-[85vh]">
            {/* 标题栏 */}
            <div className="flex items-center gap-3 border-b px-4 py-3">
              <div className="flex h-9 w-9 items-center justify-center rounded-full bg-gradient-to-br from-purple-500 to-purple-600 text-white">
                <Bot className="h-5 w-5" />
              </div>
              <div className="flex-1 min-w-0">
                <h3 className="text-sm font-semibold">AI 图书管理员</h3>
                <p className="text-xs text-muted-foreground">小管随时为你服务</p>
              </div>
              {chatSessionId && (
                <button
                  onClick={handleNewAdminChat}
                  className="flex h-8 w-8 items-center justify-center rounded-full bg-muted hover:bg-muted/80 transition-colors"
                  title="新对话"
                >
                  <Sparkles className="h-4 w-4" />
                </button>
              )}
              <button
                onClick={() => setShowChat(false)}
                className="flex h-8 w-8 items-center justify-center rounded-full bg-muted hover:bg-muted/80 transition-colors"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            {/* 消息区域 */}
            <div className="flex-1 overflow-y-auto p-4 space-y-3">
              {chatMessages.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-8 text-center">
                  <div className="mb-3 flex h-14 w-14 items-center justify-center rounded-full bg-purple-50 dark:bg-purple-900/20">
                    <Bot className="h-7 w-7 text-purple-500" />
                  </div>
                  <h4 className="mb-1 text-sm font-semibold">你好，我是小管</h4>
                  <p className="mb-5 text-xs text-muted-foreground">AI 图书管理员，帮你高效管理图书库</p>
                  <div className="flex flex-col gap-2 w-full">
                    {ADMIN_QUICK_PROMPTS.map((hint) => (
                      <button
                        key={hint}
                        className="w-full rounded-xl border border-purple-200 px-4 py-2.5 text-sm text-purple-600 transition-colors hover:border-purple-400 hover:bg-purple-50 dark:border-purple-800 dark:text-purple-400 dark:hover:bg-purple-950/30 text-left"
                        onClick={() => handleChatSend(hint)}
                        disabled={chatLoading}
                      >
                        {hint}
                      </button>
                    ))}
                  </div>
                </div>
              ) : (
                <div className="space-y-3">
                  {chatMessages.map((msg) => (
                    <div
                      key={msg.id}
                      className={`flex gap-2.5 ${msg.role === 'user' ? 'flex-row-reverse' : 'flex-row'}`}
                    >
                      <div
                        className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs ${
                          msg.role === 'user'
                            ? 'bg-purple-500 text-white'
                            : 'bg-purple-100 text-purple-600 dark:bg-purple-900/30 dark:text-purple-400'
                        }`}
                      >
                        {msg.role === 'user' ? <User className="h-3.5 w-3.5" /> : <Bot className="h-3.5 w-3.5" />}
                      </div>
                      <div
                        className={`max-w-[80%] rounded-2xl px-3.5 py-2.5 text-sm leading-relaxed ${
                          msg.role === 'user'
                            ? 'bg-purple-500 text-white'
                            : 'bg-muted'
                        }`}
                      >
                        {msg.role === 'user' ? (
                          <p className="whitespace-pre-wrap">{msg.content}</p>
                        ) : (
                          <div
                            className="prose-sm text-justify"
                            dangerouslySetInnerHTML={{ __html: renderMarkdown(msg.content) }}
                          />
                        )}
                        {msg.streaming && (
                          <span className="ml-0.5 inline-block h-4 w-1 animate-pulse bg-foreground/50" />
                        )}
                      </div>
                    </div>
                  ))}
                  <div ref={messagesEndRef} />
                </div>
              )}
            </div>

            {/* 输入区域 */}
            <div className="border-t px-4 py-3">
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
              <div className="mt-2 flex flex-wrap gap-1.5 text-[10px] text-muted-foreground">
                <span className="rounded bg-muted px-1.5 py-0.5">"帮我删除张三的所有书"</span>
                <span className="rounded bg-muted px-1.5 py-0.5">"《三体》有重复吗？"</span>
                <span className="rounded bg-muted px-1.5 py-0.5">"搜索评分最高的书"</span>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
