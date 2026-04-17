import { useState, useRef, useCallback, useEffect } from 'react'
import {
  Play, Pause, Square, Volume2,
  ChevronLeft, ChevronRight, Loader2,
} from 'lucide-react'
import { useTtsStore } from '@/store/tts'
import { ttsService } from '@/utils/tts'

const STORAGE_KEY = 'tts-float-pos'
const SNAP_DURATION = 300
const AUTO_COLLAPSE_MS = 3000

const COLLAPSED_SIZE = 44   // 小圆直径
const EXPANDED_W = 224
const EXPANDED_H = 140

/**
 * 全局 TTS 浮动播放器
 * 
 * 展开状态：可拖动的迷你播放条，3秒无操作自动收起
 * 收起状态：半隐藏贴边小圆图标，点击展开
 * 拖动释放后自动吸附到最近的左/右边缘
 * 位置持久化到 localStorage
 */
export default function TtsFloatPlayer() {
  const {
    status, bookId, bookTitle, segmentIndex, totalSegments,
    segmentsLoading,
  } = useTtsStore()

  if (status === 'idle' || !bookId) return null

  return <TtsFloatPlayerInner
    status={status}
    bookId={bookId}
    bookTitle={bookTitle}
    segmentIndex={segmentIndex}
    totalSegments={totalSegments}
    segmentsLoading={segmentsLoading}
  />
}

interface InnerProps {
  status: string
  bookId: number | null
  bookTitle: string
  segmentIndex: number
  totalSegments: number
  segmentsLoading: boolean
}

