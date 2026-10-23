import { useTtsStore } from '@/store/tts'
import type { TtsVoice } from '@/store/tts'
import { isStreamingSupported } from '@/api/adminTts'
import { ProgressiveStreamPlayer } from '@/utils/ttsStreamPlayer'
import type { PlaybackMetadata, PlaybackProgress } from '@/utils/ttsStreamPlayer'

const CHUNK_FIRST_MAX_LEN = 50
const CHUNK_MAX_LEN = 100
const CHUNK_MIN_LEN = 50

function getPauseMs(text: string): number {
  if (!text) return 100
  const last = text[text.length - 1]
  if (/[。！？!?；;]/.test(last)) return 500
  if (last === '\n') return 400
  if (/[，,、：:]/.test(last)) return 300
  return 100
}

function smartChunkText(text: string): string[] {
  if (text.length <= CHUNK_MAX_LEN) return [text]

  const paragraphs = text.split(/\n\n+/).filter(p => p.trim())
  const rawChunks: string[] = []

  for (const para of paragraphs) {
    const trimmed = para.trim()
    if (!trimmed) continue
    if (trimmed.length <= CHUNK_MAX_LEN) {
      rawChunks.push(trimmed)
      continue
    }
    rawChunks.push(...splitLongText(trimmed, CHUNK_MAX_LEN))
  }

  const merged: string[] = []
  let buffer = ''
  for (const chunk of rawChunks) {
    if (buffer && buffer.length + chunk.length <= CHUNK_MAX_LEN) {
      buffer += chunk
    } else {
      if (buffer) merged.push(buffer)
      buffer = chunk
    }
  }
  if (buffer) merged.push(buffer)

  if (merged.length >= 2 && merged[merged.length - 1].length < CHUNK_MIN_LEN) {
    merged[merged.length - 2] += merged[merged.length - 1]
    merged.pop()
  }

  const result = merged.filter(c => c.trim().length > 0)

  if (result.length >= 2 && result[0].length > CHUNK_FIRST_MAX_LEN) {
    const firstChunk = splitLongText(result[0], CHUNK_FIRST_MAX_LEN)
    return [...firstChunk, ...result.slice(1)]
  }

  return result
}

function splitLongText(text: string, maxLen: number): string[] {
  if (text.length <= maxLen) return [text]

  const searchStart = Math.floor(maxLen * 0.4)
  const searchEnd = Math.min(text.length - 1, maxLen)

  let splitPos = -1
  let needPunctuation = false

  for (let i = searchEnd; i >= searchStart; i--) {
    if (/[。！？!?；;\n]/.test(text[i])) {
      splitPos = i + 1
      needPunctuation = false
      break
    }
  }

  if (splitPos === -1) {
    for (let i = searchEnd; i >= searchStart; i--) {
      if (/[，,、：:]/.test(text[i])) {
        splitPos = i + 1
        needPunctuation = false
        break
      }
    }
  }

  if (splitPos === -1) {
    splitPos = maxLen
    needPunctuation = true
  }

  let first = text.slice(0, splitPos)
  const rest = text.slice(splitPos)

  if (needPunctuation && !/[，,。！？!?；;、：:\n]$/.test(first)) {
    first += '，'
  }

  if (!rest.trim()) return [first]
  return [first, ...splitLongText(rest, maxLen)]
}


class TtsService {
  private synth: SpeechSynthesis | null = null

  /**
   * 后端朗读的统一播放内核。
   * <p>
   * 整篇文本在后端合成为一条连续 MP3 流，交由单个常驻 audio 元素播放，
   * 因此锁屏、后台、系统媒体键全部由平台原生承担。
   */
  private progressivePlayer = new ProgressiveStreamPlayer()

  /** 当前朗读的元信息，供系统媒体控制展示 */
  private currentMetadata: PlaybackMetadata | null = null

  /** 播放进度订阅者（界面进度条） */
  private progressListeners = new Set<(progress: PlaybackProgress) => void>()

