import { useState, useCallback, useRef } from 'react'
import { RotateCcw } from 'lucide-react'

interface PinchZoomImageProps {
  src: string
  alt: string
  className?: string
}

export default function PinchZoomImage({ src, alt, className }: PinchZoomImageProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [transform, setTransform] = useState({ scale: 1, x: 0, y: 0 })
  const lastTouchRef = useRef<{ distance: number; x: number; y: number; scale: number; tx: number; ty: number } | null>(null)
  const isDraggingRef = useRef(false)
  const lastSingleTouchRef = useRef<{ x: number; y: number; tx: number; ty: number } | null>(null)

  const resetTransform = useCallback(() => {
    setTransform({ scale: 1, x: 0, y: 0 })
  }, [])

  const clampTranslate = useCallback((x: number, y: number, scale: number) => {
    if (!containerRef.current) return { x, y }
    const container = containerRef.current
    const imgW = container.scrollWidth * scale
    const imgH = container.scrollHeight * scale
    const cW = container.clientWidth
    const cH = container.clientHeight
    const overflowX = Math.max(0, (imgW - cW) / 2)
    const overflowY = Math.max(0, (imgH - cH) / 2)
    return {
      x: Math.max(-overflowX, Math.min(overflowX, x)),
      y: Math.max(-overflowY, Math.min(overflowY, y)),
    }
  }, [])

  const handleTouchStart = useCallback((e: React.TouchEvent) => {
    if (e.touches.length === 2) {
      e.preventDefault()
      const dx = e.touches[0].clientX - e.touches[1].clientX
      const dy = e.touches[0].clientY - e.touches[1].clientY
      lastTouchRef.current = {
        distance: Math.hypot(dx, dy),
        x: (e.touches[0].clientX + e.touches[1].clientX) / 2,
        y: (e.touches[0].clientY + e.touches[1].clientY) / 2,
        scale: transform.scale,
        tx: transform.x,
        ty: transform.y,
      }
      isDraggingRef.current = false
      lastSingleTouchRef.current = null
    } else if (e.touches.length === 1) {
      if (transform.scale > 1) {
        lastSingleTouchRef.current = {
          x: e.touches[0].clientX,
          y: e.touches[0].clientY,
          tx: transform.x,
          ty: transform.y,
        }
        isDraggingRef.current = true
      }
    }
  }, [transform])

  const handleTouchMove = useCallback((e: React.TouchEvent) => {
    if (e.touches.length === 2 && lastTouchRef.current) {
      e.preventDefault()
      const dx = e.touches[0].clientX - e.touches[1].clientX
      const dy = e.touches[0].clientY - e.touches[1].clientY
      const distance = Math.hypot(dx, dy)
      const scaleRatio = distance / lastTouchRef.current.distance
      const newScale = Math.max(1, Math.min(5, lastTouchRef.current.scale * scaleRatio))
      const cx = (e.touches[0].clientX + e.touches[1].clientX) / 2
      const cy = (e.touches[0].clientY + e.touches[1].clientY) / 2
      const panX = cx - lastTouchRef.current.x
      const panY = cy - lastTouchRef.current.y
      const clamped = clampTranslate(
        lastTouchRef.current.tx + panX,
        lastTouchRef.current.ty + panY,
        newScale
      )
      setTransform({ scale: newScale, x: clamped.x, y: clamped.y })
    } else if (e.touches.length === 1 && isDraggingRef.current && lastSingleTouchRef.current) {
      e.preventDefault()
      const dx = e.touches[0].clientX - lastSingleTouchRef.current.x
      const dy = e.touches[0].clientY - lastSingleTouchRef.current.y
      const clamped = clampTranslate(
        lastSingleTouchRef.current.tx + dx,
        lastSingleTouchRef.current.ty + dy,
        transform.scale
      )
      setTransform(prev => ({ ...prev, x: clamped.x, y: clamped.y }))
    }
  }, [clampTranslate, transform.scale])

  const handleTouchEnd = useCallback(() => {
    lastTouchRef.current = null
    isDraggingRef.current = false
    lastSingleTouchRef.current = null
    setTransform(prev => {
      if (prev.scale <= 1) return { scale: 1, x: 0, y: 0 }
      return prev
    })
  }, [])

  const handleWheel = useCallback((e: React.WheelEvent) => {
    e.preventDefault()
    const delta = e.deltaY > 0 ? 0.9 : 1.1
    setTransform(prev => {
      const newScale = Math.max(1, Math.min(5, prev.scale * delta))
      if (newScale <= 1) return { scale: 1, x: 0, y: 0 }
      const clamped = clampTranslate(prev.x, prev.y, newScale)
      return { scale: newScale, x: clamped.x, y: clamped.y }
    })
  }, [clampTranslate])

  return (
    <div
      ref={containerRef}
      className={`flex items-center justify-center min-h-[300px] max-h-[70vh] overflow-hidden relative touch-none select-none ${className}`}
      onTouchStart={handleTouchStart}
      onTouchMove={handleTouchMove}
      onTouchEnd={handleTouchEnd}
      onWheel={handleWheel}
    >
      <img
        src={src}
        alt={alt}
        className="max-w-full max-h-[70vh] object-contain rounded-lg pointer-events-none"
        style={{
          transform: `translate(${transform.x}px, ${transform.y}px) scale(${transform.scale})`,
          transformOrigin: 'center center',
          transition: lastTouchRef.current ? 'none' : 'transform 0.15s ease-out',
        }}
        draggable={false}
      />
      {transform.scale > 1 && (
        <button
          onClick={resetTransform}
          className="absolute bottom-3 right-3 flex h-8 w-8 items-center justify-center rounded-full bg-black/50 text-white backdrop-blur-sm active:bg-black/70 transition-colors"
        >
          <RotateCcw className="h-4 w-4" />
        </button>
      )}
    </div>
  )
}
