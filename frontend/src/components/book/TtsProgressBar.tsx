import { useEffect, useRef, useState } from 'react'
import { Pause, Play, Square } from 'lucide-react'
import { ttsService } from '@/utils/tts'
import type { PlaybackProgress } from '@/utils/ttsStreamPlayer'

interface TtsProgressBarProps {
  /** 是否正在朗读；false 时组件不渲染 */
  active: boolean
  /** 停止朗读 */
  onStop: () => void
}

/** 秒 → m:ss */
function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00'
  const total = Math.floor(seconds)
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

/**
 * 朗读进度条
 * <p>
 * 只订阅播放内核的进度回调，自身 state 变化不会引起父组件重渲染。
 * <p>
 * 播放/暂停状态一律以内核推送为准，本组件不自己维护一份「是否暂停」：
 * 锁屏控制条、耳机按键、系统中断都可能改变播放状态，界面自己记就会与
 * 真实状态脱节，出现「已经停了但按钮还是暂停图标」的错乱。
 * 点击按钮时给一个短暂的本地预期值，用于即时反馈，下一次推送即被真实状态覆盖。
 */
export default function TtsProgressBar({ active, onStop }: TtsProgressBarProps) {
  const [progress, setProgress] = useState<PlaybackProgress | null>(null)
  const [dragging, setDragging] = useState(false)
  /** 点击暂停/继续后的本地预期，仅用于即时反馈；null 表示以内核状态为准 */
  const [pauseIntent, setPauseIntent] = useState<boolean | null>(null)
  const draggingRef = useRef(false)
  /** 拖动中的草稿位置；用 ref 保证提交时读到的是最后一次的值 */
  const draftRef = useRef(0)
  const [draftTime, setDraftTime] = useState(0)

  // 取消订阅、清理可能残留的拖动状态
  useEffect(() => {
    if (!active) {
      setProgress(null)
      setPauseIntent(null)
      setDragging(false)
      draggingRef.current = false
      return
    }
    return ttsService.onProgress((p) => {
      // 每次推送都代表内核的真实状态，本地预期到此作废
      setPauseIntent(null)
      // 拖动过程中不用内核推送的位置覆盖草稿值
      if (!draggingRef.current) setProgress(p)
    })
  }, [active])

  useEffect(() => {
    draggingRef.current = dragging
  }, [dragging])

  // 兜底：指针在滑块外释放、或手势被浏览器接管（pointercancel）时，
  // 滑块上的 pointerup 不一定触发。缺少这层兜底，dragging 会永久停留在 true，
  // 进度条从此不再刷新，用户以为功能坏了。
  useEffect(() => {
    if (!dragging) return
    const commit = () => {
      draggingRef.current = false
      setDragging(false)
      ttsService.seek(draftRef.current)
    }
    window.addEventListener('pointerup', commit)
    window.addEventListener('pointercancel', commit)
    return () => {
      window.removeEventListener('pointerup', commit)
      window.removeEventListener('pointercancel', commit)
    }
  }, [dragging])

  if (!active) return null

  const duration = progress?.duration ?? 0
  const seekable = progress?.seekable ?? false
  const current = dragging ? draftTime : (progress?.currentTime ?? 0)
  // 尚未收到任何推送时按「正在播放」处理：用户刚刚点击了朗读
  const isPaused = pauseIntent ?? (progress ? !progress.playing : false)
  // 总长可能是估算的（VBR MP3 在流式阶段无法精确预知），真实播放位置有
  // 小概率超过它。若让 max 小于 value，滑块会显示「位置超过总长」的错乱，
  // 因此把上限至少抬到当前位置，保证进度条末态不会倒退。
  const sliderMax = Math.max(duration, current, 0.1)

  const handleTogglePause = () => {
    if (isPaused) {
      ttsService.resume()
      setPauseIntent(false)
    } else {
      ttsService.pause()
      setPauseIntent(true)
    }
  }

  const handleSeekCommit = () => {
    if (!draggingRef.current) return
    draggingRef.current = false
    setDragging(false)
    ttsService.seek(draftRef.current)
  }

  return (
    <div className="flex items-center gap-2 border-t border-border/60 bg-muted/30 px-3 py-2">
      <button
        className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground active:scale-95"
        onClick={handleTogglePause}
        title={isPaused ? '继续朗读' : '暂停朗读'}
      >
        {isPaused ? <Play className="h-3.5 w-3.5" /> : <Pause className="h-3.5 w-3.5" />}
      </button>

      <span className="w-9 shrink-0 text-right text-[11px] tabular-nums text-muted-foreground">
        {formatTime(current)}
      </span>

      {/*
        始终提供可拖动滑块：渐进流阶段拖动会触发播放内核重建连接，
        服务端此时已缓存全部分段，重建后即可精确跳转。
        若因不可定位而隐藏滑块，用户就永远没有入口触发这次升级。
      */}
      <input
        type="range"
        min={0}
        max={sliderMax}
        step={0.1}
        value={current}
        className="h-1 flex-1 cursor-pointer accent-primary"
        onChange={(e) => {
          const value = Number(e.target.value)
          draftRef.current = value
          setDraftTime(value)
          draggingRef.current = true
          setDragging(true)
        }}
        onPointerUp={handleSeekCommit}
        onPointerCancel={handleSeekCommit}
        onKeyUp={(e) => {
          // 只处理滑块自己的定位键：Tab / Shift 等按键不应触发跳转
          if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End', 'PageUp', 'PageDown'].includes(e.key)) return
          handleSeekCommit()
        }}
        onBlur={handleSeekCommit}
        title={seekable ? '拖动跳转' : '拖动跳转（会自动加载完整音频）'}
      />

      <span className="w-9 shrink-0 text-[11px] tabular-nums text-muted-foreground">
        {formatTime(duration)}
      </span>

      <button
        className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground active:scale-95"
        onClick={onStop}
        title="停止朗读"
      >
        <Square className="h-3 w-3 fill-current" />
      </button>
    </div>
  )
}
