import { create } from 'zustand'
import { STORAGE_KEYS } from '@/constants'
import type { UserInfo } from '@/api/auth'
import { getCurrentUser } from '@/api/user'
import { broadcastTokenUpdate, broadcastTokenCleared } from '@/utils/token-sync'

export type { UserInfo } from '@/api/auth'

interface AuthState {
  token: string | null
  userInfo: UserInfo | null
  isAuthenticated: boolean

  setAuth: (token: string, refreshToken: string, userInfo: UserInfo) => void
  updateUserInfo: (userInfo: Partial<UserInfo>) => void
  fetchUserInfo: () => Promise<void>
  logout: () => void
  hydrate: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  token: null,
  userInfo: null,
  isAuthenticated: false,

  setAuth: (token, refreshToken, userInfo) => {
    localStorage.setItem(STORAGE_KEYS.TOKEN, token)
    localStorage.setItem(STORAGE_KEYS.REFRESH_TOKEN, refreshToken)
    localStorage.setItem(STORAGE_KEYS.USER_INFO, JSON.stringify(userInfo))
    broadcastTokenUpdate(token, refreshToken)
    set({ token, userInfo, isAuthenticated: true })
  },

  updateUserInfo: (partial) => {
    set((state) => {
      if (!state.userInfo) return state
      const userInfo = { ...state.userInfo, ...partial }
      localStorage.setItem(STORAGE_KEYS.USER_INFO, JSON.stringify(userInfo))
      return { userInfo }
    })
  },

  fetchUserInfo: async () => {
    const { token } = useAuthStore.getState()
    if (!token) return
    try {
      const res = await getCurrentUser()
      const data = res
      if (!data) return
      const userInfo = data as UserInfo
      localStorage.setItem(STORAGE_KEYS.USER_INFO, JSON.stringify(userInfo))
      set({ userInfo })
    } catch { /* ignore */ }
  },

  logout: () => {
    localStorage.removeItem(STORAGE_KEYS.TOKEN)
    localStorage.removeItem(STORAGE_KEYS.REFRESH_TOKEN)
    localStorage.removeItem(STORAGE_KEYS.USER_INFO)
    broadcastTokenCleared()
    set({ token: null, userInfo: null, isAuthenticated: false })
  },

  hydrate: () => {
    const token = localStorage.getItem(STORAGE_KEYS.TOKEN)
    const userInfoStr = localStorage.getItem(STORAGE_KEYS.USER_INFO)
    if (token && userInfoStr) {
      try {
        const userInfo = JSON.parse(userInfoStr) as UserInfo
        set({ token, userInfo, isAuthenticated: true })
      } catch {
        localStorage.removeItem(STORAGE_KEYS.USER_INFO)
      }
    }
  },
}))
