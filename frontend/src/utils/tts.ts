import { useTtsStore } from '@/store/tts'
import type { TtsVoice } from '@/store/tts'

/**
 * TTS 服务层
 * 封装 Web Speech API，提供朗读控制
 * 
 * 核心设计：朗读流程由 service + store 驱动，不依赖任何组件闭包
 * - segments 缓存在 store 中，退出阅读页后仍可继续朗读
 * - speakSegment 从 store 读取段落，朗读完一段后自动递进到下一段
 * - 浮动播放器直接操作 service 控制朗读
 * - 支持 EPUB 异步段落提取：先进入 loading 状态，提取完再开始朗读
 */
class TtsService {
  private synth: SpeechSynthesis | null = null

  constructor() {
    if (typeof window !== 'undefined' && window.speechSynthesis) {
      this.synth = window.speechSynthesis
    }
  }

  /** 是否支持 TTS */
  get supported(): boolean {
    return this.synth !== null
  }

  /** 当前是否正在朗读 */
  get speaking(): boolean {
    return this.synth?.speaking ?? false
  }

  /** 当前是否暂停 */
  get paused(): boolean {
    return this.synth?.paused ?? false
  }

  /**
   * 同步开始朗读整本书（TXT 等已有全量文本的情况）
   * 将全量段落缓存到 store，然后从 startSegment 开始朗读
   */
  startReading(bookId: number, bookTitle: string, segments: string[], startSegment = 0): void {
    if (!this.synth) return

    // 过滤空段落
    const filteredSegments = segments.filter(s => s.trim().length > 0)
    if (filteredSegments.length === 0) return

    // 缓存段落到 store
    useTtsStore.getState().startReading(bookId, bookTitle, filteredSegments, startSegment)

    // 开始朗读
    this.speakSegment(startSegment)
  }

  /**
   * 异步开始朗读（EPUB 等需要逐章提取文本的情况）
   * 1. 先在 store 标记 playing + segmentsLoading
   * 2. 调用 segmentsLoader 异步提取全量段落
   * 3. 完成后缓存到 store 并开始朗读
   */
  async startReadingAsync(
    bookId: number,
    bookTitle: string,
    segmentsLoader: () => Promise<string[]>,
    startSegment = 0,
  ): Promise<void> {
    if (!this.synth) return

    // 标记异步加载中
    useTtsStore.getState().startReadingAsync(bookId, bookTitle, startSegment)

    try {
      const segments = await segmentsLoader()

      // 过滤空段落
      const filteredSegments = segments.filter(s => s.trim().length > 0)
      if (filteredSegments.length === 0) {
        useTtsStore.getState().onSegmentsLoadFailed()
        return
      }

      // 段落加载完成，更新 store
      useTtsStore.getState().onSegmentsLoaded(filteredSegments)

      // 检查是否还在播放状态（用户可能已停止）
      const { status, bookId: currentBookId } = useTtsStore.getState()
      if (status === 'playing' && currentBookId === bookId) {
        this.speakSegment(startSegment)
      }
    } catch (e) {
      console.error('TTS 异步段落加载失败:', e)
      useTtsStore.getState().onSegmentsLoadFailed()
    }
  }

  /**
   * 朗读指定段落
   * 从 store 中读取段落文本，朗读完成后自动递进到下一段
   */
  speakSegment(index: number): void {
    if (!this.synth) return

    const store = useTtsStore.getState()
    const { segments, bookId, status } = store

    // 状态检查
    if (status !== 'playing') return

    // 段落还在加载中，等一会儿再试
    if (store.segmentsLoading) {
      setTimeout(() => {
        const currentStore = useTtsStore.getState()
        if (currentStore.status === 'playing' && !currentStore.segmentsLoading) {
          this.speakSegment(currentStore.segmentIndex)
        }
      }, 300)
      return
    }

    // 段落索引无效或已读完
    if (index < 0 || index >= segments.length) {
      this.cancel()
      useTtsStore.getState().stopReading()
      return
    }

    const text = segments[index]

    // 空段跳过
    if (!text || !text.trim()) {
      useTtsStore.getState().setSegmentIndex(index)
      this.speakSegment(index + 1)
      return
    }

    // 更新段索引
    useTtsStore.getState().setSegmentIndex(index)

    // 取消之前的朗读
    this.synth.cancel()

    const utterance = new SpeechSynthesisUtterance(text)
    const { settings, voices } = store

    // 设置语速
    utterance.rate = settings.rate

    // 设置音色
    if (settings.voiceURI) {
      const voice = voices.find((v: TtsVoice) => v.voiceURI === settings.voiceURI)
      if (voice) {
        const sv = this.synth.getVoices().find((v) => v.voiceURI === voice.voiceURI)
        if (sv) utterance.voice = sv
      }
    }

    utterance.onend = () => {
      // 当前段读完，自动读下一段
      const currentStatus = useTtsStore.getState().status
      const currentBookId = useTtsStore.getState().bookId
      if (currentStatus === 'playing' && currentBookId === bookId) {
        setTimeout(() => {
          this.speakSegment(index + 1)
        }, 200)
      }
    }

    utterance.onerror = (e) => {
      // 'canceled' 是正常取消，不是错误
      if (e.error !== 'canceled') {
        console.warn('TTS error:', e.error)
        // 出错也继续下一段
        const currentStatus = useTtsStore.getState().status
        const currentBookId = useTtsStore.getState().bookId
        if (currentStatus === 'playing' && currentBookId === bookId) {
          setTimeout(() => {
            this.speakSegment(index + 1)
          }, 200)
        }
      }
    }

    this.synth.speak(utterance)
  }

  /** 朗读单段文本（用于浮动播放器切换段落） */
  speakSingleText(text: string, onEnd?: () => void): void {
    if (!this.synth) return

    this.synth.cancel()

    const utterance = new SpeechSynthesisUtterance(text)
    const { settings, voices } = useTtsStore.getState()

    utterance.rate = settings.rate

    if (settings.voiceURI) {
      const voice = voices.find((v: TtsVoice) => v.voiceURI === settings.voiceURI)
      if (voice) {
        const sv = this.synth.getVoices().find((v) => v.voiceURI === voice.voiceURI)
        if (sv) utterance.voice = sv
      }
    }

    utterance.onend = () => {
      onEnd?.()
    }

    utterance.onerror = (e) => {
      if (e.error !== 'canceled') {
        console.warn('TTS error:', e.error)
        onEnd?.()
      }
    }

    this.synth.speak(utterance)
  }

  /** 暂停 */
  pause(): void {
    this.synth?.pause()
  }

  /** 恢复 */
  resume(): void {
    this.synth?.resume()
  }

  /** 停止 */
  cancel(): void {
    this.synth?.cancel()
  }
}

/** 全局单例 */
export const ttsService = new TtsService()
