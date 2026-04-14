import { useEffect, useRef, useState, useCallback } from 'react'
import { useReaderStore } from '@/store/reader'
import { READER_THEMES } from '@/constants'

interface PdfRendererProps {
  totalPages: number
  currentPage: number
  scale: number
  containerRef: React.RefObject<HTMLDivElement>
  onScroll: () => void
  onRenderPage: (pageNum: number, canvas: HTMLCanvasElement) => Promise<{ width: number; height: number } | null>
  rendering: boolean
}

/**
 * PDF 渲染器 - 虚拟滚动方案
 * 只渲染可视区域 ± 缓冲区的页面，其他位置用占位符
 * 页面尺寸由 pdfjs viewport 动态决定，自动适配容器宽度
 */
export default function PdfRenderer({
  totalPages, currentPage, scale, containerRef, onScroll, onRenderPage, rendering,
}: PdfRendererProps) {
  const { settings, isSystemDark } = useReaderStore()
  const theme = READER_THEMES[isSystemDark ? 'DARK' : settings.themeKey]
  const [visibleRange, setVisibleRange] = useState({ start: 1, end: Math.min(5, totalPages) })
  const pageContainerRefs = useRef<Map<number, HTMLDivElement>>(new Map())
  const renderedScaleRef = useRef<number>(0)
  // 存储每页渲染后的实际 CSS 尺寸
  const pageSizeMap = useRef<Map<number, { width: number; height: number }>>(new Map())
  // 触发重排
  const [layoutVersion, setLayoutVersion] = useState(0)
  // 是否需要恢复到初始页码
  const initialPageRef = useRef(currentPage > 1 ? currentPage : null)

  // 默认占位高度（渲染前估算）
  const DEFAULT_PAGE_HEIGHT = 800

  const getPageHeight = (pageNum: number) => pageSizeMap.current.get(pageNum)?.height || DEFAULT_PAGE_HEIGHT

  // 渲染可见页面
  useEffect(() => {
    const pagesToRender = []
    for (let i = visibleRange.start; i <= visibleRange.end; i++) {
      // scale 变化后需要重新渲染所有页面
      if (!pageSizeMap.current.has(i) || renderedScaleRef.current !== scale) {
        pagesToRender.push(i)
      }
    }
    if (pagesToRender.length === 0) return

    // scale 变更时清空旧数据
    if (renderedScaleRef.current !== scale) {
      pageSizeMap.current.clear()
      renderedScaleRef.current = scale
    }

    let hasNewRender = false
    pagesToRender.forEach(async (pageNum) => {
      const container = pageContainerRefs.current.get(pageNum)
      if (!container) return

      // 移除旧 canvas
      const oldCanvas = container.querySelector('canvas')
      if (oldCanvas) oldCanvas.remove()

      const canvas = document.createElement('canvas')
      container.appendChild(canvas)

      const size = await onRenderPage(pageNum, canvas)
      if (size) {
        pageSizeMap.current.set(pageNum, size)
        hasNewRender = true
        setLayoutVersion((n) => n + 1)
      }
    })
    
    // 首次渲染完成后，恢复到初始页码或滚动到顶部
    if (hasNewRender && containerRef.current) {
      const targetPage = initialPageRef.current
      if (targetPage && targetPage > 1) {
        // 等待 DOM 更新后滚动到目标页
        setTimeout(() => {
          const target = containerRef.current?.querySelector(`[data-page="${targetPage}"]`)
          if (target) {
            target.scrollIntoView({ behavior: 'auto' })
          }
          initialPageRef.current = null
        }, 100)
      } else if (visibleRange.start === 1) {
        setTimeout(() => {
          if (containerRef.current) {
            containerRef.current.scrollTop = 0
          }
        }, 100)
      }
    }
  }, [visibleRange, onRenderPage, scale])

  // 滚动时更新可见范围
  const handleScroll = useCallback(() => {
    if (!containerRef.current) return
    const container = containerRef.current
    const scrollTop = container.scrollTop
    const viewHeight = container.clientHeight

    const avgPageHeight = pageSizeMap.current.size > 0
      ? Array.from(pageSizeMap.current.values()).reduce((s, p) => s + p.height, 0) / pageSizeMap.current.size + 12 // 12 = gap-3
      : DEFAULT_PAGE_HEIGHT

    const bufferPages = 2
    const startPage = Math.max(1, Math.floor(scrollTop / avgPageHeight) - bufferPages + 1)
    const endPage = Math.min(totalPages, Math.ceil((scrollTop + viewHeight) / avgPageHeight) + bufferPages)

    setVisibleRange({ start: startPage, end: endPage })
    onScroll()
  }, [containerRef, totalPages, onScroll])

  // pdfScale <= 1: 禁止水平滚动
  // pdfScale > 1: 允许水平滚动查看放大内容
  const overflowX = scale > 1 ? 'auto' : 'hidden'

  return (
    <div
      ref={containerRef}
      className="h-full"
      onScroll={handleScroll}
      style={{
        overflowY: 'scroll',
        overflowX,
        backgroundColor: theme.bg,
        filter: `brightness(${settings.brightness})`,
      }}
    >
      <div className="flex flex-col gap-2 py-2 px-4">
        {Array.from({ length: totalPages }, (_, i) => i + 1).map((pageNum) => {
          const isVisible = pageNum >= visibleRange.start && pageNum <= visibleRange.end
          const size = pageSizeMap.current.get(pageNum)
          return (
            <div
              key={pageNum}
              ref={(el) => {
                if (el) pageContainerRefs.current.set(pageNum, el)
              }}
              data-page={pageNum}
              className="flex items-center justify-center shadow-sm mx-auto"
              style={{
                width: size ? `${size.width}px` : '100%',
                minHeight: `${getPageHeight(pageNum)}px`,
                backgroundColor: '#fff',
              }}
            >
              {!isVisible && (
                <span className="text-sm text-gray-400">{pageNum} / {totalPages}</span>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
