import {
  Play, Pause, Square, Volume2,
  ChevronLeft, ChevronRight, X, Loader2,
} from 'lucide-react'
import { useTtsStore } from '@/store/tts'
import { ttsService } from '@/utils/tts'

/**
 * 全局 TTS 浮动播放器
 * 
 * 收起时：右侧贴边小圆形按钮，带呼吸动画
 * 展开时：从右侧滑出迷你播放条
 * 
 * 直接操作 ttsService + store，不依赖阅读页组件
 */
export default function TtsFloatPlayer() {
  const {
    status, bookId, bookTitle, segmentIndex, totalSegments,
    segments, playerExpanded, togglePlayerExpanded, segmentsLoading,
  } = useTtsStore()

  // 不在播放/暂停状态时不显示
  if (status === 'idle' || !bookId) return null

  const progress = totalSegments > 0 ? (segmentIndex + 1) / totalSegments : 0

  const handlePlayPause = () => {
    if (segmentsLoading) return // 加载中不可操作
    if (status === 'playing') {
      ttsService.pause()
      useTtsStore.getState().pause()
    } else if (status === 'paused') {
      // 恢复朗读：从 store 缓存的段落继续
      useTtsStore.getState().resume()
      ttsService.speakSegment(segmentIndex)
    }
  }

  const handleStop = () => {
    ttsService.cancel()
    useTtsStore.getState().stopReading()
  }

  const handlePrevSegment = () => {
    if (segmentsLoading || segmentIndex <= 0) return
    const newIndex = segmentIndex - 1
    useTtsStore.getState().setSegmentIndex(newIndex)
    // 如果正在播放，立即朗读新段落
    if (status === 'playing') {
      ttsService.speakSegment(newIndex)
    }
  }

  const handleNextSegment = () => {
    if (segmentsLoading || segmentIndex >= totalSegments - 1) return
    const newIndex = segmentIndex + 1
    useTtsStore.getState().setSegmentIndex(newIndex)
    // 如果正在播放，立即朗读新段落
    if (status === 'playing') {
      ttsService.speakSegment(newIndex)
    }
  }

  const handleGoToBook = () => {
    if (bookId) {
      const readerPath = `/reader/${bookId}`
      if (window.location.pathname !== readerPath) {
        window.location.href = readerPath
      }
    }
  }

  // 收起状态：右侧贴边小圆形按钮
  if (!playerExpanded) {
    return (
      <button
        onClick={togglePlayerExpanded}
        className="fixed right-0 top-1/3 z-50 flex h-11 w-11 -translate-y-1/2 items-center justify-center rounded-l-full bg-primary text-primary-foreground shadow-lg transition-transform active:scale-95"
        style={{ paddingRight: '2px' }}
      >
        <Volume2 className="h-5 w-5" />
        {/* 播放中呼吸动画指示灯 */}
        {status === 'playing' && !segmentsLoading && (
          <span className="absolute -left-1 top-1 flex h-3 w-3">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-green-400 opacity-75" />
            <span className="relative inline-flex h-3 w-3 rounded-full bg-green-500" />
          </span>
        )}
        {/* 加载中指示灯 */}
        {segmentsLoading && (
          <span className="absolute -left-1 top-1 h-3 w-3">
            <Loader2 className="h-3 w-3 animate-spin text-yellow-400" />
          </span>
        )}
        {status === 'paused' && (
          <span className="absolute -left-1 top-1 h-3 w-3 rounded-full bg-yellow-400" />
        )}
      </button>
    )
  }

  // 展开状态：迷你播放条
  return (
    <div className="fixed right-0 top-1/3 z-50 -translate-y-1/2 animate-in slide-in-from-right duration-200">
      <div className="flex w-56 flex-col rounded-l-xl bg-card shadow-xl border border-border">
        {/* 头部：书名 + 关闭 */}
        <div className="flex items-center gap-1.5 px-3 pt-2.5 pb-1">
          <Volume2 className="h-4 w-4 shrink-0 text-primary" />
          <button
            onClick={handleGoToBook}
            className="min-w-0 flex-1 truncate text-xs font-medium text-foreground hover:text-primary text-left"
          >
            {bookTitle || '朗读中'}
          </button>
          <button
            onClick={togglePlayerExpanded}
            className="shrink-0 rounded-full p-0.5 hover:bg-muted"
          >
            <X className="h-3.5 w-3.5 text-muted-foreground" />
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
            disabled={segmentsLoading || segmentIndex <= 0}
            className="flex h-7 w-7 items-center justify-center rounded-full hover:bg-muted disabled:opacity-30"
          >
            <ChevronLeft className="h-4 w-4" />
          </button>

          <button
            onClick={handlePlayPause}
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
            className="flex h-7 w-7 items-center justify-center rounded-full hover:bg-muted"
          >
            <Square className="h-3.5 w-3.5" />
          </button>

          <button
            onClick={handleNextSegment}
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
