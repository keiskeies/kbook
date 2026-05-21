import { STORAGE_KEYS } from '@/constants'
import { broadcastTokenUpdate, broadcastTokenCleared } from './token-sync'

/**
 * 统一的 Token 刷新协调器
 *
 * 解决 request.ts (axios) 和 sse-request.ts (fetch) 各自独立刷新导致的竞争问题：
 * - 多标签页同时 401
 * - SSE 连接和普通请求同时 401
 *
 * 使用全局锁 + 请求队列，确保同一时间只有一个 refresh 请求发出
 */

let isRefreshing = false
let refreshPromise: Promise<string | null> | null = null

/**
 * 刷新 access token，所有调用方共享同一个 refresh 请求
 *
 * @returns 新的 access token，失败返回 null
 */
export function refreshAccessToken(): Promise<string | null> {
  const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'

  if (isRefreshing && refreshPromise) {
    return refreshPromise
  }

  isRefreshing = true
  refreshPromise = new Promise((resolve) => {
    const refreshTokenVal = localStorage.getItem(STORAGE_KEYS.REFRESH_TOKEN)
    if (!refreshTokenVal) {
      isRefreshing = false
      refreshPromise = null
      resolve(null)
      return
    }

    fetch(`${BASE_URL}/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: refreshTokenVal }),
    })
      .then(async (resp) => {
        if (!resp.ok) {
          isRefreshing = false
          refreshPromise = null
          resolve(null)
          return
        }
        const result = await resp.json()
        if (result.code === 0 && result.data) {
          const newToken = result.data.token
          const newRefreshToken = result.data.refreshToken
          localStorage.setItem(STORAGE_KEYS.TOKEN, newToken)
          localStorage.setItem(STORAGE_KEYS.REFRESH_TOKEN, newRefreshToken)
          // 广播到其他标签页
          broadcastTokenUpdate(newToken, newRefreshToken)
          isRefreshing = false
          refreshPromise = null
          resolve(newToken)
        } else {
          isRefreshing = false
          refreshPromise = null
          resolve(null)
        }
      })
      .catch(() => {
        isRefreshing = false
        refreshPromise = null
        resolve(null)
      })
  })

  return refreshPromise
}

/**
 * 清除所有认证信息并重定向到登录页
 */
export function clearAuthAndRedirect() {
  localStorage.removeItem(STORAGE_KEYS.TOKEN)
  localStorage.removeItem(STORAGE_KEYS.REFRESH_TOKEN)
  localStorage.removeItem(STORAGE_KEYS.USER_INFO)
  broadcastTokenCleared()
  window.location.href = '/login'
}

/**
 * 获取当前 access token
 */
export function getAccessToken(): string | null {
  return localStorage.getItem(STORAGE_KEYS.TOKEN)
}

/**
 * 获取当前 refresh token
 */
export function getRefreshToken(): string | null {
  return localStorage.getItem(STORAGE_KEYS.REFRESH_TOKEN)
}
