import { create } from 'zustand'

interface UiState {
  tabBarVisible: boolean
  setTabBarVisible: (visible: boolean) => void
}

export const useUiStore = create<UiState>((set) => ({
  tabBarVisible: true,
  setTabBarVisible: (visible) => set({ tabBarVisible: visible }),
}))
