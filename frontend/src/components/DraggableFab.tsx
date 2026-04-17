import { useState, useRef, useEffect, useCallback } from 'react'

interface DraggableFabProps {
  /** 点击回调（短按才触发，拖动不触发） */
  onClick: () => void
  /** 子元素（通常是图标） */
  children: React.ReactNode
  /** 按钮类名 */
  className?: string
  /** 按钮尺寸（px） */
  size?: number
  /** 距离边缘的间距（px） */
  edgePadding?: number
  /** 贴边动画时长 ms */
  snapDuration?: number
  /** 标题 */
  title?: string
}

/**
 * 可拖动 + 自动贴边的浮动按钮
 * - 拖动释放后自动吸附到最近的左/右边缘
 * - 短按（无拖动位移）触发 onClick
 * - 位置持久化到 localStorage
 */
export default function DraggableFab({
  onClick,
  children,
  className = '',
  size = 56,
  edgePadding = 16,
  snapDuration = 300,
  title,
}: DraggableFabProps) {
  const STORAGE_KEY = 'draggable-fab-pos'

  const [pos, setPos] = useState<{ x: number; y: number }>(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY)
      if (saved) {
        const p = JSON.parse(saved)
        if (typeof p.x === 'number' && typeof p.y === 'number') return p
      }
    } catch {}
    // 默认右下角
    return { x: window.innerWidth - size - edgePadding, y: window.innerHeight - size - edgePadding - 80 }
  })

  const [snapping, setSnapping] = useState(false)
  const [dragging, setDragging] = useState(false)
  const dragRef = useRef<{
    startX: number
    startY: number
    startPosX: number
    startPosY: number
    moved: boolean
  } | null>(null)

  // 贴边：吸附到最近的左/右边缘
  const snapToEdge = useCallback((x: number, y: number) => {
    const midX = window.innerWidth / 2
    const snappedX = x + size / 2 < midX ? edgePadding : window.innerWidth - size - edgePadding
    // 限制 Y 在可视范围内
    const minY = 60
    const maxY = window.innerHeight - size - edgePadding
    const snappedY = Math.min(Math.max(y, minY), maxY)
    return { x: snappedX, y: snappedY }
  }, [size, edgePadding])

  // 释放时贴边
  const handleSnap = useCallback((x: number, y: number) => {
    const target = snapToEdge(x, y)
    setSnapping(true)
    setPos(target)
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(target)) } catch {}
    setTimeout(() => setSnapping(false), snapDuration)
  }, [snapToEdge, snapDuration])

  // ---- Pointer 事件（兼容 PC 和移动端） ----
  const handlePointerDown = useCallback((e: React.PointerEvent) => {
    e.preventDefault()
    ;(e.target as HTMLElement).setPointerCapture(e.pointerId)
    dragRef.current = {
      startX: e.clientX,
      startY: e.clientY,
      startPosX: pos.x,
      startPosY: pos.y,
      moved: false,
    }
    setDragging(true)
  }, [pos])

  const handlePointerMove = useCallback((e: React.PointerEvent) => {
    if (!dragRef.current) return
    const dx = e.clientX - dragRef.current.startX
    const dy = e.clientY - dragRef.current.startY
    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
      dragRef.current.moved = true
    }
    const newX = dragRef.current.startPosX + dx
    const newY = dragRef.current.startPosY + dy
    // 实时跟手（限制在可视范围内）
    const minX = 0
    const maxX = window.innerWidth - size
    const minY = 0
    const maxY = window.innerHeight - size
    setPos({
      x: Math.min(Math.max(newX, minX), maxX),
      y: Math.min(Math.max(newY, minY), maxY),
    })
  }, [size])

  const handlePointerUp = useCallback(() => {
    if (!dragRef.current) return
    const { moved, startPosX, startPosY } = dragRef.current
    setDragging(false)
    if (!moved) {
      // 短按 → 触发点击
      onClick()
      // 位置恢复（拖动中可能因微小位移改变了 pos）
      setPos({ x: startPosX, y: startPosY })
    } else {
      // 拖动结束 → 贴边
      handleSnap(pos.x, pos.y)
    }
    dragRef.current = null
  }, [onClick, handleSnap, pos])

  // 窗口大小变化时重新贴边
  useEffect(() => {
    const handleResize = () => {
      handleSnap(pos.x, pos.y)
    }
    window.addEventListener('resize', handleResize)
    return () => window.removeEventListener('resize', handleResize)
  }, [pos.x, pos.y, handleSnap])

  return (
    <button
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerCancel={handlePointerUp}
      className={`flex items-center justify-center rounded-full select-none touch-none ${
        dragging ? 'cursor-grabbing scale-110' : 'cursor-grab hover:scale-110 active:scale-95'
      } ${className}`}
      style={{
        position: 'fixed',
        left: pos.x,
        top: pos.y,
        width: size,
        height: size,
        zIndex: 40,
        transition: snapping
          ? `left ${snapDuration}ms cubic-bezier(0.25,1,0.5,1), top ${snapDuration}ms cubic-bezier(0.25,1,0.5,1), transform 0.15s`
          : 'transform 0.15s',
        boxShadow: dragging
          ? '0 8px 30px rgba(147,51,234,0.4)'
          : undefined,
      }}
      title={title}
    >
      {children}
    </button>
  )
}
