import { useState, useRef, useEffect, useCallback } from 'react'
import { ArrowLeft, RefreshCw, Upload, Scan, BookOpen, FileText, File, CheckCircle2, XCircle, ChevronDown, ChevronUp, AlertTriangle } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { scanBooksStream, getScanStatus, resetScanStatus, uploadBook } from '@/api/book'
import type { ScanProgress, ScanResult, ScanError } from '@/api/book'
import { toast } from 'sonner'

export default function AdminBooksPage() {
  const navigate = useNavigate()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const [scanning, setScanning] = useState(false)
  const [progress, setProgress] = useState<ScanProgress | null>(null)
  const [scanResult, setScanResult] = useState<ScanResult | null>(null)
  const [uploading, setUploading] = useState(false)
  const [uploadTitle, setUploadTitle] = useState('')
  const [showErrors, setShowErrors] = useState(false)

  // 停止轮询
  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current)
      pollRef.current = null
    }
  }, [])

  // 开始轮询扫描进度（SSE 断开后的后备）
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
          // 扫描已结束
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
      } catch {
        // 轮询失败不处理
      }
    }, 1000)
  }, [stopPolling])

  // 页面加载时检查是否有正在进行的扫描
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
        // 尝试重新连接 SSE
        startScanStream()
        // 同时启动轮询作为后备
        startPolling()
      }
    }).catch(() => {})
    return () => { stopPolling() }
  }, [])

  const startScanStream = () => {
    abortRef.current = scanBooksStream(
      (data) => {
        // SSE 收到进度，直接更新（轮询同时运行作为后备）
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
        // SSE 断开，确保轮询正在运行
        console.warn('SSE 连接断开，切换到轮询模式:', err.message)
        startPolling()
      },
    )
    // 同时启动轮询作为后备，确保 SSE 断开时仍能看到进度
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
    try {
      await resetScanStatus()
    } catch {}
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
      <header className="sticky top-0 z-10 border-b bg-background/95 backdrop-blur-sm">
        <div className="flex items-center gap-3 px-4 py-3">
          <button onClick={() => navigate(-1)} className="flex h-9 w-9 items-center justify-center rounded-full bg-muted">
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
              {/* 扫描中错误列表 */}
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

          {/* 错误详情展开 */}
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
              {/* 完成后错误列表 */}
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
            <li>AI 标签会在入库后自动异步生成</li>
            <li>并发线程数越大扫描越快，但占用资源越多</li>
            <li>单个文件出错不影响整体扫描，错误详情可展开查看</li>
          </ul>
        </section>
      </div>
    </div>
  )
}
