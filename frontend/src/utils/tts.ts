import { useTtsStore } from '@/store/tts'
import type { TtsVoice } from '@/store/tts'
import { synthesizeTts } from '@/api/adminTts'

class TtsService {
  private synth: SpeechSynthesis | null = null
  private currentAudio: HTMLAudioElement | null = null
  private abortController: AbortController | null = null

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

    // Cancel current audio before starting new synthesis
    this.currentAudio?.pause()
    this.currentAudio = null
    this.abortController?.abort()

    useTtsStore.getState().setSegmentIndex(index)

    try {
      this.abortController = new AbortController()
      const audioData = await synthesizeTts(text, store.backendConfig?.id)

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
    } catch (e: any) {
      if (e.name === 'AbortError') return
      console.warn('Backend TTS error, falling back to browser:', e.message)
      useTtsStore.getState().setBackendMode(false)
      this.speakSegmentBrowser(index)
    }
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
    } catch (e) {
      console.warn('Backend TTS single text error, falling back to browser:', e)
      useTtsStore.getState().setBackendMode(false)
      this.speakSingleTextBrowser(text, onEnd)
    }
  }

  pause(): void {
    if (this.isBackendMode) {
      this.currentAudio?.pause()
    } else {
      this.synth?.pause()
    }
  }

  resume(): void {
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
    this.synth?.cancel()
  }
}

export const ttsService = new TtsService()