  // ── 长文本朗读(分段连续播放)的健壮性控制 ──
  // 代际计数:每次新的 speakLongText / cancel 递增,旧回调链据此作废,避免多次点击并发播报
  private longTextGeneration = 0
  // heartbeat:定时 resume() 唤醒 Chrome speechSynthesis 长播挂起
  private longTextHeartbeatRef: ReturnType<typeof setInterval> | null = null
  // safety timeout:onend 万一丢失时兜底推进下一段,防止队列死在某一段
  private longTextSafetyTimeoutRef: ReturnType<typeof setTimeout> | null = null

  private clearLongTextTimers(): void {
    if (this.longTextHeartbeatRef) {
      clearInterval(this.longTextHeartbeatRef)
      this.longTextHeartbeatRef = null
    }
    if (this.longTextSafetyTimeoutRef) {
      clearTimeout(this.longTextSafetyTimeoutRef)
      this.longTextSafetyTimeoutRef = null
    }
  }

  constructor() {
    if (typeof window !== 'undefined' && window.speechSynthesis) {
      this.synth = window.speechSynthesis
    }
  }

  get supported(): boolean {
    return this.synth !== null
  }

  get speaking(): boolean {
    return this.synth?.speaking ?? false
  }

  get paused(): boolean {
    return this.synth?.paused ?? false
  }

  private get isBackendMode(): boolean {
    return useTtsStore.getState().backendMode && useTtsStore.getState().backendConfig !== null
  }

  /**
   * 探测当前配置的能力。
   * <p>
   * 渐进式播放不再依赖此结论选择播放路径（后端会自动选择最优容器），
   * 保留该探测用于观测与排查：返回 false 说明配置侧可能存在问题。
   */
  async checkStreamingSupport(): Promise<boolean> {
    const configId = useTtsStore.getState().backendConfig?.id
    if (!configId) return false
    try {
      return await isStreamingSupported(configId)
    } catch {
      return false
    }
  }

  async startReading(bookId: number, bookTitle: string, segments: string[], startSegment = 0): Promise<void> {
    if (this.isBackendMode) {
      await this.checkStreamingSupport()
    }
    if (this.isBackendMode && !this.synth) return
    if (!this.isBackendMode && !this.synth) return

    const filteredSegments = segments.filter(s => s.trim().length > 0)
    if (filteredSegments.length === 0) return

    useTtsStore.getState().startReading(bookId, bookTitle, filteredSegments, startSegment)
    this.speakSegment(startSegment)
  }

  async startReadingAsync(
    bookId: number,
    bookTitle: string,
    segmentsLoader: () => Promise<string[]>,
    startSegment = 0,
  ): Promise<void> {
    const hasSynth = this.synth !== null
    if (this.isBackendMode && !hasSynth) return
    if (!this.isBackendMode && !hasSynth) return

    useTtsStore.getState().startReadingAsync(bookId, bookTitle, startSegment)

    try {
      const segments = await segmentsLoader()
      const filteredSegments = segments.filter(s => s.trim().length > 0)
      if (filteredSegments.length === 0) {
        useTtsStore.getState().onSegmentsLoadFailed()
        return
      }

      useTtsStore.getState().onSegmentsLoaded(filteredSegments)

      const { status, bookId: currentBookId } = useTtsStore.getState()
      if (status === 'playing' && currentBookId === bookId) {
        this.speakSegment(startSegment)
      }
    } catch (e) {
      console.error('TTS async segment load failed:', e)
      useTtsStore.getState().onSegmentsLoadFailed()
    }
  }

  speakSegment(index: number): void {
    if (this.isBackendMode) {
      this.speakSegmentBackend(index)
    } else {
      this.speakSegmentBrowser(index)
    }
  }

