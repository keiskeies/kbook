import { create } from 'zustand'
import { STORAGE_KEYS } from '@/constants'
import type { ReadingProgress, ProgressBatchItem } from '@/types/book'
import { reportProgress, batchReportProgress, getProgress } from '@/api/progress'

interface ProgressState {
  /** 本地进度缓存（断网降级） */
  localProgress: Record<number, ReadingProgress>
  /** 待上报队列（断网时暂存） */
  pendingReports: ProgressBatchItem[]
  /** 是否在线 */
  isOnline: boolean

  /** 更新本地进度 */
  setLocalProgress: (bookId: number, progress: ReadingProgress) => void
  /** 上报进度（含断网降级） */
  reportProgress: (bookId: number, progress: number, currentPosition: string | null) => Promise<void>
  /** 从服务器同步进度 */
  syncFromServer: (bookId: number) => Promise<void>
  /** 上报待上传队列 */
  flushPending: () => Promise<void>
  /** 设置在线状态 */
  setOnline: (online: boolean) => void
  /** 清理 */
  clear: () => void
}

export const useProgressStore = create<ProgressState>((set, get) => ({
  localProgress: {},
  pendingReports: [],
  isOnline: navigator.onLine,

  setLocalProgress: (bookId, progress) => {
    set((state) => {
      const updated = { ...state.localProgress, [bookId]: progress }
      localStorage.setItem(STORAGE_KEYS.LOCAL_PROGRESS, JSON.stringify(updated))
      return { localProgress: updated }
    })
  },

  reportProgress: async (bookId, progress, currentPosition) => {
    const progressData: ReadingProgress = {
      id: 0,
      userId: 0,
      bookId,
      progress,
      currentPosition,
      updatedAt: new Date().toISOString(),
    }

    // 无论在线与否，先更新本地
    get().setLocalProgress(bookId, progressData)

    if (get().isOnline) {
      try {
        const res = await reportProgress({ bookId, progress, currentPosition })
        if (res) {
          get().setLocalProgress(bookId, res as unknown as ReadingProgress)
        }
      } catch {
        // 上报失败，加入待上报队列
        const item: ProgressBatchItem = {
          bookId,
          progress,
          currentPosition,
          clientTimestamp: new Date().toISOString(),
        }
        set((state) => {
          const pending = [...state.pendingReports, item]
          localStorage.setItem(STORAGE_KEYS.PENDING_PROGRESS, JSON.stringify(pending))
          return { pendingReports: pending }
        })
      }
    } else {
      // 离线，加入待上报队列
      const item: ProgressBatchItem = {
        bookId,
        progress,
        currentPosition,
        clientTimestamp: new Date().toISOString(),
      }
      set((state) => {
        const pending = [...state.pendingReports, item]
        localStorage.setItem(STORAGE_KEYS.PENDING_PROGRESS, JSON.stringify(pending))
        return { pendingReports: pending }
      })
    }
  },

  syncFromServer: async (bookId) => {
    try {
      const res = await getProgress(bookId)
      if (res) {
        get().setLocalProgress(bookId, res as unknown as ReadingProgress)
      }
    } catch {
      // 同步失败使用本地缓存
    }
  },

  flushPending: async () => {
    const { pendingReports } = get()
    if (pendingReports.length === 0) return

    try {
      await batchReportProgress(pendingReports)
      set({ pendingReports: [] })
      localStorage.removeItem(STORAGE_KEYS.PENDING_PROGRESS)
    } catch {
      // 仍然失败，保留队列
    }
  },

  setOnline: (online) => {
    set({ isOnline: online })
    if (online) {
      get().flushPending()
    }
  },

  clear: () => {
    set({ localProgress: {}, pendingReports: [] })
    localStorage.removeItem(STORAGE_KEYS.LOCAL_PROGRESS)
    localStorage.removeItem(STORAGE_KEYS.PENDING_PROGRESS)
  },
}))

// 监听网络状态
if (typeof window !== 'undefined') {
  window.addEventListener('online', () => useProgressStore.getState().setOnline(true))
  window.addEventListener('offline', () => useProgressStore.getState().setOnline(false))
}
