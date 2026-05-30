import { create } from 'zustand'
import { STORAGE_KEYS } from '@/constants'
import type { UserInfo as ApiUserInfo } from '@/api/auth'
import { getCurrentUser } from '@/api/user'
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
  childrenAgeRanges?: string | null
  mbti?: string | null
  occupation?: string | null
  aspirationEducation?: string | null
  entrepreneurship?: string | null
  aspirationIncome?: string | null
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
  fetchUserInfo: () => Promise<void>
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
      childrenAgeRanges: apiUserInfo.childrenAgeRanges,
      mbti: apiUserInfo.mbti,
      occupation: apiUserInfo.occupation,
      aspirationEducation: apiUserInfo.aspirationEducation,
      entrepreneurship: apiUserInfo.entrepreneurship,
      aspirationIncome: apiUserInfo.aspirationIncome,
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

  fetchUserInfo: async () => {
    const { token } = useAuthStore.getState()
    if (!token) return
    try {
      const res = await getCurrentUser()
      const data = (res as any)?.data || (res as any)
      if (!data) return
      const userInfo: UserInfo = {
        id: data.id,
        email: data.email,
        nickname: data.nickname,
        avatar: data.avatar,
        role: data.role as 'USER' | 'ADMIN',
        status: data.status as 'PENDING' | 'APPROVED' | 'BANNED',
        emailBound: data.emailBound,
        birthday: data.birthday ?? null,
        gender: data.gender as 'MALE' | 'FEMALE' | 'OTHER' | null,
        married: data.married ?? null,
        hasChildren: data.hasChildren ?? null,
        childrenAgeRanges: data.childrenAgeRanges ?? null,
        mbti: data.mbti ?? null,
        occupation: data.occupation ?? null,
        aspirationEducation: data.aspirationEducation ?? null,
        entrepreneurship: data.entrepreneurship ?? null,
        aspirationIncome: data.aspirationIncome ?? null,
        mood: data.mood ?? null,
        bio: data.bio ?? null,
        followerCount: data.followerCount ?? 0,
        followingCount: data.followingCount ?? 0,
      }
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
