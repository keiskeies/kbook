import { useCallback } from 'react'
import { useTtsStore } from '@/store/tts'
import { ttsService } from '@/utils/tts'

interface UseTtsReaderOptions {
  /** 书籍 ID */
  bookId: number
  /** 书籍标题 */
  bookTitle: string
  /** 同步获取全量段落（整本书），TXT 用 */
  getAllSegments?: () => string[]
  /** 异步获取全量段落（整本书），EPUB 用 */
  getAllSegmentsAsync?: () => Promise<string[]>
}

/**
 * TTS 朗读控制 Hook
 * 
 * 职责：UI 状态桥接层，将 store 状态映射为组件可用的属性和方法
 * 核心朗读逻辑由 ttsService + store 驱动，不依赖组件闭包
 * 
 * 支持两种段落获取模式：
 * - 同步模式（TXT）：getAllSegments() 直接返回段落数组
 * - 异步模式（EPUB）：getAllSegmentsAsync() 返回 Promise，逐章提取文本
 */
export function useTtsReader({ bookId, bookTitle, getAllSegments, getAllSegmentsAsync }: UseTtsReaderOptions) {
  const { status, segmentIndex, bookId: storeBookId, segmentsLoading } = useTtsStore()
  const isCurrentBook = storeBookId === bookId
  const isReading = isCurrentBook && status === 'playing'
  const isPaused = isCurrentBook && status === 'paused'
  const currentSegmentIndex = isCurrentBook ? segmentIndex : -1

  // 同步开始朗读（TXT 等已有全量文本的情况）
  const startReading = useCallback((startSegment = 0) => {
    if (getAllSegmentsAsync) {
      // EPUB 异步模式
      ttsService.startReadingAsync(bookId, bookTitle, getAllSegmentsAsync, startSegment)
    } else if (getAllSegments) {
      // TXT 同步模式
      const segments = getAllSegments()
      if (segments.length === 0) return
      ttsService.startReading(bookId, bookTitle, segments, startSegment)
    }
  }, [bookId, bookTitle, getAllSegments, getAllSegmentsAsync])

  // 继续朗读
  const resumeReading = useCallback(() => {
    if (!isCurrentBook) return
    const store = useTtsStore.getState()
    store.resume()
    // 从 store 中缓存的段落继续朗读
    ttsService.speakSegment(store.segmentIndex)
  }, [isCurrentBook])

  // 暂停
  const pauseReading = useCallback(() => {
    ttsService.pause()
    useTtsStore.getState().pause()
  }, [])

  // 停止
  const stopReading = useCallback(() => {
    ttsService.cancel()
    useTtsStore.getState().stopReading()
  }, [])

  // 跳转到指定段落
  const jumpToSegment = useCallback((index: number) => {
    if (!isCurrentBook) return
    const store = useTtsStore.getState()
    store.setSegmentIndex(index)
    if (store.status === 'playing') {
      ttsService.speakSegment(index)
    }
  }, [isCurrentBook])

  return {
    isReading,
    isPaused,
    isCurrentBook,
    currentSegmentIndex,
    segmentsLoading: isCurrentBook && segmentsLoading,
    startReading,
    resumeReading,
    pauseReading,
    stopReading,
    jumpToSegment,
  }
}
