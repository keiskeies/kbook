import { useTtsStore } from '@/store/tts'
import type { TtsVoice } from '@/store/tts'
import { synthesizeTts } from '@/api/adminTts'

const PCM_SAMPLE_RATE = 24000
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

class PcmStreamPlayer {
  private audioContext: AudioContext | null = null
  private nextPlayTime = 0
  private gainNode: GainNode | null = null

  init() {
    if (!this.audioContext) {
      this.audioContext = new AudioContext({ sampleRate: PCM_SAMPLE_RATE })
    }
    if (this.audioContext.state === 'suspended') {
      this.audioContext.resume()
    }
    this.gainNode = this.audioContext.createGain()
    this.gainNode.connect(this.audioContext.destination)
    this.nextPlayTime = this.audioContext.currentTime
  }

  playPcmChunk(pcmBase64: string) {
    if (!this.audioContext || !this.gainNode) return

    const binaryStr = atob(pcmBase64)
    const bytes = new Uint8Array(binaryStr.length)
    for (let i = 0; i < binaryStr.length; i++) {
      bytes[i] = binaryStr.charCodeAt(i)
    }

    const int16 = new Int16Array(bytes.buffer)
    const float32 = new Float32Array(int16.length)
    for (let i = 0; i < int16.length; i++) {
      float32[i] = int16[i] / 32768.0
    }

    const audioBuffer = this.audioContext.createBuffer(1, float32.length, PCM_SAMPLE_RATE)
    audioBuffer.getChannelData(0).set(float32)

    const source = this.audioContext.createBufferSource()
    source.buffer = audioBuffer
    source.connect(this.gainNode)

    const now = this.audioContext.currentTime
    if (this.nextPlayTime < now) {
      this.nextPlayTime = now
    }
    source.start(this.nextPlayTime)
    this.nextPlayTime += audioBuffer.duration
  }

  stop() {
    if (this.audioContext) {
      this.audioContext.close()
      this.audioContext = null
    }
    this.gainNode = null
  }

  addPause(durationMs: number) {
    if (!this.audioContext) return
    const now = this.audioContext.currentTime
    if (this.nextPlayTime < now) {
      this.nextPlayTime = now
    }
    this.nextPlayTime += durationMs / 1000
  }

  get currentTime(): number {
    return this.audioContext?.currentTime ?? 0
  }

  get isPlaying(): boolean {
    return this.audioContext !== null && this.audioContext.state === 'running'
  }

  get remainingTime(): number {
    if (!this.audioContext || !this.gainNode) return 0
    return Math.max(0, this.nextPlayTime - this.audioContext.currentTime)
  }
}

class TtsService {
  private synth: SpeechSynthesis | null = null
  private currentAudio: HTMLAudioElement | null = null
  private abortController: AbortController | null = null
  private streamPlayer: PcmStreamPlayer | null = null
  private streamingEnabled = false
  private longTextAbortController: AbortController | null = null

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

  private get isStreamingEnabled(): boolean {
    return Boolean(useTtsStore.getState().backendConfig?.streaming)
  }

  async checkStreamingSupport(): Promise<boolean> {
    this.streamingEnabled = this.isStreamingEnabled
    return this.streamingEnabled
  }

  startReading(bookId: number, bookTitle: string, segments: string[], startSegment = 0): void {
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

    this.currentAudio?.pause()
    this.currentAudio = null
    this.abortController?.abort()
    this.streamPlayer?.stop()
    this.streamPlayer = null

    useTtsStore.getState().setSegmentIndex(index)

    try {
      if (this.isStreamingEnabled) {
        await this.speakSegmentStream(index, text, bookId)
      } else {
        await this.speakSegmentNonStream(index, text, bookId)
      }
    } catch (e: any) {
      if (e.name === 'AbortError') return
      console.warn('Backend TTS error, falling back to browser:', e.message)
      useTtsStore.getState().setBackendMode(false)
      this.speakSegmentBrowser(index)
    }
  }

