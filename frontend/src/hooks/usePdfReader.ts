import { useEffect, useRef, useCallback, useState } from 'react'
import { useReaderStore } from '@/store/reader'
import { useProgressStore } from '@/store/progress'
import type { Book } from '@/types/book'
import { getBook } from '@/api/book'

interface UsePdfReaderOptions {
  bookId: number
  initialPosition?: string | null
}

/**
 * PDF 阅读器 Hook
 * 采用 Canvas 渲染 + 虚拟滚动方案
 * 使用 pdfjs-dist 库进行 PDF 解析
 * 页面自动适配容器宽度，pdfScale 作为缩放倍率
 */
export function usePdfReader({ bookId, initialPosition }: UsePdfReaderOptions) {
  const [book, setBook] = useState<Book | null>(null)
  const [currentPage, setCurrentPage] = useState(1)
  const [totalPages, setTotalPages] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [rendering, setRendering] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)
  const lastReportRef = useRef(0)
  const pdfDocRef = useRef<any>(null)
  const { settings } = useReaderStore()
  const { reportProgress } = useProgressStore()

  // 加载 PDF
  useEffect(() => {
    let cancelled = false

    async function load() {
      try {
        const bookRes = await getBook(bookId)
        if (cancelled) return
        setBook(bookRes as unknown as Book)

        // 动态加载 pdfjs-dist
        // eslint-disable-next-line @typescript-eslint/ban-ts-comment
        // @ts-expect-error
        const pdfjsLib = await import('pdfjs-dist/build/pdf.mjs')
        pdfjsLib.GlobalWorkerOptions.workerSrc = new URL(
          'pdfjs-dist/build/pdf.worker.min.mjs',
          import.meta.url
        ).toString()

        const token = localStorage.getItem(import.meta.env.VITE_TOKEN_KEY || 'kbook_token')
        const headers: Record<string, string> = {}
        if (token) headers['Authorization'] = `Bearer ${token}`
        const response = await fetch(`/api/books/${bookId}/file`, { headers })
        const buffer = await response.arrayBuffer()
        const pdf = await pdfjsLib.getDocument({ data: buffer }).promise
        if (cancelled) return

        pdfDocRef.current = pdf
        setTotalPages(pdf.numPages)

        // 恢复页码
        if (initialPosition) {
          const page = parseInt(initialPosition, 10)
          if (!isNaN(page) && page >= 1 && page <= pdf.numPages) {
            setCurrentPage(page)
          }
        }

        setLoading(false)
      } catch (e: any) {
        if (!cancelled) {
          setError(e.message || 'PDF 加载失败，请确保已安装 pdfjs-dist')
          setLoading(false)
        }
      }
    }

    load()
    return () => { cancelled = true }
  }, [bookId, initialPosition])

  /**
   * 计算自适应缩放比例
   * pdfScale=1 时 PDF 宽度恰好等于容器宽度，填满屏幕
   * pdfScale>1 时 PDF 宽度按倍数放大，允许水平滚动
   */
  const getEffectiveScale = useCallback((page: any): number => {
    const baseViewport = page.getViewport({ scale: 1 })
    const containerWidth = containerRef.current?.clientWidth || window.innerWidth
    const fitScale = containerWidth / baseViewport.width
    return fitScale * settings.pdfScale
  }, [settings.pdfScale])

  // 渲染指定页到 Canvas，返回 CSS 像素尺寸
  const renderPage = useCallback(async (pageNum: number, canvas: HTMLCanvasElement): Promise<{ width: number; height: number } | null> => {
    if (!pdfDocRef.current) return null
    setRendering(true)
    try {
      const page = await pdfDocRef.current.getPage(pageNum)
      const effectiveScale = getEffectiveScale(page)
      const viewport = page.getViewport({ scale: effectiveScale })

      canvas.width = viewport.width
      canvas.height = viewport.height
      // 不设置 canvas.style，让 canvas 按 1:1 像素显示

      const ctx = canvas.getContext('2d')
      if (!ctx) return null
      await page.render({ canvasContext: ctx, viewport }).promise

      return {
        width: Math.round(viewport.width),
        height: Math.round(viewport.height),
      }
    } catch {
      return null
    } finally {
      setRendering(false)
    }
  }, [getEffectiveScale])

  // 监听滚动确定当前页
  const handleScroll = useCallback(() => {
    if (!containerRef.current) return
    const container = containerRef.current
    const pageElements = container.querySelectorAll('[data-page]')
    if (pageElements.length === 0) return

    const containerTop = container.scrollTop
    const containerHeight = container.clientHeight
    const midPoint = containerTop + containerHeight / 2

    let currentPageNum = 1
    pageElements.forEach((el) => {
      const rect = el.getBoundingClientRect()
      const elMid = rect.top + rect.height / 2
      if (elMid <= midPoint + container.getBoundingClientRect().top) {
        currentPageNum = parseInt((el as HTMLElement).dataset.page || '1', 10)
      }
    })

    setCurrentPage(currentPageNum)

    // 计算进度
    const pdfProgress = totalPages > 0 ? currentPageNum / totalPages : 0
    const now = Date.now()
    if (now - lastReportRef.current > 3000) {
      lastReportRef.current = now
      reportProgress(bookId, pdfProgress, String(currentPageNum))
    }
  }, [totalPages, bookId, reportProgress])

  // 翻页
  const goPage = useCallback((direction: 'next' | 'prev') => {
    if (!containerRef.current) return
    const container = containerRef.current
    const pageHeight = container.clientHeight
    container.scrollBy({
      top: direction === 'next' ? pageHeight : -pageHeight,
      behavior: settings.pageAnimation === 'none' ? 'auto' : 'smooth',
    })
  }, [settings.pageAnimation])

  // 跳转页码
  const goToPage = useCallback((page: number) => {
    if (page < 1 || page > totalPages) return
    const target = containerRef.current?.querySelector(`[data-page="${page}"]`)
    target?.scrollIntoView({ behavior: 'smooth' })
    setCurrentPage(page)
  }, [totalPages])

  // 切后台时上报
  useEffect(() => {
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'hidden') {
        const pdfProgress = totalPages > 0 ? currentPage / totalPages : 0
        reportProgress(bookId, pdfProgress, String(currentPage))
      }
    }
    document.addEventListener('visibilitychange', handleVisibilityChange)
    return () => document.removeEventListener('visibilitychange', handleVisibilityChange)
  }, [bookId, currentPage, totalPages, reportProgress])

  const progress = totalPages > 0 ? currentPage / totalPages : 0

  return {
    book, currentPage, totalPages, progress, loading, error, rendering,
    containerRef, renderPage, handleScroll, goPage, goToPage,
  }
}