  private speakSegmentBrowser(index: number): void {
    if (!this.synth) return

    const store = useTtsStore.getState()
    const { segments, bookId, status } = store
    if (status !== 'playing') return

    if (store.segmentsLoading) {
      setTimeout(() => {
        const currentStore = useTtsStore.getState()
        if (currentStore.status === 'playing' && !currentStore.segmentsLoading) {
          this.speakSegment(currentStore.segmentIndex)
        }
      }, 300)
      return
    }

    if (index < 0 || index >= segments.length) {
      this.cancel()
      useTtsStore.getState().stopReading()
      return
    }

    const text = segments[index]
    if (!text || !text.trim()) {
      useTtsStore.getState().setSegmentIndex(index)
      this.speakSegment(index + 1)
      return
    }

    useTtsStore.getState().setSegmentIndex(index)
    this.synth.cancel()

    const utterance = new SpeechSynthesisUtterance(text)
    const { settings, voices } = store

    utterance.rate = settings.rate

    if (settings.voiceURI) {
      const voice = voices.find((v: TtsVoice) => v.voiceURI === settings.voiceURI)
      if (voice) {
        const sv = this.synth.getVoices().find((v) => v.voiceURI === voice.voiceURI)
        if (sv) utterance.voice = sv
      }
    }

    utterance.onend = () => {
      const currentStatus = useTtsStore.getState().status
      const currentBookId = useTtsStore.getState().bookId
      if (currentStatus === 'playing' && currentBookId === bookId) {
        setTimeout(() => { this.speakSegment(index + 1) }, 200)
      }
    }

    utterance.onerror = (e) => {
      if (e.error !== 'canceled') {
        console.warn('TTS error:', e.error)
        const currentStatus = useTtsStore.getState().status
        const currentBookId = useTtsStore.getState().bookId
        if (currentStatus === 'playing' && currentBookId === bookId) {
          setTimeout(() => { this.speakSegment(index + 1) }, 200)
        }
      }
    }

    this.synth.speak(utterance)
  }

/**
   * 按段落朗读（正文朗读入口）
   * <p>
   * 与图书问答共用同一个播放内核：把该段落作为一条连续音频流播放。
   * 段落结束后依据代际守卫判断是否继续下一段，且即使 ended 事件丢失，
   * 播放内核的看门狗也会发现停滞并自动恢复，不会让队列静默死掉。
   */
  private async speakSegmentBackend(index: number): Promise<void> {
    const store = useTtsStore.getState()
    const { segments, bookId, status } = store
    if (status !== 'playing') return

    if (store.segmentsLoading) {
      setTimeout(() => {
        const currentStore = useTtsStore.getState()
        if (currentStore.status === 'playing' && !currentStore.segmentsLoading) {
          this.speakSegment(currentStore.segmentIndex)
        }
      }, 300)
      return
    }

    if (index < 0 || index >= segments.length) {
      this.cancel()
      useTtsStore.getState().stopReading()
      return
    }

    const text = segments[index]
    if (!text || !text.trim()) {
      useTtsStore.getState().setSegmentIndex(index)
      this.speakSegment(index + 1)
      return
    }

    useTtsStore.getState().setSegmentIndex(index)

    const meta: PlaybackMetadata = {
      title: store.bookTitle || '朗读',
      artist: `第 ${index + 1} 段 / 共 ${segments.length} 段`,
      album: store.bookTitle || '朗读',
    }
    if (this.currentMetadata) {
      meta.artworkUrl = this.currentMetadata.artworkUrl
    }
    this.currentMetadata = meta

    await this.speakBackendViaStream(text, () => {
      const current = useTtsStore.getState()
      if (current.status === 'playing' && current.bookId === bookId) {
        this.speakSegment(index + 1)
      }
    }, meta)
  }

  /**
   * 朗读一段文本
   *
   * @param text     待朗读纯文本
   * @param onEnd    播放结束回调
   * @param metadata 系统媒体控制元信息（锁屏/通知栏展示用）
   */
  speakSingleText(text: string, onEnd?: () => void, metadata?: PlaybackMetadata): void {
    if (this.isBackendMode) {
      this.speakBackendViaStream(text, onEnd, metadata)
      return
    }
    // 浏览器语音统一走分片路径：一次性读长文本在移动端会静默失败
    this.speakFallbackBrowser(text, onEnd)
  }

