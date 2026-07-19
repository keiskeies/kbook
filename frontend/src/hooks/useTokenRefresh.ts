import { useEffect, useRef } from 'react'
import { refreshAccessToken, getAccessToken, clearAuthAndRedirect } from '@/utils/token-refresh'
import { useAuthStore } from '@/store/auth'

const REFRESH_THRESHOLD = 2 * 60 * 1000
const CHECK_INTERVAL = 60 * 1000

function isTokenExpiringSoon(): boolean {
  const token = getAccessToken()
  if (!token) return false

  try {
    const parts = token.split('.')
    if (parts.length !== 3) return true
    const payload = JSON.parse(atob(parts[1]))
    if (!payload.exp) return true
    const expiresAt = payload.exp * 1000
    const now = Date.now()
    return now >= expiresAt - REFRESH_THRESHOLD
  } catch {
    return true
  }
}

export function useTokenRefresh() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const refreshingRef = useRef(false)
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const tryRefresh = async () => {
    if (!isAuthenticated || refreshingRef.current) return
    if (!isTokenExpiringSoon()) return

    refreshingRef.current = true
    try {
      const newToken = await refreshAccessToken()
      if (!newToken) {
        // 刷新失败（refresh token 也已失效）→ 立即清空并跳登录，避免"假登录态"
        clearAuthAndRedirect()
        return
      }
    } catch {
      // 网络异常等不可恢复错误 → 同样清空跳登录
      clearAuthAndRedirect()
    } finally {
      refreshingRef.current = false
    }
  }

  useEffect(() => {
    if (!isAuthenticated) {
      if (intervalRef.current) {
        clearInterval(intervalRef.current)
        intervalRef.current = null
      }
      return
    }

    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        tryRefresh()
      }
    }

    document.addEventListener('visibilitychange', handleVisibilityChange)

    intervalRef.current = setInterval(tryRefresh, CHECK_INTERVAL)

    tryRefresh()

    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange)
      if (intervalRef.current) {
        clearInterval(intervalRef.current)
        intervalRef.current = null
      }
    }
  }, [isAuthenticated])
}
