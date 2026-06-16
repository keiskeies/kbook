import { create } from 'zustand'

interface UiState {
  tabBarVisible: boolean
  setTabBarVisible: (visible: boolean) => void
  recommendRefreshKey: number
  triggerRefreshRecommend: () => void
}

export const useUiStore = create<UiState>((set) => ({
  tabBarVisible: true,
  setTabBarVisible: (visible) => set({ tabBarVisible: visible }),
  recommendRefreshKey: 0,
  triggerRefreshRecommend: () => set((s) => ({ recommendRefreshKey: s.recommendRefreshKey + 1 })),
}))
