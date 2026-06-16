import { useState, useRef, useEffect, useCallback } from 'react'

type SnapEdge = 'left' | 'right' | 'top' | 'bottom'

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
  /** 允许贴边的方向，默认 ['left', 'right'] */
  snapEdges?: SnapEdge[]
  /** 贴边后是否半隐藏 + 半透明，默认 false */
  autoHide?: boolean
  /** localStorage 存储键名，默认 'draggable-fab-pos' */
  storageKey?: string
}

/**
 * 可拖动 + 自动贴边的浮动按钮
 * - 拖动释放后自动吸附到最近的边缘
 * - 短按（无拖动位移）触发 onClick
 * - 位置持久化到 localStorage
 * - 支持多边缘吸附 + 自动半隐藏
 */
export default function DraggableFab({
  onClick,
  children,
  className = '',
  size = 56,
  edgePadding = 16,
  snapDuration = 300,
  title,
  snapEdges = ['left', 'right'],
  autoHide = false,
  storageKey = 'draggable-fab-pos',
}: DraggableFabProps) {
  const [pos, setPos] = useState<{ x: number; y: number }>(() => {
    try {
      const saved = localStorage.getItem(storageKey)
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
  const [snappedEdge, setSnappedEdge] = useState<SnapEdge | null>(null)
  const dragRef = useRef<{
    startX: number
    startY: number
    startPosX: number
    startPosY: number
    moved: boolean
  } | null>(null)

  // 贴边：吸附到 snapEdges 中欧氏距离最近的边缘
  const snapToEdge = useCallback((x: number, y: number): { x: number; y: number; edge: SnapEdge } => {
    const windowWidth = window.innerWidth
    const windowHeight = window.innerHeight
    const minY = 60
    const maxY = windowHeight - size - edgePadding
    const minX = 0
    const maxX = windowWidth - size

    let bestDist = Infinity
    let bestX = x
    let bestY = y
    let bestEdge: SnapEdge = snapEdges[0]

    for (const edge of snapEdges) {
      let targetX: number
      let targetY: number

      switch (edge) {
        case 'right':
          targetX = windowWidth - (autoHide ? size / 2 : size) - (autoHide ? 4 : edgePadding)
          targetY = Math.min(Math.max(y, minY), maxY)
          break
        case 'left':
          targetX = autoHide ? -(size / 2) + 4 : edgePadding
          targetY = Math.min(Math.max(y, minY), maxY)
          break
        case 'bottom':
          targetY = windowHeight - (autoHide ? size / 2 : size) - (autoHide ? 4 : edgePadding)
          targetX = Math.min(Math.max(x, minX), maxX)
          break
        case 'top':
          targetY = autoHide ? -(size / 2) + 4 : edgePadding
          targetX = Math.min(Math.max(x, minX), maxX)
          break
      }

      const dx = x - targetX
      const dy = y - targetY
      const dist = dx * dx + dy * dy

      if (dist < bestDist) {
        bestDist = dist
        bestX = targetX
        bestY = targetY
        bestEdge = edge
      }
    }

    return { x: bestX, y: bestY, edge: bestEdge }
  }, [size, edgePadding, snapEdges, autoHide])

  // 释放时贴边
  const handleSnap = useCallback((x: number, y: number) => {
    const target = snapToEdge(x, y)
    setSnapping(true)
    setSnappedEdge(target.edge)
    setPos({ x: target.x, y: target.y })
    try { localStorage.setItem(storageKey, JSON.stringify({ x: target.x, y: target.y })) } catch {}
    setTimeout(() => setSnapping(false), snapDuration)
  }, [snapToEdge, snapDuration, storageKey])

  // ---- Pointer 事件（兼容 PC 和移动端） ----
  const handlePointerDown = useCallback((e: React.PointerEvent) => {
    e.preventDefault()

    let newX = pos.x
    let newY = pos.y

    // 如果开启了 autoHide 且当前已贴边，先完全滑入可见区域再开始拖拽
    if (autoHide && snappedEdge) {
      const windowWidth = window.innerWidth
      const windowHeight = window.innerHeight

      switch (snappedEdge) {
        case 'right':
          newX = windowWidth - size - edgePadding
          break
        case 'left':
          newX = edgePadding
          break
        case 'bottom':
          newY = windowHeight - size - edgePadding
          break
        case 'top':
          newY = edgePadding
          break
      }

      setPos({ x: newX, y: newY })
      setSnappedEdge(null)
    }

    ;(e.target as HTMLElement).setPointerCapture(e.pointerId)
    dragRef.current = {
      startX: e.clientX,
      startY: e.clientY,
      startPosX: newX,
      startPosY: newY,
      moved: false,
    }
    setDragging(true)
  }, [pos, size, edgePadding, autoHide, snappedEdge])

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
      if (autoHide) {
        // autoHide 模式：点击后重新贴边隐藏（pointerDown 已把按钮滑入可见区域）
        handleSnap(pos.x, pos.y)
      } else {
        // 非 autoHide：恢复原位
        setPos({ x: startPosX, y: startPosY })
      }
    } else {
      // 拖动结束 → 贴边
      handleSnap(pos.x, pos.y)
    }
    dragRef.current = null
  }, [onClick, handleSnap, pos, autoHide])

  // 窗口大小变化时重新贴边
  useEffect(() => {
    const handleResize = () => {
      handleSnap(pos.x, pos.y)
    }
    window.addEventListener('resize', handleResize)
    return () => window.removeEventListener('resize', handleResize)
  }, [pos.x, pos.y, handleSnap])

  // autoHide 模式下的不透明度
  const fabOpacity = (autoHide && snappedEdge && !dragging) ? 0.5 : 1

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
        opacity: fabOpacity,
        transition: snapping
          ? `left ${snapDuration}ms cubic-bezier(0.25,1,0.5,1), top ${snapDuration}ms cubic-bezier(0.25,1,0.5,1), transform 0.15s, opacity 0.3s ease`
          : 'transform 0.15s, opacity 0.3s ease',

      }}
      title={title}
    >
      {children}
    </button>
  )
}
