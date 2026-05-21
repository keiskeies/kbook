import { create } from 'zustand'
import { STORAGE_KEYS } from '@/constants'
import type { UserInfo as ApiUserInfo } from '@/api/auth'
import { broadcastTokenUpdate, broadcastTokenCleared } from '@/utils/token-sync'

export interface UserInfo {
  id: number
  email: string
  nickname: string
  avatar: string | null
  role: 'USER' | 'ADMIN'
  status: 'PENDING' | 'APPROVED' | 'BANNED'
  emailBound: boolean
  birthday?: string | null
  gender?: 'MALE' | 'FEMALE' | 'OTHER' | null
  married?: boolean | null
  hasChildren?: boolean | null
  mbti?: string | null
  occupation?: string | null
  education?: string | null
  entrepreneurship?: string | null
  annualIncome?: string | null
  mood?: string | null
  bio?: string | null
  followerCount?: number
  followingCount?: number
}

interface AuthState {
  token: string | null
  userInfo: UserInfo | null
  isAuthenticated: boolean

  setAuth: (token: string, refreshToken: string, userInfo: ApiUserInfo) => void
  updateUserInfo: (userInfo: Partial<UserInfo>) => void
  logout: () => void
  hydrate: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  token: null,
  userInfo: null,
  isAuthenticated: false,

  setAuth: (token, refreshToken, apiUserInfo) => {
    const userInfo: UserInfo = {
      id: apiUserInfo.id,
      email: apiUserInfo.email,
      nickname: apiUserInfo.nickname,
      avatar: apiUserInfo.avatar,
      role: apiUserInfo.role as 'USER' | 'ADMIN',
      status: apiUserInfo.status as 'PENDING' | 'APPROVED' | 'BANNED',
      emailBound: apiUserInfo.emailBound,
      birthday: apiUserInfo.birthday,
      gender: apiUserInfo.gender as 'MALE' | 'FEMALE' | 'OTHER' | null,
      married: apiUserInfo.married,
      hasChildren: apiUserInfo.hasChildren,
      mbti: apiUserInfo.mbti,
      occupation: apiUserInfo.occupation,
      education: apiUserInfo.education,
      entrepreneurship: apiUserInfo.entrepreneurship,
      annualIncome: apiUserInfo.annualIncome,
      mood: apiUserInfo.mood,
      bio: apiUserInfo.bio,
      followerCount: apiUserInfo.followerCount,
      followingCount: apiUserInfo.followingCount,
    }
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
