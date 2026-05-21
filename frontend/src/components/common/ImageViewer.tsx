import { useState, useCallback, useEffect } from 'react'
import { X, ZoomIn, ZoomOut, ImagePlus } from 'lucide-react'

interface ImageViewerProps {
  src: string | null
  alt?: string
  isOpen: boolean
  onClose: () => void
  /** 是否显示管理员操作按钮 */
  showAdminActions?: boolean
  /** 管理员操作按钮文本 */
  adminActionLabel?: string
  /** 管理员操作回调 */
  onAdminAction?: () => void
  /** 是否显示更换封面按钮（详情页） */
  showChangeCover?: boolean
  onChangeCover?: () => void
}

export default function ImageViewer({
  src,
  alt = '图片',
  isOpen,
  onClose,
  showAdminActions = false,
  adminActionLabel = '设为封面',
  onAdminAction,
  showChangeCover = false,
  onChangeCover,
}: ImageViewerProps) {
  const [scale, setScale] = useState(1)
  const [isDragging, setIsDragging] = useState(false)
  const [position, setPosition] = useState({ x: 0, y: 0 })
  const [dragStart, setDragStart] = useState({ x: 0, y: 0 })

  const handleZoomIn = useCallback(() => {
    setScale(prev => Math.min(prev + 0.25, 4))
  }, [])

  const handleZoomOut = useCallback(() => {
    setScale(prev => {
      const next = Math.max(prev - 0.25, 0.5)
      if (next === 1) setPosition({ x: 0, y: 0 })
      return next
    })
  }, [])

  const handleWheel = useCallback((e: React.WheelEvent) => {
    e.preventDefault()
    if (e.deltaY < 0) {
      setScale(prev => Math.min(prev + 0.1, 4))
    } else {
      setScale(prev => {
        const next = Math.max(prev - 0.1, 0.5)
        if (next <= 1) setPosition({ x: 0, y: 0 })
        return next
      })
    }
  }, [])

  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    if (scale > 1) {
      setIsDragging(true)
      setDragStart({ x: e.clientX - position.x, y: e.clientY - position.y })
    }
  }, [scale, position])

  const handleMouseMove = useCallback((e: React.MouseEvent) => {
    if (isDragging && scale > 1) {
      setPosition({
        x: e.clientX - dragStart.x,
        y: e.clientY - dragStart.y,
      })
    }
  }, [isDragging, dragStart, scale])

  const handleMouseUp = useCallback(() => {
    setIsDragging(false)
  }, [])

  const handleTouchStart = useCallback((e: React.TouchEvent) => {
    if (scale > 1 && e.touches.length === 1) {
      setIsDragging(true)
      setDragStart({
        x: e.touches[0].clientX - position.x,
        y: e.touches[0].clientY - position.y,
      })
    }
  }, [scale, position])

  const handleTouchMove = useCallback((e: React.TouchEvent) => {
    if (isDragging && scale > 1 && e.touches.length === 1) {
      setPosition({
        x: e.touches[0].clientX - dragStart.x,
        y: e.touches[0].clientY - dragStart.y,
      })
    }
  }, [isDragging, dragStart, scale])

  const handleTouchEnd = useCallback(() => {
    setIsDragging(false)
  }, [])

  // 重置状态当打开/关闭时
  useEffect(() => {
    if (isOpen) {
      setScale(1)
      setPosition({ x: 0, y: 0 })
    }
  }, [isOpen])

  // ESC 关闭
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    if (isOpen) {
      document.addEventListener('keydown', handleKeyDown)
      document.body.style.overflow = 'hidden'
    }
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      document.body.style.overflow = ''
    }
  }, [isOpen, onClose])

  if (!isOpen) return null
  if (!src && !showChangeCover) return null

  return (
    <div
      className="fixed inset-0 z-[100] flex flex-col items-center justify-center bg-black/90 backdrop-blur-sm"
      onClick={onClose}
    >
      {/* 顶部工具栏 */}
      <div className="absolute top-0 left-0 right-0 z-10 flex items-center justify-between px-4 py-3">
        <div className="flex items-center gap-2">
          <button
            onClick={(e) => { e.stopPropagation(); handleZoomOut() }}
            className="flex h-10 w-10 items-center justify-center rounded-full bg-white/10 text-white hover:bg-white/20 transition-colors"
            title="缩小"
          >
            <ZoomOut className="h-5 w-5" />
          </button>
          <span className="text-sm text-white/80 min-w-[60px] text-center">
            {Math.round(scale * 100)}%
          </span>
          <button
            onClick={(e) => { e.stopPropagation(); handleZoomIn() }}
            className="flex h-10 w-10 items-center justify-center rounded-full bg-white/10 text-white hover:bg-white/20 transition-colors"
            title="放大"
          >
            <ZoomIn className="h-5 w-5" />
          </button>
        </div>

        <div className="flex items-center gap-2">
          {/* 更换封面按钮（详情页） */}
          {showChangeCover && onChangeCover && (
            <button
              onClick={(e) => { e.stopPropagation(); onChangeCover() }}
              className="flex items-center gap-1.5 rounded-full bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
            >
              <ImagePlus className="h-4 w-4" />
              更换封面
            </button>
          )}
          {/* 设为封面按钮（阅读页） */}
          {showAdminActions && onAdminAction && (
            <button
              onClick={(e) => { e.stopPropagation(); onAdminAction() }}
              className="flex items-center gap-1.5 rounded-full bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
            >
              <ImagePlus className="h-4 w-4" />
              {adminActionLabel}
            </button>
          )}
          <button
            onClick={(e) => { e.stopPropagation(); onClose() }}
            className="flex h-10 w-10 items-center justify-center rounded-full bg-white/10 text-white hover:bg-white/20 transition-colors"
            title="关闭"
          >
            <X className="h-5 w-5" />
          </button>
        </div>
      </div>

      {/* 图片区域 */}
      <div
        className="flex-1 flex items-center justify-center w-full overflow-hidden"
        onWheel={handleWheel}
        onMouseDown={handleMouseDown}
        onMouseMove={handleMouseMove}
        onMouseUp={handleMouseUp}
        onMouseLeave={handleMouseUp}
        onTouchStart={handleTouchStart}
        onTouchMove={handleTouchMove}
        onTouchEnd={handleTouchEnd}
        style={{ cursor: scale > 1 ? (isDragging ? 'grabbing' : 'grab') : 'zoom-in' }}
      >
        {src ? (
          <img
            src={src}
            alt={alt}
            className="max-h-[85vh] max-w-[90vw] object-contain transition-transform duration-100 select-none"
            style={{
              transform: `translate(${position.x}px, ${position.y}px) scale(${scale})`,
              pointerEvents: 'none',
            }}
            draggable={false}
            onClick={(e) => e.stopPropagation()}
          />
        ) : (
          <div className="flex flex-col items-center gap-4 text-white/50">
            <ImagePlus className="h-20 w-20 opacity-40" />
            <span className="text-lg">暂无封面图片</span>
            {showChangeCover && (
              <span className="text-sm opacity-60">点击顶部按钮上传封面</span>
            )}
          </div>
        )}
      </div>

      {/* 底部提示 */}
      <div className="absolute bottom-4 left-1/2 -translate-x-1/2 text-xs text-white/50">
        滚轮缩放 · 拖拽移动 · 点击背景关闭
      </div>
    </div>
  )
}
