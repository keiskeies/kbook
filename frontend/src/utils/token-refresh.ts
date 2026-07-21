import { STORAGE_KEYS } from '@/constants'
import { broadcastTokenUpdate, broadcastTokenCleared } from './token-sync'
import { useAuthStore } from '@/store/auth'

/**
 * 统一的 Token 刷新协调器
 *
 * 解决 request.ts (axios) 和 sse-request.ts (fetch) 各自独立刷新导致的竞争问题：
 * - 多标签页同时 401
 * - SSE 连接和普通请求同时 401
 *
 * 使用全局锁 + 请求队列，确保同一时间只有一个 refresh 请求发出
 *
 * Refresh token 存储策略：
 * - 优先依赖 HttpOnly Cookie（后端设置，JS 读不到，不受 ITP 清理影响）
 * - localStorage 保留 refreshToken 作为兼容（老后端 / 灰度回退）
 * - 请求时 credentials: 'include' 让浏览器自动带 cookie
 * - body 同时传 refreshToken 作为兼容（后端优先 cookie，回退 body）
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
    // 兼容性：localStorage 中的 refreshToken 作为 body 回退（后端优先读 cookie）
    const refreshTokenVal = localStorage.getItem(STORAGE_KEYS.REFRESH_TOKEN)

    fetch(`${BASE_URL}/auth/refresh`, {
      method: 'POST',
      credentials: 'include', // 携带 HttpOnly Cookie 中的 refresh token
      headers: { 'Content-Type': 'application/json' },
      body: refreshTokenVal ? JSON.stringify({ refreshToken: refreshTokenVal }) : '{}',
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
          // 兼容性保留：localStorage 也存一份（不依赖，但用于多标签页广播）
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
 * <p>
 * 调后端 /api/auth/logout 清 cookie + 拉黑 access token。
 * 即使 access token 已过期导致 401，也静默清本地状态跳登录。
 */
export function clearAuthAndRedirect() {
  const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'
  const token = localStorage.getItem(STORAGE_KEYS.TOKEN)

  // 先清本地状态，避免循环
  localStorage.removeItem(STORAGE_KEYS.TOKEN)
  localStorage.removeItem(STORAGE_KEYS.REFRESH_TOKEN)
  localStorage.removeItem(STORAGE_KEYS.USER_INFO)
  broadcastTokenCleared()
  useAuthStore.setState({ token: null, userInfo: null, isAuthenticated: false })

  // 异步调后端 logout（清 cookie + 拉黑 access token），失败不阻塞跳转
  if (token) {
    fetch(`${BASE_URL}/auth/logout`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Authorization': `Bearer ${token}` },
    }).catch(() => { /* 静默失败：token 可能已过期，cookie 已会被后端清除 */ })
  }

  if (window.location.pathname !== '/login') {
    window.location.href = '/login'
  }
}

/**
 * 获取当前 access token
 */
export function getAccessToken(): string | null {
  return localStorage.getItem(STORAGE_KEYS.TOKEN)
}

/**
 * 获取当前 refresh token（仅兼容用，主路径已改用 HttpOnly Cookie）
 */
export function getRefreshToken(): string | null {
  return localStorage.getItem(STORAGE_KEYS.REFRESH_TOKEN)
}