function TtsFloatPlayerInner({
  status, bookId, bookTitle, segmentIndex, totalSegments,
  segmentsLoading,
}: InnerProps) {
  const [expanded, setExpanded] = useState(true)
  const [pos, setPos] = useState<{ x: number; y: number }>(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY)
      if (saved) {
        const p = JSON.parse(saved)
        if (typeof p.x === 'number' && typeof p.y === 'number') return p
      }
    } catch {}
    return { x: window.innerWidth - EXPANDED_W - 8, y: Math.round(window.innerHeight * 0.33) }
  })

  const [snapping, setSnapping] = useState(false)
  const posRef = useRef(pos)
  posRef.current = pos

  const draggingRef = useRef(false)
  const dragRef = useRef<{
    startX: number
    startY: number
    startPosX: number
    startPosY: number
    moved: boolean
  } | null>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const autoCollapseTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  // 当前贴边方向
  const [snapSide, setSnapSide] = useState<'left' | 'right'>(() => {
    return pos.x + EXPANDED_W / 2 < window.innerWidth / 2 ? 'left' : 'right'
  })

  // 重置自动收起计时器
  const resetAutoCollapse = useCallback(() => {
    if (autoCollapseTimer.current) clearTimeout(autoCollapseTimer.current)
    if (expanded) {
      autoCollapseTimer.current = setTimeout(() => {
        setExpanded(false)
      }, AUTO_COLLAPSE_MS)
    }
  }, [expanded])

  // 展开时启动计时器，收起时清除
  useEffect(() => {
    if (expanded) {
      resetAutoCollapse()
    } else {
      if (autoCollapseTimer.current) clearTimeout(autoCollapseTimer.current)
    }
    return () => {
      if (autoCollapseTimer.current) clearTimeout(autoCollapseTimer.current)
    }
  }, [expanded, resetAutoCollapse])

  // 收起时贴边 + 半隐藏
  useEffect(() => {
    if (!expanded) {
      // 用 snapSide 而非位置判断方向（位置可能不可靠）
      const side = snapSide
      const hiddenX = side === 'left'
        ? -COLLAPSED_SIZE / 2   // 左边：露出右半
        : window.innerWidth - COLLAPSED_SIZE / 2  // 右边：露出左半
      const clampedY = Math.min(Math.max(posRef.current.y, 50), window.innerHeight - COLLAPSED_SIZE - 50)
      setSnapping(true)
      setPos({ x: hiddenX, y: clampedY })
      try { localStorage.setItem(STORAGE_KEY, JSON.stringify({ x: hiddenX, y: clampedY })) } catch {}
      setTimeout(() => setSnapping(false), SNAP_DURATION)
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expanded])

  // 点击半隐藏图标展开
  const handleExpandFromCollapsed = useCallback(() => {
    const side = snapSide
    const expandedX = side === 'left'
      ? 8
      : window.innerWidth - EXPANDED_W - 8
    const clampedY = Math.min(Math.max(posRef.current.y, 50), window.innerHeight - EXPANDED_H - 50)
    setSnapSide(side)
    setSnapping(true)
    setPos({ x: expandedX, y: clampedY })
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify({ x: expandedX, y: clampedY })) } catch {}
    setTimeout(() => setSnapping(false), SNAP_DURATION)
    setExpanded(true)
  }, [])

  // ---- 拖动（捕获阶段，展开状态下工作） ----
  useEffect(() => {
    if (!expanded) return
    const el = containerRef.current
    if (!el) return

    const onPointerDown = (e: PointerEvent) => {
      if ((e.target as HTMLElement).closest('[data-no-drag]')) return
      e.preventDefault()
      el.setPointerCapture(e.pointerId)
      const cur = posRef.current
      dragRef.current = {
        startX: e.clientX,
        startY: e.clientY,
        startPosX: cur.x,
        startPosY: cur.y,
        moved: false,
      }
      draggingRef.current = true
      // 拖动时暂停自动收起
      if (autoCollapseTimer.current) clearTimeout(autoCollapseTimer.current)
    }

    const onPointerMove = (e: PointerEvent) => {
      if (!dragRef.current) return
      const dx = e.clientX - dragRef.current.startX
      const dy = e.clientY - dragRef.current.startY
      if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
        dragRef.current.moved = true
      }
      const newX = dragRef.current.startPosX + dx
      const newY = dragRef.current.startPosY + dy
      const maxX = window.innerWidth - EXPANDED_W
      const maxY = window.innerHeight - EXPANDED_H
      setPos({
        x: Math.min(Math.max(newX, 0), maxX),
        y: Math.min(Math.max(newY, 50), maxY),
      })
    }

    const onPointerUp = () => {
      if (!dragRef.current) return
      const { moved } = dragRef.current
      draggingRef.current = false
      if (moved) {
        // 贴边
        const cur = posRef.current
        const side = cur.x + EXPANDED_W / 2 < window.innerWidth / 2 ? 'left' : 'right'
        const snappedX = side === 'left' ? 8 : window.innerWidth - EXPANDED_W - 8
        const snappedY = Math.min(Math.max(cur.y, 50), window.innerHeight - EXPANDED_H - 50)
        setSnapping(true)
        setPos({ x: snappedX, y: snappedY })
        try { localStorage.setItem(STORAGE_KEY, JSON.stringify({ x: snappedX, y: snappedY })) } catch {}
        setTimeout(() => setSnapping(false), SNAP_DURATION)
        setSnapSide(side)
      }
      dragRef.current = null
      // 释放后重启自动收起计时
      resetAutoCollapse()
    }

    el.addEventListener('pointerdown', onPointerDown, true)
    el.addEventListener('pointermove', onPointerMove, true)
    el.addEventListener('pointerup', onPointerUp, true)
    el.addEventListener('pointercancel', onPointerUp, true)

    return () => {
      el.removeEventListener('pointerdown', onPointerDown, true)
      el.removeEventListener('pointermove', onPointerMove, true)
      el.removeEventListener('pointerup', onPointerUp, true)
      el.removeEventListener('pointercancel', onPointerUp, true)
    }
  }, [expanded, resetAutoCollapse])

  // 窗口 resize
  useEffect(() => {
    const onResize = () => {
      if (expanded) {
        const cur = posRef.current
        const side = snapSide
        const snappedX = side === 'left' ? 8 : window.innerWidth - EXPANDED_W - 8
        const snappedY = Math.min(Math.max(cur.y, 50), window.innerHeight - EXPANDED_H - 50)
        setSnapping(true)
        setPos({ x: snappedX, y: snappedY })
        setTimeout(() => setSnapping(false), SNAP_DURATION)
      } else {
        handleExpandFromCollapsed()
        setExpanded(false)
      }
    }
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expanded, snapSide])

  // 用户操作时重置自动收起计时
  const touchAutoCollapse = useCallback(() => {
    resetAutoCollapse()
  }, [resetAutoCollapse])

  const progress = totalSegments > 0 ? (segmentIndex + 1) / totalSegments : 0

  const handlePlayPause = () => {
    if (segmentsLoading) return
    if (status === 'playing') {
      ttsService.pause()
      useTtsStore.getState().pause()
    } else if (status === 'paused') {
      useTtsStore.getState().resume()
      ttsService.speakSegment(segmentIndex)
    }
    touchAutoCollapse()
  }

  const handleStop = () => {
    ttsService.cancel()
    useTtsStore.getState().stopReading()
  }

  const handlePrevSegment = () => {
    if (segmentsLoading || segmentIndex <= 0) return
    const newIndex = segmentIndex - 1
    useTtsStore.getState().setSegmentIndex(newIndex)
    if (status === 'playing') ttsService.speakSegment(newIndex)
    touchAutoCollapse()
  }

  const handleNextSegment = () => {
    if (segmentsLoading || segmentIndex >= totalSegments - 1) return
    const newIndex = segmentIndex + 1
    useTtsStore.getState().setSegmentIndex(newIndex)
    if (status === 'playing') ttsService.speakSegment(newIndex)
    touchAutoCollapse()
  }

  const handleGoToBook = () => {
    if (bookId) {
      const readerPath = `/reader/${bookId}`
      if (window.location.pathname !== readerPath) {
        window.location.href = readerPath
      }
    }
    touchAutoCollapse()
  }

  // ========== 收起状态：半隐藏贴边小圆图标 ==========
  if (!expanded) {
    return (
      <button
        onClick={handleExpandFromCollapsed}
        className="fixed z-50 flex items-center justify-center rounded-full bg-primary text-primary-foreground shadow-lg transition-transform active:scale-95 hover:scale-110"
        style={{
          left: pos.x,
          top: pos.y,
          width: COLLAPSED_SIZE,
          height: COLLAPSED_SIZE,
          transition: snapping
            ? `left ${SNAP_DURATION}ms cubic-bezier(0.25,1,0.5,1), top ${SNAP_DURATION}ms cubic-bezier(0.25,1,0.5,1), transform 0.15s`
            : 'transform 0.15s',
          touchAction: 'none',
        }}
        title="展开听书控件"
      >
        <Volume2 className="h-5 w-5" />
        {/* 播放中呼吸动画 */}
        {status === 'playing' && !segmentsLoading && (
          <span className="absolute top-0 right-0 flex h-3 w-3">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-green-400 opacity-75" />
            <span className="relative inline-flex h-3 w-3 rounded-full bg-green-500" />
          </span>
        )}
        {segmentsLoading && (
          <span className="absolute top-0 right-0 h-3 w-3">
            <Loader2 className="h-3 w-3 animate-spin text-yellow-400" />
          </span>
        )}
        {status === 'paused' && (
          <span className="absolute top-0 right-0 h-3 w-3 rounded-full bg-yellow-400" />
        )}
      </button>
    )
  }

  // ========== 展开状态：可拖动的迷你播放条 ==========
  return (
    <div
      ref={containerRef}
      className={`fixed z-50 ${draggingRef.current ? 'cursor-grabbing' : 'cursor-grab'}`}
      style={{
        left: pos.x,
        top: pos.y,
        width: EXPANDED_W,
        transition: snapping
          ? `left ${SNAP_DURATION}ms cubic-bezier(0.25,1,0.5,1), top ${SNAP_DURATION}ms cubic-bezier(0.25,1,0.5,1), width 0.2s`
          : 'width 0.2s',
        touchAction: 'none',
        userSelect: 'none',
      }}
    >
      <div className="flex w-full flex-col rounded-xl bg-card shadow-xl border border-border">
        {/* 头部：书名 */}
        <div className="flex items-center gap-1.5 px-3 pt-2.5 pb-1">
          <Volume2 className="h-4 w-4 shrink-0 text-primary" />
          <button
            onClick={handleGoToBook}
            data-no-drag
            className="min-w-0 flex-1 truncate text-xs font-medium text-foreground hover:text-primary text-left"
          >
            {bookTitle || '朗读中'}
          </button>
        </div>

        {/* 进度条 */}
        <div className="px-3 pb-1.5">
          <div className="h-1 w-full rounded-full bg-muted overflow-hidden">
            <div
              className="h-full rounded-full bg-primary transition-all duration-300"
              style={{ width: `${Math.round(progress * 100)}%` }}
            />
          </div>
          <div className="mt-0.5 text-[10px] text-muted-foreground text-right">
            {segmentsLoading ? '加载中...' : `${segmentIndex + 1} / ${totalSegments}`}
          </div>
        </div>

        {/* 控制按钮 */}
        <div className="flex items-center justify-center gap-2 px-3 pb-2.5">
          <button
            onClick={handlePrevSegment}
            data-no-drag
            disabled={segmentsLoading || segmentIndex <= 0}
            className="flex h-7 w-7 items-center justify-center rounded-full hover:bg-muted disabled:opacity-30"
          >
            <ChevronLeft className="h-4 w-4" />
          </button>

          <button
            onClick={handlePlayPause}
            data-no-drag
            disabled={segmentsLoading}
            className="flex h-9 w-9 items-center justify-center rounded-full bg-primary text-primary-foreground hover:bg-primary/90 active:scale-95 transition-transform disabled:opacity-50"
          >
            {segmentsLoading ? (
              <Loader2 className="h-5 w-5 animate-spin" />
            ) : status === 'playing' ? (
              <Pause className="h-5 w-5" />
            ) : (
              <Play className="h-5 w-5 ml-0.5" />
            )}
          </button>

          <button
            onClick={handleStop}
            data-no-drag
            className="flex h-7 w-7 items-center justify-center rounded-full hover:bg-muted"
          >
            <Square className="h-3.5 w-3.5" />
          </button>

          <button
            onClick={handleNextSegment}
            data-no-drag
            disabled={segmentsLoading || segmentIndex >= totalSegments - 1}
            className="flex h-7 w-7 items-center justify-center rounded-full hover:bg-muted disabled:opacity-30"
          >
            <ChevronRight className="h-4 w-4" />
          </button>
        </div>
      </div>
    </div>
  )
}
