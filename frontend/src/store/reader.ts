import { create } from 'zustand'
import { STORAGE_KEYS, READER_THEMES } from '@/constants'

/** 阅读器设置 */
export interface ReaderSettings {
  /** 字体 */
  fontFamily: string
  /** 字号（px） */
  fontSize: number
  /** 行高倍数 */
  lineHeight: number
  /** 段落间距（px） */
  paragraphSpacing: number
  /** 页面两边边距（px） */
  pageMargin: number
  /** 背景主题 key */
  themeKey: keyof typeof READER_THEMES
  /** 翻页动画: none / slide / fade */
  pageAnimation: string
  /** 亮度（0~1） */
  brightness: number
  /** 自动滚动速度（px/s, 0=关闭） */
  autoScrollSpeed: number
  /** PDF 缩放比例 */
  pdfScale: number
}

const DEFAULT_SETTINGS: ReaderSettings = {
  fontFamily: 'system-ui, "PingFang SC", "Microsoft YaHei", sans-serif',
  fontSize: 18,
  lineHeight: 1.8,
  paragraphSpacing: 16,
  pageMargin: 16,
  themeKey: 'LIGHT',
  pageAnimation: 'slide',
  brightness: 1,
  autoScrollSpeed: 0,
  pdfScale: 1,
}

interface ReaderState {
  settings: ReaderSettings
  /** 当前阅读的 bookId */
  currentBookId: number | null
  /** 是否显示设置面板 */
  showSettings: boolean
  /** 是否显示目录（EPUB） */
  showToc: boolean
  /** 全屏模式 */
  isFullscreen: boolean
  /** 系统是否处于夜间模式 */
  isSystemDark: boolean

  /** 实际生效的主题 key（夜间模式下强制 DARK） */
  effectiveThemeKey: () => keyof typeof READER_THEMES
  updateSettings: (partial: Partial<ReaderSettings>) => void
  resetSettings: () => void
  setCurrentBookId: (id: number | null) => void
  toggleSettings: () => void
  toggleToc: () => void
  setFullscreen: (v: boolean) => void
  setSystemDark: (v: boolean) => void
}

function loadSettings(): ReaderSettings {
  try {
    const saved = localStorage.getItem(STORAGE_KEYS.READER_SETTINGS)
    if (saved) {
      const parsed = JSON.parse(saved)
      // 修正旧版 pdfScale 默认值（旧版默认 1.5，新版默认 1）
      if (parsed.pdfScale === undefined || parsed.pdfScale === 1.5) {
        parsed.pdfScale = 1
      }
      return { ...DEFAULT_SETTINGS, ...parsed }
    }
  } catch { /* ignore */ }
  return { ...DEFAULT_SETTINGS }
}

function saveSettings(settings: ReaderSettings) {
  localStorage.setItem(STORAGE_KEYS.READER_SETTINGS, JSON.stringify(settings))
}

export const useReaderStore = create<ReaderState>((set, get) => ({
  settings: loadSettings(),
  currentBookId: null,
  showSettings: false,
  showToc: false,
  isFullscreen: false,
  isSystemDark: typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches,

  effectiveThemeKey: () => get().isSystemDark ? 'DARK' : get().settings.themeKey,

  updateSettings: (partial) => {
    const settings = { ...get().settings, ...partial }
    saveSettings(settings)
    set({ settings })
  },

  resetSettings: () => {
    saveSettings(DEFAULT_SETTINGS)
    set({ settings: { ...DEFAULT_SETTINGS } })
  },

  setCurrentBookId: (id) => set({ currentBookId: id }),
  toggleSettings: () => set((s) => ({ showSettings: !s.showSettings })),
  toggleToc: () => set((s) => ({ showToc: !s.showToc })),
  setFullscreen: (v) => set({ isFullscreen: v }),
  setSystemDark: (v) => set({ isSystemDark: v }),
}))
