import { create } from 'zustand'

interface ScrollCache {
  [key: string]: number
}

interface PageDataCache {
  [key: string]: unknown
}

interface KeepAliveState {
  activeTab: string
  scrollCache: ScrollCache
  pageData: PageDataCache
  setActiveTab: (path: string) => void
  saveScroll: (path: string, scrollTop: number) => void
  getScroll: (path: string) => number | undefined
  clearScroll: (path: string) => void
  savePageData: (path: string, data: unknown) => void
  getPageData: <T = unknown>(path: string) => T | undefined
  clearPageData: (path: string) => void
}

export const useKeepAliveStore = create<KeepAliveState>((set, get) => ({
  activeTab: '/home',
  scrollCache: {},
  pageData: {},
  setActiveTab: (path) => set({ activeTab: path }),
  saveScroll: (path, scrollTop) =>
    set((state) => ({
      scrollCache: { ...state.scrollCache, [path]: scrollTop },
    })),
  getScroll: (path) => get().scrollCache[path],
  clearScroll: (path) =>
    set((state) => {
      const next = { ...state.scrollCache }
      delete next[path]
      return { scrollCache: next }
    }),
  savePageData: (path, data) =>
    set((state) => ({
      pageData: { ...state.pageData, [path]: data },
    })),
  getPageData: (path) => get().pageData[path] as undefined,
  clearPageData: (path) =>
    set((state) => {
      const next = { ...state.pageData }
      delete next[path]
      return { pageData: next }
    }),
}))