  private async speakSegmentNonStream(index: number, text: string, bookId: number | null): Promise<void> {
    this.abortController = new AbortController()
    const audioData = await synthesizeTts(text, useTtsStore.getState().backendConfig?.id)

    if (useTtsStore.getState().status !== 'playing' || useTtsStore.getState().bookId !== bookId) {
      return
    }

    const blob = new Blob([audioData], { type: 'audio/wav' })
    const url = URL.createObjectURL(blob)

    this.currentAudio = new Audio(url)

    const onFinish = () => {
      URL.revokeObjectURL(url)
      const currentStatus = useTtsStore.getState().status
      const currentBookId = useTtsStore.getState().bookId
      if (currentStatus === 'playing' && currentBookId === bookId) {
        setTimeout(() => { this.speakSegment(index + 1) }, 200)
      }
    }

    this.currentAudio.addEventListener('ended', onFinish)
    this.currentAudio.addEventListener('error', onFinish)

    await this.currentAudio.play()
  }

  private async speakSegmentStream(index: number, text: string, bookId: number | null): Promise<void> {
    this.streamPlayer = new PcmStreamPlayer()
    this.streamPlayer.init()

    const token = localStorage.getItem('kbook_token')
    const configId = useTtsStore.getState().backendConfig?.id

    const response = await fetch('/api/tts/synthesize/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': token ? `Bearer ${token}` : '',
        'Accept': 'text/event-stream',
      },
      body: JSON.stringify({ text, configId }),
    })

    if (!response.ok) {
      throw new Error(`TTS stream request failed: HTTP ${response.status}`)
    }

    const reader = response.body?.getReader()
    if (!reader) {
      throw new Error('TTS stream: no readable body')
    }

    const decoder = new TextDecoder()
    let buffer = ''
    let streamDone = false

    while (!streamDone) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (line.startsWith('event:audio')) {
          continue
        }
        if (line.startsWith('data:')) {
          const data = line.substring(5).trim()
          if (!data) continue

          this.streamPlayer.playPcmChunk(data)
        }
        if (line.startsWith('event:done')) {
          streamDone = true
          break
        }
        if (line.startsWith('event:error')) {
          const errorData = line.startsWith('data:') ? line.substring(5).trim() : '流式合成失败'
          throw new Error(errorData)
        }
      }
    }

    const checkPlaybackEnd = () => {
      if (!this.streamPlayer) return
      const currentStatus = useTtsStore.getState().status
      const currentBookId = useTtsStore.getState().bookId
      if (currentStatus === 'playing' && currentBookId === bookId) {
        setTimeout(() => { this.speakSegment(index + 1) }, 200)
      }
    }

    const estimatedDuration = text.length * 0.15
    setTimeout(checkPlaybackEnd, estimatedDuration * 1000)
  }

  speakSingleText(text: string, onEnd?: () => void): void {
    if (this.isBackendMode) {
      this.speakSingleTextBackend(text, onEnd)
    } else {
      this.speakSingleTextBrowser(text, onEnd)
    }
  }

  private speakSingleTextBrowser(text: string, onEnd?: () => void): void {
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

    utterance.onend = () => onEnd?.()
    utterance.onerror = (e) => {
      if (e.error !== 'canceled') {
        console.warn('TTS error:', e.error)
        onEnd?.()
      }
    }

    this.synth.speak(utterance)
  }

  private async speakSingleTextBackend(text: string, onEnd?: () => void): Promise<void> {
    try {
      this.currentAudio?.pause()
      this.currentAudio = null
      this.streamPlayer?.stop()
      this.streamPlayer = null

      if (this.isStreamingEnabled) {
        this.streamPlayer = new PcmStreamPlayer()
        this.streamPlayer.init()

        const token = localStorage.getItem('kbook_token')
        const configId = useTtsStore.getState().backendConfig?.id

        const response = await fetch('/api/tts/synthesize/stream', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': token ? `Bearer ${token}` : '',
            'Accept': 'text/event-stream',
          },
          body: JSON.stringify({ text, configId }),
        })

        if (!response.ok) throw new Error(`HTTP ${response.status}`)

        const reader = response.body?.getReader()
        if (!reader) throw new Error('No readable body')

        const decoder = new TextDecoder()
        let buffer = ''

        while (true) {
          const { done, value } = await reader.read()
          if (done) break

          buffer += decoder.decode(value, { stream: true })
          const lines = buffer.split('\n')
          buffer = lines.pop() || ''

          for (const line of lines) {
            if (line.startsWith('event:audio')) continue
            if (line.startsWith('data:')) {
              const data = line.substring(5).trim()
              if (data) this.streamPlayer!.playPcmChunk(data)
            }
            if (line.startsWith('event:done')) break
            if (line.startsWith('event:error')) {
              throw new Error('TTS stream error')
            }
          }
        }

        const estimatedDuration = text.length * 0.15
        setTimeout(() => onEnd?.(), estimatedDuration * 1000)
      } else {
        const audioData = await synthesizeTts(text, useTtsStore.getState().backendConfig?.id)
        const blob = new Blob([audioData], { type: 'audio/wav' })
        const url = URL.createObjectURL(blob)

        this.currentAudio = new Audio(url)

        const onFinish = () => {
          URL.revokeObjectURL(url)
          onEnd?.()
        }
        this.currentAudio.addEventListener('ended', onFinish)
        this.currentAudio.addEventListener('error', onFinish)

        await this.currentAudio.play()
      }
    } catch (e) {
      console.warn('Backend TTS single text error, falling back to browser:', e)
      useTtsStore.getState().setBackendMode(false)
      this.speakSingleTextBrowser(text, onEnd)
    }
  }

  speakLongText(text: string, onEnd?: () => void): void {
    const chunks = smartChunkText(text)
    if (chunks.length <= 1) {
      this.speakSingleText(text, onEnd)
      return
    }
    if (this.isBackendMode) {
      this.speakLongTextBackend(chunks, onEnd)
    } else {
      this.speakLongTextBrowser(chunks, onEnd)
    }
  }

  private speakLongTextBrowser(chunks: string[], onEnd?: () => void): void {
    if (!this.synth) return
    this.synth.cancel()

    let currentIndex = 0

    const speakNext = () => {
      if (currentIndex >= chunks.length) {
        onEnd?.()
        return
      }

      const utterance = new SpeechSynthesisUtterance(chunks[currentIndex])
      const { settings, voices } = useTtsStore.getState()

      utterance.rate = settings.rate

      if (settings.voiceURI) {
        const voice = voices.find((v: TtsVoice) => v.voiceURI === settings.voiceURI)
        if (voice) {
          const sv = this.synth!.getVoices().find((v) => v.voiceURI === voice.voiceURI)
          if (sv) utterance.voice = sv
        }
      }

      utterance.onend = () => {
        const pause = getPauseMs(chunks[currentIndex])
        currentIndex++
        setTimeout(speakNext, pause)
      }

      utterance.onerror = (e) => {
        if (e.error !== 'canceled') {
          console.warn('TTS chunk error:', e.error)
          currentIndex++
          speakNext()
        }
      }

      this.synth!.speak(utterance)
    }

    speakNext()
  }

  private async speakLongTextBackend(chunks: string[], onEnd?: () => void): Promise<void> {
    this.currentAudio?.pause()
    this.currentAudio = null
    this.streamPlayer?.stop()
    this.streamPlayer = null
    this.longTextAbortController?.abort()
    this.longTextAbortController = new AbortController()
    const signal = this.longTextAbortController.signal

    try {
      if (this.isStreamingEnabled) {
        await this.speakLongTextBackendStream(chunks, signal, onEnd)
      } else {
        await this.speakLongTextBackendNonStream(chunks, signal, onEnd)
      }
    } catch (e: any) {
      if (e.name === 'AbortError') return
      console.warn('Backend TTS long text error, falling back to browser:', e)
      useTtsStore.getState().setBackendMode(false)
      this.speakLongTextBrowser(chunks, onEnd)
    }
  }

  private async speakLongTextBackendStream(chunks: string[], signal: AbortSignal, onEnd?: () => void): Promise<void> {
    this.streamPlayer = new PcmStreamPlayer()
    this.streamPlayer.init()

    for (let i = 0; i < chunks.length; i++) {
      if (signal.aborted) break

      const token = localStorage.getItem('kbook_token')
      const configId = useTtsStore.getState().backendConfig?.id

      const response = await fetch('/api/tts/synthesize/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': token ? `Bearer ${token}` : '',
          'Accept': 'text/event-stream',
        },
        body: JSON.stringify({ text: chunks[i], configId }),
        signal,
      })

      if (!response.ok) throw new Error(`HTTP ${response.status}`)

      const reader = response.body?.getReader()
      if (!reader) throw new Error('No readable body')

      const decoder = new TextDecoder()
      let buffer = ''
      let chunkDone = false

      while (!chunkDone) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('event:audio')) continue
          if (line.startsWith('data:')) {
            const data = line.substring(5).trim()
            if (data) this.streamPlayer!.playPcmChunk(data)
          }
          if (line.startsWith('event:done')) {
            chunkDone = true
            break
          }
          if (line.startsWith('event:error')) {
            throw new Error('TTS stream chunk error')
          }
        }
      }

      if (i < chunks.length - 1) {
        this.streamPlayer!.addPause(getPauseMs(chunks[i]))
      }
    }

    const remaining = this.streamPlayer?.remainingTime ?? 0
    setTimeout(() => {
      this.streamPlayer?.stop()
      this.streamPlayer = null
      onEnd?.()
    }, (remaining + 0.5) * 1000)
  }

  private async speakLongTextBackendNonStream(chunks: string[], signal: AbortSignal, onEnd?: () => void): Promise<void> {
    for (let i = 0; i < chunks.length; i++) {
      if (signal.aborted) break

      const audioData = await synthesizeTts(chunks[i], useTtsStore.getState().backendConfig?.id)
      const blob = new Blob([audioData], { type: 'audio/wav' })
      const url = URL.createObjectURL(blob)

      await new Promise<void>((resolve) => {
        const audio = new Audio(url)
        this.currentAudio = audio

        const finish = () => {
          URL.revokeObjectURL(url)
          const pause = i < chunks.length - 1 ? getPauseMs(chunks[i]) : 0
          if (pause > 0) {
            setTimeout(resolve, pause)
          } else {
            resolve()
          }
        }

        audio.addEventListener('ended', finish)
        audio.addEventListener('error', finish)

        audio.play().catch(() => {
          URL.revokeObjectURL(url)
          resolve()
        })
      })

      this.currentAudio = null
    }

    onEnd?.()
  }

  pause(): void {
    if (this.streamPlayer) {
      return
    }
    if (this.isBackendMode) {
      this.currentAudio?.pause()
    } else {
      this.synth?.pause()
    }
  }

  resume(): void {
    if (this.streamPlayer) {
      return
    }
    if (this.isBackendMode) {
      this.currentAudio?.play().catch(() => {})
    } else {
      this.synth?.resume()
    }
  }

  cancel(): void {
    this.currentAudio?.pause()
    this.currentAudio = null
    this.abortController?.abort()
    this.abortController = null
    this.longTextAbortController?.abort()
    this.longTextAbortController = null
    this.streamPlayer?.stop()
    this.streamPlayer = null
    this.synth?.cancel()
  }
}

export const ttsService = new TtsService()
