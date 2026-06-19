import { create } from 'zustand'
import { STORAGE_KEYS } from '@/constants'
import type { TtsConfig } from '@/api/adminTts'

export type TtsStatus = 'idle' | 'playing' | 'paused'

export interface TtsVoice {
  voiceURI: string
  name: string
  lang: string
  localService: boolean
}

export interface TtsSettings {
  rate: number
  voiceURI: string | null
}

const DEFAULT_TTS_SETTINGS: TtsSettings = {
  rate: 1,
  voiceURI: null,
}

function loadTtsSettings(): TtsSettings {
  try {
    const saved = localStorage.getItem(STORAGE_KEYS.TTS_SETTINGS)
    if (saved) return { ...DEFAULT_TTS_SETTINGS, ...JSON.parse(saved) }
  } catch { /* ignore */ }
  return { ...DEFAULT_TTS_SETTINGS }
}

function saveTtsSettings(settings: TtsSettings) {
  localStorage.setItem(STORAGE_KEYS.TTS_SETTINGS, JSON.stringify(settings))
}

interface TtsState {
  status: TtsStatus
  bookId: number | null
  bookTitle: string | null
  segmentIndex: number
  totalSegments: number
  segments: string[]
  segmentsLoading: boolean
  settings: TtsSettings
  voices: TtsVoice[]
  playerExpanded: boolean

  /** 是否使用后端 TTS */
  backendMode: boolean
  /** 后端 TTS 配置 */
  backendConfig: TtsConfig | null

  /** 语音轮询是否激活 */
  voicePollingActive: boolean

  setStatus: (status: TtsStatus) => void
  startReading: (bookId: number, bookTitle: string, segments: string[], startSegment?: number) => void
  startReadingAsync: (bookId: number, bookTitle: string, startSegment?: number) => void
  onSegmentsLoaded: (segments: string[]) => void
  onSegmentsLoadFailed: () => void
  stopReading: () => void
  pause: () => void
  resume: () => void
  setSegmentIndex: (index: number) => void
  updateSettings: (partial: Partial<TtsSettings>) => void
  setVoices: (voices: TtsVoice[]) => void
  togglePlayerExpanded: () => void
  setPlayerExpanded: (v: boolean) => void

  /** 设置后端 TTS */
  setBackendConfig: (config: TtsConfig | null) => void
  setBackendMode: (mode: boolean) => void

  /** 启动/停止语音轮询 */
  setVoicePollingActive: (active: boolean) => void
}

export const useTtsStore = create<TtsState>((set, get) => ({
  status: 'idle',
  bookId: null,
  bookTitle: null,
  segmentIndex: 0,
  totalSegments: 0,
  segments: [],
  segmentsLoading: false,
  settings: loadTtsSettings(),
  voices: [],
  playerExpanded: false,
  backendMode: false,
  backendConfig: null,
  voicePollingActive: false,

  setStatus: (status) => set({ status }),

  startReading: (bookId, bookTitle, segments, startSegment = 0) => {
    set({
      status: 'playing',
      bookId,
      bookTitle,
      totalSegments: segments.length,
      segmentIndex: startSegment,
      segments,
      segmentsLoading: false,
      playerExpanded: true,
    })
  },

  startReadingAsync: (bookId, bookTitle, startSegment = 0) => {
    set({
      status: 'playing',
      bookId,
      bookTitle,
      segmentIndex: startSegment,
      totalSegments: 0,
      segments: [],
      segmentsLoading: true,
      playerExpanded: true,
    })
  },

  onSegmentsLoaded: (segments) => {
    const { status } = get()
    if (status !== 'idle') {
      set({
        totalSegments: segments.length,
        segments,
        segmentsLoading: false,
      })
    }
  },

  onSegmentsLoadFailed: () => {
    set({
      status: 'idle',
      segmentsLoading: false,
      bookId: null,
      bookTitle: null,
      segmentIndex: 0,
      totalSegments: 0,
      segments: [],
      playerExpanded: false,
    })
  },

  stopReading: () => set({
    status: 'idle',
    bookId: null,
    bookTitle: null,
    segmentIndex: 0,
    totalSegments: 0,
    segments: [],
    segmentsLoading: false,
    playerExpanded: false,
  }),

  pause: () => set({ status: 'paused' }),
  resume: () => set({ status: 'playing' }),
  setSegmentIndex: (index) => set({ segmentIndex: index }),

  updateSettings: (partial) => {
    const settings = { ...get().settings, ...partial }
    saveTtsSettings(settings)
    set({ settings })
  },

  setVoices: (voices) => set({ voices }),
  togglePlayerExpanded: () => set((s) => ({ playerExpanded: !s.playerExpanded })),
  setPlayerExpanded: (v) => set({ playerExpanded: v }),

  setBackendConfig: (config) => set({ backendConfig: config }),
  setBackendMode: (mode) => set({ backendMode: mode }),
  setVoicePollingActive: (active) => set({ voicePollingActive: active }),
}))

if (typeof window !== 'undefined' && window.speechSynthesis) {
  const synth = window.speechSynthesis

  const loadVoices = () => {
    const sv = synth.getVoices()
    if (sv.length > 0) {
      useTtsStore.getState().setVoices(
        sv.map((v) => ({
          voiceURI: v.voiceURI,
          name: v.name,
          lang: v.lang,
          localService: v.localService,
        }))
      )
    }
  }

  // 初始加载
  loadVoices()

  // 浏览器事件通知
  synth.onvoiceschanged = loadVoices

  // 补充轮询：某些场景下 onvoiceschanged 延迟触发或未触发
  // 前 30 秒每 3 秒轮询一次（10 次），之后每 60 秒检查一次变化
  let lastVoiceHash = ''

  const getVoiceHash = (): string => {
    const voices = synth.getVoices()
    return voices.map(v => `${v.name}:${v.localService}`).join('|')
  }

  // 快速轮询阶段：组件挂载后密集检测
  let quickPollCount = 0
  const quickPollTimer = setInterval(() => {
    if (quickPollCount >= 10) {
      clearInterval(quickPollTimer)
      return
    }
    quickPollCount++
    const hash = getVoiceHash()
    if (hash && hash !== lastVoiceHash) {
      lastVoiceHash = hash
      loadVoices()
    }
  }, 3000)

  // 慢速轮询阶段：持续监听语音列表变化（如 Edge 云语音迟到）
  setInterval(() => {
    // 只有 store 中 voicePollingActive 为 true 时才继续
    if (!useTtsStore.getState().voicePollingActive) return
    const hash = getVoiceHash()
    if (hash && hash !== lastVoiceHash) {
      lastVoiceHash = hash
      loadVoices()
    }
  }, 60000)
}
