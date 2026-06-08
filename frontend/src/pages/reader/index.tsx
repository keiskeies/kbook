import { useEffect, useState, useRef, useCallback, useMemo } from 'react'
import { useParams } from 'react-router-dom'
import { useGoBack } from '@/hooks/useGoBack'
import {
  ArrowLeft, Settings, List,
  Loader2, AlertCircle, Volume2, VolumeX,
  ChevronLeft, ChevronRight,
} from 'lucide-react'
import { useReaderStore } from '@/store/reader'
import { useProgressStore } from '@/store/progress'
import { getBook, updateBookCover } from '@/api/book'
import { getProgress } from '@/api/progress'
import { READER_THEMES, STORAGE_KEYS } from '@/constants'
import type { Book } from '@/types/book'
import { useTxtReader } from '@/hooks/useTxtReader'
import { useEpubReader } from '@/hooks/useEpubReader'
import { usePdfReader } from '@/hooks/usePdfReader'
import { useTtsReader } from '@/hooks/useTtsReader'
import { ttsService } from '@/utils/tts'
import { useAuthStore } from '@/store/auth'
import { toast } from 'sonner'
import TxtRenderer from '@/components/reader/TxtRenderer'
import EpubRenderer from '@/components/reader/EpubRenderer'
import PdfRenderer from '@/components/reader/PdfRenderer'
import SettingsPanel from '@/components/reader/SettingsPanel'
import TocPanel from '@/components/reader/TocPanel'
import ImageViewer from '@/components/common/ImageViewer'