  /**
   * 在用户手势中同步调用，解锁后端播放所需的 audio 元素。
   * <p>
   * iOS Safari 要求音频播放必须由用户手势发起；而朗读需要先向后端申请播放凭据，
   * 手势链会在 await 处断开。点击的同步阶段先用静音音频激活元素即可绕过该限制。
   */
  primeForGesture(): void {
    // 这里不能按 backendMode 提前返回：backendConfig 是异步获取的，
    // 页面加载后的第一次点击时它还是 null，此时若跳过解锁，
    // 真正开始播放已经在 await 之后、脱离用户手势，iOS 会直接拒绝播放。
    // 解锁本身只是让一个常驻元素播一小段静音，代价可忽略。
    this.progressivePlayer.primeFromGesture()
  }


  /**
   * 后端朗读：统一走渐进式音频流
   * <p>
   * 整段文本作为一个连续 MP3 流交给常驻 audio 元素播放。
   * 相比旧实现「逐段合成 + 逐个 Audio 串联 + 只等 ended」：
   * <ul>
   *   <li>没有段间空隙，也没有「某段的 ended 丢失导致整条链路死掉」</li>
   *   <li>锁屏 / 后台 / 系统媒体键由平台原生承担</li>
   *   <li>播放位置停滞会被看门狗发现并从断点自动续播</li>
   * </ul>
   * 因此不再需要按文本长度分片，也不需要非流式 WAV 兜底路径。
   */
  private async speakBackendViaStream(
    text: string,
    onEnd?: () => void,
    metadata?: PlaybackMetadata,
  ): Promise<void> {
    const configId = useTtsStore.getState().backendConfig?.id
    const meta: PlaybackMetadata = metadata ?? this.currentMetadata ?? { title: '朗读' }
    this.currentMetadata = meta

    try {
      await this.progressivePlayer.start(text, configId, meta, {
        onEnd: () => {
          onEnd?.()
        },
        onInterrupted: () => {
          // 被系统中断（来电、其它 App 抢占音频焦点）且自动恢复失败：
          // 明确告知界面已暂停，避免按钮停留在「朗读中」的假状态
          console.warn('[TTS] 播放被系统中断，自动恢复失败，已暂停')
          useTtsStore.getState().pause()
        },
        onStoppedBySystem: () => {
          // 锁屏 / 耳机按了「停止」：凭据与音源已释放，无法原地继续，
          // 界面必须退出朗读态，否则会留下一个点不动的假控件
          onEnd?.()
        },
        onError: (e) => {
          // 只对本次朗读降级，不修改全局 backendMode：
          // 单次瞬时故障（网络抖动、TTS 上游超时）不应让整个会话永久退化到浏览器语音。
          console.warn('[TTS] 后端朗读失败，本次降级到浏览器语音：', e.message)
          // 先结束进度条：浏览器语音没有可定位的媒体元素，
          // 保留进度条只会得到一个停在原地、按键全部失效的假控件。
          onEnd?.()
          this.speakFallbackBrowser(text, onEnd)
        },
        onProgress: (progress) => {
          this.emitProgressToListeners(progress)
        },
      })
    } catch (e) {
      console.warn('[TTS] 后端朗读启动失败，本次降级到浏览器语音：', e)
      this.speakFallbackBrowser(text, onEnd)
    }
  }

  /**
   * 后端不可用时的浏览器语音降级
   * <p>
   * 必须按标点分片朗读：speechSynthesis 一次读长文本在多数移动端会静默失败
   * （不出声也不触发 onend），正是「读一两段就停」在纯浏览器路径上的成因。
   * 因此这里复用带 heartbeat + 安全超时的分片实现，而不是一次性塞整篇。
   */
  private speakFallbackBrowser(text: string, onEnd?: () => void): void {
    if (!this.synth) {
      onEnd?.()
      return
    }
    this.longTextGeneration += 1
    const generation = this.longTextGeneration
    this.speakLongTextBrowser(smartChunkText(text), onEnd, generation)
  }

