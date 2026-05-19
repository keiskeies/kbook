import { useEffect, useRef, useCallback, useState } from 'react'
import { useReaderStore } from '@/store/reader'
import { useProgressStore } from '@/store/progress'
import { detectEncoding, decodeBuffer, splitChapters, findChapterIndex, calcTxtProgress } from '@/utils/txt-reader'
import type { TxtChapter } from '@/types/reader'
import { getBook } from '@/api/book'
import type { Book } from '@/types/book'

interface UseTxtReaderOptions {
  bookId: number
  initialPosition?: string | null
}

export function useTxtReader({ bookId, initialPosition }: UseTxtReaderOptions) {
  const [book, setBook] = useState<Book | null>(null)
  const [rawText, setRawText] = useState('')
  const [chapters, setChapters] = useState<TxtChapter[]>([])
  const [charOffset, setCharOffset] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [encoding, setEncoding] = useState('UTF-8')
  const [currentChapterIndex, setCurrentChapterIndex] = useState(0)
  const containerRef = useRef<HTMLDivElement>(null)
  const autoScrollRef = useRef<number | null>(null)
  const lastReportRef = useRef(0)
  const initialOffsetRef = useRef<number | null>(null)
  const { settings } = useReaderStore()
  const { reportProgress } = useProgressStore()

  // 加载图书文本
  useEffect(() => {
    let cancelled = false

    async function load() {
      try {
        const bookRes = await getBook(bookId)
        if (cancelled) return
        setBook(bookRes as unknown as Book)

        const token = localStorage.getItem(import.meta.env.VITE_TOKEN_KEY || 'kbook_token')
        const headers: Record<string, string> = {}
        if (token) headers['Authorization'] = `Bearer ${token}`
        const response = await fetch(`/api/books/${bookId}/file`, { headers })
        if (!response.ok) throw new Error('文件加载失败')

        const buffer = await response.arrayBuffer()
        if (cancelled) return

        const detected = detectEncoding(buffer)
        setEncoding(detected)
        const text = decodeBuffer(buffer, detected)

        const chaps = splitChapters(text)
        setRawText(text)
        // @ts-ignore
        setChapters(chaps)

        if (initialPosition) {
          const offset = parseInt(initialPosition, 10)
          if (!isNaN(offset) && offset < text.length) {
            setCharOffset(offset)
            setCurrentChapterIndex(findChapterIndex(chaps, offset))
            initialOffsetRef.current = offset
          }
        }

        setLoading(false)
      } catch (e: any) {
        if (!cancelled) {
          setError(e.message || '加载失败')
          setLoading(false)
        }
      }
    }

    load()
    return () => { cancelled = true }
  }, [bookId, initialPosition])

  const progress = calcTxtProgress(charOffset, rawText.length)

  // 恢复滚动位置（内容渲染后延迟执行，确保 DOM 已有高度）
  useEffect(() => {
    const offset = initialOffsetRef.current
    if (offset === null || !rawText || !containerRef.current) return
    initialOffsetRef.current = null
    const scrollRatio = offset / rawText.length
    // 等待 DOM 渲染完成后再滚动
    requestAnimationFrame(() => {
      if (containerRef.current) {
        const scrollHeight = containerRef.current.scrollHeight - containerRef.current.clientHeight
        containerRef.current.scrollTop = scrollRatio * scrollHeight
      }
    })
  }, [rawText])

  const handleScroll = useCallback(() => {
    if (!containerRef.current || !rawText) return
    const container = containerRef.current
    const scrollRatio = container.scrollTop / (container.scrollHeight - container.clientHeight || 1)
    const newOffset = Math.floor(scrollRatio * rawText.length)
    setCharOffset(newOffset)
    setCurrentChapterIndex(findChapterIndex(chapters, newOffset))

    const now = Date.now()
    if (now - lastReportRef.current > 3000) {
      lastReportRef.current = now
      reportProgress(bookId, calcTxtProgress(newOffset, rawText.length), String(newOffset))
    }
  }, [rawText, chapters, bookId, reportProgress])

  const goPage = useCallback((direction: 'next' | 'prev') => {
    if (!containerRef.current) return
    const container = containerRef.current
    const pageHeight = container.clientHeight
    const scrollAmount = pageHeight * 0.9

    container.scrollBy({
      top: direction === 'next' ? scrollAmount : -scrollAmount,
      behavior: settings.pageAnimation === 'none' ? 'auto' : 'smooth',
    })
  }, [settings.pageAnimation])

  const goToChapter = useCallback((chapterIndex: number) => {
    if (!containerRef.current || !chapters[chapterIndex]) return
    setCharOffset(chapters[chapterIndex].startOffset)
    setCurrentChapterIndex(chapterIndex)
    containerRef.current.scrollTop = 0
  }, [chapters])

  // 自动滚动
  useEffect(() => {
    if (autoScrollRef.current) {
      cancelAnimationFrame(autoScrollRef.current)
      autoScrollRef.current = null
    }
    if (settings.autoScrollSpeed <= 0 || !containerRef.current) return

    const container = containerRef.current
    const pixelsPerFrame = settings.autoScrollSpeed / 60

    function scroll() {
      container.scrollTop += pixelsPerFrame
      autoScrollRef.current = requestAnimationFrame(scroll)
    }
    autoScrollRef.current = requestAnimationFrame(scroll)
    return () => {
      if (autoScrollRef.current) cancelAnimationFrame(autoScrollRef.current)
    }
  }, [settings.autoScrollSpeed])

  // 切后台时上报进度
  useEffect(() => {
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'hidden' && rawText) {
        reportProgress(bookId, progress, String(charOffset))
      }
    }
    document.addEventListener('visibilitychange', handleVisibilityChange)
    return () => document.removeEventListener('visibilitychange', handleVisibilityChange)
  }, [bookId, progress, charOffset, rawText, reportProgress])

  const currentChapter = chapters[currentChapterIndex]
  const currentChapterText = currentChapter
    ? rawText.slice(currentChapter.startOffset, currentChapter.endOffset)
    : rawText

  return {
    book, rawText, chapters, currentChapterIndex, currentChapterText,
    charOffset, progress, loading, error, encoding,
    containerRef, handleScroll, goPage, goToChapter,
  }
}
