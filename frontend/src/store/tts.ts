import { create } from 'zustand'
import { STORAGE_KEYS } from '@/constants'

/** TTS 播放状态 */
export type TtsStatus = 'idle' | 'playing' | 'paused'

/** TTS 音色 */
export interface TtsVoice {
  voiceURI: string
  name: string
  lang: string
  localService: boolean
}

/** TTS 设置 */
export interface TtsSettings {
  /** 语速 0.5~2，默认 1 */
  rate: number
  /** 音色 URI */
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
  /** 播放状态 */
  status: TtsStatus
  /** 当前朗读的书籍 ID */
  bookId: number | null
  /** 当前朗读的书籍标题 */
  bookTitle: string | null
  /** 当前朗读的段索引 */
  segmentIndex: number
  /** 总段数 */
  totalSegments: number
  /** 全量段落缓存 —— 整本书的所有段落，退出阅读页后仍可用于朗读 */
  segments: string[]
  /** 段落是否正在加载中（EPUB 异步提取时为 true） */
  segmentsLoading: boolean
  /** TTS 设置 */
  settings: TtsSettings
  /** 可用音色列表 */
  voices: TtsVoice[]
  /** 浮动播放器是否展开 */
  playerExpanded: boolean

  // 操作
  setStatus: (status: TtsStatus) => void
  /** 开始朗读（缓存全量段落） */
  startReading: (bookId: number, bookTitle: string, segments: string[], startSegment?: number) => void
  /** 开始朗读（异步加载段落，EPUB 用） */
  startReadingAsync: (bookId: number, bookTitle: string, startSegment?: number) => void
  /** 段落加载完成，缓存到 store 并开始朗读 */
  onSegmentsLoaded: (segments: string[]) => void
  /** 段落加载失败 */
  onSegmentsLoadFailed: () => void
  /** 停止朗读 */
  stopReading: () => void
  /** 暂停 */
  pause: () => void
  /** 恢复 */
  resume: () => void
  /** 更新段索引 */
  setSegmentIndex: (index: number) => void
  /** 更新设置 */
  updateSettings: (partial: Partial<TtsSettings>) => void
  /** 设置可用音色 */
  setVoices: (voices: TtsVoice[]) => void
  /** 切换浮动播放器展开/收起 */
  togglePlayerExpanded: () => void
  setPlayerExpanded: (v: boolean) => void
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

  /** 异步开始朗读：先标记 loading，等段落加载完再开始播放 */
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

  /** 段落加载完成 */
  onSegmentsLoaded: (segments) => {
    const { bookId, status } = get()
    // 只有还在朗读状态才更新
    if (status !== 'idle') {
      set({
        totalSegments: segments.length,
        segments,
        segmentsLoading: false,
      })
    }
  },

  /** 段落加载失败 */
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
}))

// 初始化音色列表
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