  /**
   * 朗读任意长度文本（图书问答、正文朗读等入口）
   * <p>
   * 后端模式下不再分片：整篇文本作为一条连续音频流播放，因此「长文本」与
   * 「短文本」走完全相同的路径，不存在两种模式之间的行为差异。
   * 浏览器语音模式仍按标点分片（speechSynthesis 无法一次读太长且需要 heartbeat 兜底）。
   *
   * @param text     待朗读纯文本
   * @param onEnd    播放结束回调
   * @param metadata 系统媒体控制元信息（锁屏/通知栏展示用）
   */
  speakLongText(text: string, onEnd?: () => void, metadata?: PlaybackMetadata): void {
    // 新一轮朗读：递增代际，作废所有旧回调链
    this.longTextGeneration += 1
    const generation = this.longTextGeneration

    if (this.isBackendMode) {
      console.log('[TTS] 后端朗读，整篇单流播放，textLength=', text.length)
      this.speakBackendViaStream(text, onEnd, metadata)
      return
    }

    const chunks = smartChunkText(text)
    console.log('[TTS] 浏览器语音朗读，chunks=', chunks.length)
    this.speakLongTextBrowser(chunks, onEnd, generation)
  }

  private speakLongTextBrowser(chunks: string[], onEnd?: () => void, generation = 0): void {
    if (!this.synth) return
    this.synth.cancel()
    this.clearLongTextTimers()

    let currentIndex = 0
    const synth = this.synth

    const finishAll = () => {
      if (generation !== this.longTextGeneration) return
      this.clearLongTextTimers()
      onEnd?.()
    }

    const scheduleSafetyTimeout = (chunkText: string) => {
      if (this.longTextSafetyTimeoutRef) {
        clearTimeout(this.longTextSafetyTimeoutRef)
      }
      // 兜底:若 onend 长时间不触发(Chrome 挂起/语音异常),强制推进下一段
      // 中文每字约 300ms,取 max(15s, len*300) + 5s 缓冲
      const timeoutMs = Math.max(15000, chunkText.length * 300) + 5000
      this.longTextSafetyTimeoutRef = setTimeout(() => {
        if (generation !== this.longTextGeneration) return
        console.warn('[TTS longText] safety timeout fired, advancing chunk', currentIndex)
        if (this.longTextHeartbeatRef) {
          clearInterval(this.longTextHeartbeatRef)
          this.longTextHeartbeatRef = null
        }
        currentIndex++
        speakNext()
      }, timeoutMs)
    }

    const speakNext = () => {
      // 代际守卫:被 cancel / 新一轮 speakLongText 作废后,旧回调直接退出
      if (generation !== this.longTextGeneration) return

      if (currentIndex >= chunks.length) {
        finishAll()
        return
      }

      const chunkText = chunks[currentIndex]
      const utterance = new SpeechSynthesisUtterance(chunkText)
      const { settings, voices } = useTtsStore.getState()

      utterance.rate = settings.rate

      if (settings.voiceURI) {
        const voice = voices.find((v: TtsVoice) => v.voiceURI === settings.voiceURI)
        if (voice) {
          const sv = synth.getVoices().find((v) => v.voiceURI === voice.voiceURI)
          if (sv) utterance.voice = sv
        }
      }

      // 启动本段兜底定时器
      scheduleSafetyTimeout(chunkText)

      // heartbeat:Chrome speechSynthesis 连续播放多段时会自动挂起,定时 resume() 唤醒
      if (!this.longTextHeartbeatRef) {
        this.longTextHeartbeatRef = setInterval(() => {
          try { synth.resume() } catch { /* ignore */ }
        }, 5000)
      }

      utterance.onend = () => {
        if (generation !== this.longTextGeneration) return
        // 正常结束,清除本段兜底定时器
        if (this.longTextSafetyTimeoutRef) {
          clearTimeout(this.longTextSafetyTimeoutRef)
          this.longTextSafetyTimeoutRef = null
        }
        const pause = getPauseMs(chunkText)
        currentIndex++
        setTimeout(() => {
          if (generation !== this.longTextGeneration) return
          speakNext()
        }, pause)
      }

      utterance.onerror = (e) => {
        if (generation !== this.longTextGeneration) return
        if (this.longTextSafetyTimeoutRef) {
          clearTimeout(this.longTextSafetyTimeoutRef)
          this.longTextSafetyTimeoutRef = null
        }
        // 被 cancel() 中断时不再递归,避免旧回调把已清空的队列又启动起来
        if (e.error === 'canceled' || e.error === 'interrupted') {
          if (this.longTextHeartbeatRef) {
            clearInterval(this.longTextHeartbeatRef)
            this.longTextHeartbeatRef = null
          }
          return
        }
        console.warn('TTS chunk error:', e.error)
        currentIndex++
        speakNext()
      }

      synth.speak(utterance)
    }

    speakNext()
  }

