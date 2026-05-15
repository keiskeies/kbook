import { useEffect, useState, useRef, useCallback, useMemo } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  ArrowLeft, Settings, List,
  Loader2, AlertCircle, Volume2, VolumeX,
} from 'lucide-react'
import { useReaderStore } from '@/store/reader'
import { useProgressStore } from '@/store/progress'
import { getBook } from '@/api/book'
import { getProgress } from '@/api/progress'
import { READER_THEMES, STORAGE_KEYS } from '@/constants'
import type { Book } from '@/types/book'
import { useTxtReader } from '@/hooks/useTxtReader'
import { useEpubReader } from '@/hooks/useEpubReader'
import { usePdfReader } from '@/hooks/usePdfReader'
import { useTtsReader } from '@/hooks/useTtsReader'
import { ttsService } from '@/utils/tts'
import TxtRenderer from '@/components/reader/TxtRenderer'
import EpubRenderer from '@/components/reader/EpubRenderer'
import PdfRenderer from '@/components/reader/PdfRenderer'
import SettingsPanel from '@/components/reader/SettingsPanel'
import TocPanel from '@/components/reader/TocPanel'
import TtsFloatPlayer from '@/components/reader/TtsFloatPlayer'

/** 工具栏自动隐藏延迟（毫秒） */
const TOOLBAR_AUTO_HIDE_DELAY = 5000

export default function ReaderPage() {
  const { bookId } = useParams<{ bookId: string }>()
  const navigate = useNavigate()
  const { settings, showSettings, toggleSettings, showToc, toggleToc, setCurrentBookId, isSystemDark } = useReaderStore()
  const [book, setBook] = useState<Book | null>(null)
  const [initialPosition, setInitialPosition] = useState<string | null>(null)
  const [showToolbar, setShowToolbar] = useState(true)
  const [loading, setLoading] = useState(true)
  const autoHideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  
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

  const epubReader = useEpubReader({
    bookId: id,
    initialPosition,
    isSystemDark,
    onContentClick: handleEpubClick,
  })
  epubGoPageRef.current = epubReader.goPage
  const pdfReader = usePdfReader({ bookId: id, initialPosition })

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

  if (readerState.error) {
    return (
      <div className="flex h-screen items-center justify-center" style={{ backgroundColor: theme.bg }}>
        <div className="px-6 text-center">
          <AlertCircle className="mx-auto h-10 w-10 text-destructive" />
          <p className="mt-3 text-sm text-foreground">{readerState.error}</p>
          <button
            onClick={() => navigate(-1)}
            className="mt-4 rounded-lg bg-primary px-6 py-2 text-sm text-primary-foreground"
          >
            返回
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="relative h-screen select-none" style={{ backgroundColor: theme.bg }}>
      {/* 顶部工具栏 */}
      <div
        className="fixed inset-x-0 top-0 z-40 flex items-center gap-3 border-b px-4 py-3 backdrop-blur-xl"
        style={{ backgroundColor: theme.bg + 'e6', color: theme.fg, borderColor: theme.fg + '15' }}
        onClick={(e) => e.stopPropagation()}
      >
        <button onClick={() => navigate(-1)} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted">
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
        className="h-full pt-[52px] pb-8"
        onContextMenu={(e) => e.preventDefault()}
        style={{ position: 'relative', touchAction: 'manipulation', WebkitUserSelect: 'none', userSelect: 'none' as any }}
      >
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

      {/* 底部进度 — 左下角小百分比 */}
      <div
        className="fixed bottom-2 left-2 z-30 pointer-events-none"
        onClick={(e) => e.stopPropagation()}
      >
        <span
          className="text-[10px] font-medium opacity-40 rounded px-1.5 py-0.5"
          style={{ color: theme.fg, backgroundColor: theme.bg + '60' }}
        >
          {Math.round(progress * 100)}%
        </span>
      </div>

      {showToc && isTocAvailable && (
        <TocPanel
          chapters={chapters}
          currentIndex={currentChapterIndex}
          onJump={(i) => readerState.goToChapter(i)}
          onClose={toggleToc}
        />
      )}

      {showSettings && <SettingsPanel isSystemDark={isSystemDark} />}
    </div>
  )
}
