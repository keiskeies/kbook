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
}))

if (typeof window !== 'undefined' && window.speechSynthesis) {
  const loadVoices = () => {
    const sv = window.speechSynthesis.getVoices()
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
  loadVoices()
  window.speechSynthesis.onvoiceschanged = loadVoices
}