  /**
   * 暂停朗读
   * <p>
   * 后端模式暂停常驻 audio 元素：系统媒体控制条会同步显示为暂停态，
   * 位置得以保留，resume 可原地继续。
   */
  pause(): void {
    if (this.isBackendMode) {
      this.progressivePlayer.pause()
      return
    }
    this.synth?.pause()
  }

  /**
   * 继续朗读
   */
  resume(): void {
    if (this.isBackendMode) {
      this.progressivePlayer.resume()
      return
    }
    this.synth?.resume()
  }

  /**
   * 订阅播放进度（供界面渲染进度条）
   * <p>
   * 回调由播放内核按固定节拍推送，界面无需自己轮询；取消订阅后不再收到回调。
   *
   * @param listener 进度回调
   * @returns 取消订阅函数
   */
  onProgress(listener: (progress: PlaybackProgress) => void): () => void {
    this.progressListeners.add(listener)
    return () => {
      this.progressListeners.delete(listener)
    }
  }

  private emitProgressToListeners(progress: PlaybackProgress): void {
    this.progressListeners.forEach((listener) => {
      try {
        listener(progress)
      } catch {
        // 单个订阅者异常不应影响播放
      }
    })
  }

  /**
   * 当前播放进度，供界面显示进度条
   *
   * @returns 播放位置与总时长（秒）；后端未在播放时返回 null
   */
  getProgress(): { currentTime: number; duration: number; seekable: boolean } | null {
    if (!this.isBackendMode) return null
    const p = this.progressivePlayer
    if (!p.playing && p.currentTime === 0) return null
    return { currentTime: p.currentTime, duration: p.duration, seekable: p.seekable }
  }

  /**
   * 跳转到指定播放位置（秒）
   *
   * @param seconds 目标位置
   */
  seek(seconds: number): void {
    this.progressivePlayer.seek(seconds)
  }

  /**
   * 停止朗读并释放全部播放资源
   * <p>
   * 会被多个入口高频调用（切书、关闭面板、切换消息等），因此必须幂等且不抛异常。
   */
  cancel(): void {
    // 先递增代际 + 清理长文本定时器：作废所有 speakLongText 的旧回调，
    // 防止 cancel 后旧回调仍推进队列或重启语音（多次点击并发播报的根因）
    this.longTextGeneration += 1
    this.clearLongTextTimers()

    // 播放内核负责：暂停 audio、解除音源（立即中止仍在进行的下载）、
    // 释放服务端播放凭据、清空系统媒体控制信息
    this.progressivePlayer.stop()
    this.synth?.cancel()
  }

  /**
   * 页面卸载时彻底释放播放资源
   */
  dispose(): void {
    this.cancel()
    this.progressivePlayer.dispose()
  }
}

export const ttsService = new TtsService()