export default function ReaderPage() {
  const { bookId } = useParams<{ bookId: string }>()
  const goBack = useGoBack()
  const { settings, showSettings, toggleSettings, showToc, toggleToc, setCurrentBookId, isSystemDark } = useReaderStore()
  const [book, setBook] = useState<Book | null>(null)
  const [initialPosition, setInitialPosition] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [viewerImageSrc, setViewerImageSrc] = useState<string | null>(null)
  const [showImageViewer, setShowImageViewer] = useState(false)

  const { userInfo } = useAuthStore()
  const isAdmin = userInfo?.role === 'ADMIN'

  // 用 ref 保存最新的进度信息，确保卸载时能获取到最新值
  const progressRef = useRef({ progress: 0, currentPosition: '' })

  // 夜间模式下强制使用 DARK 主题
  const theme = READER_THEMES[isSystemDark ? 'DARK' : settings.themeKey]

  const id = Number(bookId) || 0

  useEffect(() => {
    if (!id) return
    setCurrentBookId(id)
    Promise.all([
      getBook(id),
      getProgress(id).catch(() => null),
    ]).then(([bookRes, progressRes]) => {
      setBook(bookRes as unknown as Book)
      const serverPos = (progressRes as any)?.currentPosition || null
      if (serverPos) {
        setInitialPosition(serverPos)
      } else {
        try {
          const cached = localStorage.getItem(STORAGE_KEYS.LOCAL_PROGRESS)
          if (cached) {
            const allProgress = JSON.parse(cached) as Record<string, any>
            const local = allProgress[id]
            if (local?.currentPosition) {
              setInitialPosition(local.currentPosition)
            }
          }
        } catch { /* ignore */ }
      }
      setLoading(false)
    })
  }, [id, setCurrentBookId])

  const txtReader = useTxtReader({ bookId: id, initialPosition })
  const epubGoPageRef = useRef<(dir: 'next' | 'prev') => void>(() => {})

  const handleEpubClick = useCallback(() => {}, [])

  const handleEpubImageClick = useCallback((src: string) => {
    setViewerImageSrc(src)
    setShowImageViewer(true)
  }, [])

  const handleSetAsCover = useCallback(async () => {
    if (!viewerImageSrc || !book) return
    try {
      // 将图片 URL 转为 File 对象上传
      const response = await fetch(viewerImageSrc)
      const blob = await response.blob()
      const file = new File([blob], 'cover.png', { type: blob.type || 'image/png' })
      const updated = await updateBookCover(book.id, file)
      setBook(updated as unknown as Book)
      toast.success('已设为封面')
    } catch (err: any) {
      toast.error(err?.message || '设置封面失败')
    }
  }, [viewerImageSrc, book])

  const epubReader = useEpubReader({
    bookId: id,
    initialPosition,
    isSystemDark,
    onContentClick: handleEpubClick,
    onImageClick: handleEpubImageClick,
  })
  const pdfReader = usePdfReader({ bookId: id, initialPosition })

  useEffect(() => {
    epubGoPageRef.current = epubReader.goPage
  }, [epubReader.goPage])

  const format = book?.format || 'TXT'

  const readerState = format === 'TXT' ? txtReader
    : format === 'EPUB' ? epubReader
    : pdfReader

  const progress = readerState.progress
  const isTocAvailable = format !== 'PDF' && (txtReader.chapters.length > 1 || epubReader.chapters.length > 0)
  const chapters = format === 'TXT' ? txtReader.chapters
    : format === 'EPUB' ? epubReader.chapters
    : []
  const currentChapterIndex = format === 'TXT' ? txtReader.currentChapterIndex
    : format === 'EPUB' ? epubReader.currentChapterIndex
    : 0

  // ===== TTS 朗读功能 =====
  const supportsTts = format === 'TXT' || format === 'EPUB'

  // TXT: 全书段落（按行分割，过滤空行）
  const txtAllSegments = useMemo(() => {
    if (format !== 'TXT') return []
    return txtReader.rawText.split(/\n+/).filter((p: string) => p.trim())
  }, [format, txtReader.rawText])

  // EPUB: 异步提取全书段落（遍历 spine 逐章加载文本）
  const getEpubAllSegmentsAsync = useCallback(async (): Promise<string[]> => {
    if (format !== 'EPUB') return []
    const epubBook = epubReader.epubBookRef?.current
    if (!epubBook) return []

    const spine = epubBook.spine
    if (!spine) return []

    const allSegments: string[] = []
    const spineItems: any[] = spine.spineItems || []

    // 逐章串行加载，避免并发内存问题
    for (let i = 0; i < spineItems.length; i++) {
      try {
        const section = spine.get(i)
        if (!section || !section.load) continue

        // 加载章节内容，传入 book.load 作为请求方法
        await section.load(epubBook.load.bind(epubBook))
        // section.document 是完整的 XML Document
        const doc = section.document
        if (doc) {
          const selector = 'p, div, section, article, h1, h2, h3, h4, h5, h6, span'
          const textNodes = doc.querySelectorAll
            ? doc.querySelectorAll(selector)
            : doc.ownerDocument
              ? doc.ownerDocument.querySelectorAll(selector)
              : null

          if (textNodes) {
            textNodes.forEach((node: any) => {
              const text = node.textContent?.trim()
              if (text && text.length > 0) {
                allSegments.push(text)
              }
            })
          }
        }
        // 卸载章节以释放内存
        section.unload()
      } catch (e) {
        console.warn(`TTS: 跳过章节 ${i}`, e)
      }
    }

    return allSegments
  }, [format, epubReader.epubBookRef])

  // TXT 同步获取全量段落
  const getTxtAllSegments = useCallback(() => {
    return txtAllSegments
  }, [txtAllSegments])

  const ttsReader = useTtsReader({
    bookId: id,
    bookTitle: book?.title || '朗读',
    getAllSegments: format === 'TXT' ? getTxtAllSegments : undefined,
    getAllSegmentsAsync: format === 'EPUB' ? getEpubAllSegmentsAsync : undefined,
  })

  // TTS 朗读按钮点击
  const handleTtsToggle = useCallback(() => {
    if (!ttsService.supported) return
    // 加载中不响应
    if (ttsReader.segmentsLoading) return
    if (ttsReader.isReading) {
      ttsReader.pauseReading()
    } else if (ttsReader.isPaused) {
      ttsReader.resumeReading()
    } else {
      ttsReader.startReading()
    }
  }, [ttsReader])

  // TTS 停止按钮
  const handleTtsStop = useCallback(() => {
    ttsReader.stopReading()
  }, [ttsReader])

  // 实时更新进度 ref
  useEffect(() => {
    const currentPosition = format === 'TXT' ? String(txtReader.charOffset)
      : format === 'EPUB' ? epubReader.currentChapterId
      : format === 'PDF' ? String(pdfReader.currentPage)
      : ''
    
    progressRef.current = {
      progress: readerState.progress,
      currentPosition,
    }
  }, [format, readerState.progress, txtReader.charOffset, epubReader.currentChapterId, pdfReader.currentPage])

  // 页面卸载时立即保存阅读进度
  useEffect(() => {
    return () => {
      const { progress, currentPosition } = progressRef.current
      
      if (currentPosition && id > 0) {
        try {
          const cached = localStorage.getItem(STORAGE_KEYS.LOCAL_PROGRESS)
          const allProgress = cached ? JSON.parse(cached) as Record<string, any> : {}
          allProgress[id] = {
            id: 0,
            userId: 0,
            bookId: id,
            progress,
            currentPosition,
            updatedAt: new Date().toISOString(),
          }
          localStorage.setItem(STORAGE_KEYS.LOCAL_PROGRESS, JSON.stringify(allProgress))
        } catch { /* ignore */ }

        useProgressStore.getState().reportProgress(id, progress, currentPosition)
      }
    }
  }, [id])

  // TTS 当前朗读的段索引
  const ttsSegmentIndex = ttsReader.isCurrentBook ? ttsReader.currentSegmentIndex : -1

  // ===== 键盘快捷键（PC 端翻页、返回） =====
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // 如果焦点在输入框中，不拦截
      const tag = (e.target as HTMLElement).tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return

      if (e.key === 'Escape') {
        e.preventDefault()
        goBack()
        return
      }

      if (e.key === 'ArrowLeft' || e.key === 'ArrowUp' || e.key === 'PageUp') {
        e.preventDefault()
        if (format === 'TXT' && txtReader.currentChapterIndex > 0) txtReader.goToChapter(txtReader.currentChapterIndex - 1)
        else if (format === 'EPUB') epubGoPageRef.current('prev')
        else if (format === 'PDF' && pdfReader.currentPage > 1) pdfReader.goToPage?.(pdfReader.currentPage - 1)
        return
      }

      if (e.key === 'ArrowRight' || e.key === 'ArrowDown' || e.key === 'PageDown' || e.key === ' ') {
        e.preventDefault()
        if (format === 'TXT' && txtReader.currentChapterIndex < txtReader.chapters.length - 1) txtReader.goToChapter(txtReader.currentChapterIndex + 1)
        else if (format === 'EPUB') epubGoPageRef.current('next')
        else if (format === 'PDF' && pdfReader.currentPage < pdfReader.totalPages) pdfReader.goToPage?.(pdfReader.currentPage + 1)
        return
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [format, goBack, txtReader, epubGoPageRef, pdfReader])

  if (readerState.error) {
    return (
      <div className="flex absolute inset-0 items-center justify-center" style={{ backgroundColor: theme.bg }}>
        <div className="px-6 text-center">
          <AlertCircle className="mx-auto h-10 w-10 text-destructive" />
          <p className="mt-3 text-sm text-foreground">{readerState.error}</p>
          <button
            onClick={() => goBack()}
            className="mt-4 rounded-lg bg-primary px-6 py-2 text-sm text-primary-foreground"
          >
            返回
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col select-none" style={{ backgroundColor: theme.bg }}>
      {/* 顶部工具栏 */}
      <div
        className="shrink-0 z-40 flex items-center gap-3 border-b px-4 py-3 backdrop-blur-xl"
        style={{ backgroundColor: theme.bg + 'e6', color: theme.fg, borderColor: theme.fg + '15' }}
        onClick={(e) => e.stopPropagation()}
      >
        <button onClick={() => goBack()} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-sm font-medium">{book?.title || '阅读'}</h1>
        </div>
        {/* TTS 朗读按钮 */}
        {supportsTts && (
          <div className="flex items-center gap-1">
            {(ttsReader.isReading || ttsReader.segmentsLoading) && (
              <button
                onClick={handleTtsStop}
                className="flex h-8 w-8 items-center justify-center rounded-full hover:bg-muted text-destructive"
                title="停止朗读"
              >
                <VolumeX className="h-5 w-5" />
              </button>
            )}
            <button
              onClick={handleTtsToggle}
              disabled={ttsReader.segmentsLoading}
              className={`flex h-8 w-8 items-center justify-center rounded-full hover:bg-muted disabled:opacity-50 ${
                ttsReader.isReading ? 'text-primary' : ttsReader.isPaused ? 'text-yellow-500' : ''
              }`}
              title={ttsReader.segmentsLoading ? '加载中...' : ttsReader.isReading ? '暂停朗读' : ttsReader.isPaused ? '继续朗读' : '开始朗读'}
            >
              {ttsReader.segmentsLoading ? (
                <Loader2 className="h-5 w-5 animate-spin" />
              ) : ttsReader.isReading ? (
                <Volume2 className="h-5 w-5 animate-pulse" />
              ) : (
                <Volume2 className="h-5 w-5" />
              )}
            </button>
          </div>
        )}
        {isTocAvailable && (
          <button onClick={toggleToc} className="flex h-8 w-8 items-center justify-center rounded-full hover:bg-muted">
            <List className="h-5 w-5" />
          </button>
        )}
        <button onClick={toggleSettings} className="flex h-8 w-8 items-center justify-center rounded-xl hover:bg-muted">
          <Settings className="h-5 w-5" />
        </button>
      </div>

      {/* 内容区域 */}
      <div
        className="flex-1 min-h-0 flex items-stretch"
        onContextMenu={(e) => e.preventDefault()}
        style={{ touchAction: 'manipulation', WebkitUserSelect: 'none', userSelect: 'none' as any }}
      >
        {/* PC端左侧翻页按钮 */}
        <button
          onClick={() => {
            if (format === 'TXT' && txtReader.currentChapterIndex > 0) txtReader.goToChapter(txtReader.currentChapterIndex - 1)
            else if (format === 'EPUB') epubGoPageRef.current('prev')
            else if (format === 'PDF' && pdfReader.currentPage > 1) pdfReader.goToPage?.(pdfReader.currentPage - 1)
          }}
          className="hidden md:flex md:w-12 lg:w-16 items-center justify-center hover:bg-black/5 dark:hover:bg-white/5 transition-colors shrink-0"
          style={{ color: theme.fg + '40' }}
          title="上一页 (←)"
        >
          <ChevronLeft className="h-6 w-6" />
        </button>

        {/* 阅读内容 — PC端限宽 */}
        <div className="flex-1 min-h-0 min-w-0">
          <div className="mx-auto h-full max-w-3xl reader-content">
              {(loading || readerState.loading) && (
                <div className="absolute inset-0 z-50 flex items-center justify-center" style={{ backgroundColor: theme.bg }}>
                  <div className="text-center">
                    <Loader2 className="mx-auto h-8 w-8 animate-spin text-primary" />
                    <p className="mt-3 text-sm text-muted-foreground">加载中...</p>
                  </div>
                </div>
              )}
              {format === 'TXT' && (
                <TxtRenderer
                  text={txtReader.currentChapterText}
                  chapters={txtReader.chapters}
                  currentChapterIndex={txtReader.currentChapterIndex}
                  containerRef={txtReader.containerRef}
                  onScroll={txtReader.handleScroll}
                  ttsSegmentIndex={ttsSegmentIndex}
                />
              )}
              {format === 'EPUB' && (
                <EpubRenderer
                  chapters={epubReader.chapters}
                  currentChapterIndex={epubReader.currentChapterIndex}
                  containerRef={epubReader.containerRef}
                />
              )}
              {format === 'PDF' && (
                <PdfRenderer
                  totalPages={pdfReader.totalPages}
                  currentPage={pdfReader.currentPage}
                  scale={settings.pdfScale}
                  containerRef={pdfReader.containerRef}
                  onScroll={pdfReader.handleScroll}
                  onRenderPage={pdfReader.renderPage}
                  rendering={pdfReader.rendering}
                />
              )}
            </div>
          </div>

          {/* PC端右侧翻页按钮 */}
          <button
            onClick={() => {
              if (format === 'TXT' && txtReader.currentChapterIndex < txtReader.chapters.length - 1) txtReader.goToChapter(txtReader.currentChapterIndex + 1)
              else if (format === 'EPUB') epubGoPageRef.current('next')
              else if (format === 'PDF' && pdfReader.currentPage < pdfReader.totalPages) pdfReader.goToPage?.(pdfReader.currentPage + 1)
            }}
            className="hidden md:flex md:w-12 lg:w-16 items-center justify-center hover:bg-black/5 dark:hover:bg-white/5 transition-colors shrink-0"
            style={{ color: theme.fg + '40' }}
            title="下一页 (→)"
          >
            <ChevronRight className="h-6 w-6" />
          </button>
      </div>

      {/* 底部 — 移动端EPUB翻页+进度 / 其他仅进度 */}
      <div
        className="md:hidden shrink-0 flex items-center justify-between px-3 py-2 z-30"
        style={{ backgroundColor: theme.bg + 'f0', borderTop: `1px solid ${theme.fg}15` }}
        onClick={(e) => e.stopPropagation()}
      >
        <button
          onClick={() => {
            if (format === 'TXT' && txtReader.currentChapterIndex > 0) txtReader.goToChapter(txtReader.currentChapterIndex - 1)
            else if (format === 'EPUB') epubGoPageRef.current('prev')
            else if (format === 'PDF' && pdfReader.currentPage > 1) pdfReader.goToPage?.(pdfReader.currentPage - 1)
          }}
          className="flex items-center gap-1 rounded-lg px-3 py-1.5 text-sm font-medium active:scale-95 transition-transform"
          style={{ color: theme.fg + '80' }}
        >
          <ChevronLeft className="h-4 w-4" />
          上一页
        </button>
        <span
          className="text-xs font-medium opacity-50"
          style={{ color: theme.fg }}
        >
          {Math.round(progress * 100)}%
        </span>
        <button
          onClick={() => {
            if (format === 'TXT' && txtReader.currentChapterIndex < txtReader.chapters.length - 1) txtReader.goToChapter(txtReader.currentChapterIndex + 1)
            else if (format === 'EPUB') epubGoPageRef.current('next')
            else if (format === 'PDF' && pdfReader.currentPage < pdfReader.totalPages) pdfReader.goToPage?.(pdfReader.currentPage + 1)
          }}
          className="flex items-center gap-1 rounded-lg px-3 py-1.5 text-sm font-medium active:scale-95 transition-transform"
          style={{ color: theme.fg + '80' }}
        >
          下一页
          <ChevronRight className="h-4 w-4" />
        </button>
      </div>

      {/* PC端底部进度栏 */}
      <div
        className="hidden md:flex shrink-0 h-8 items-center justify-center"
        style={{ borderTop: `1px solid ${theme.fg}10` }}
      >
        <span
          className="text-xs font-medium opacity-40"
          style={{ color: theme.fg }}
        >
          {Math.round(progress * 100)}%
        </span>
      </div>

      {showToc && isTocAvailable && (
        <TocPanel
          chapters={chapters}
          currentIndex={currentChapterIndex}
          onJump={(i) => (readerState as any).goToChapter?.(i)}
          onClose={toggleToc}
        />
      )}

      {showSettings && <SettingsPanel isSystemDark={isSystemDark} />}

      {/* 图片全屏查看 */}
      <ImageViewer
        src={viewerImageSrc}
        alt="书籍图片"
        isOpen={showImageViewer}
        onClose={() => setShowImageViewer(false)}
        showAdminActions={isAdmin}
        adminActionLabel="设为封面"
        onAdminAction={handleSetAsCover}
      />
    </div>
  )
}
