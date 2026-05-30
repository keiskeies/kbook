import { useEffect, useRef } from 'react'
import { refreshAccessToken, getAccessToken } from '@/utils/token-refresh'
import { useAuthStore } from '@/store/auth'

const REFRESH_THRESHOLD = 5 * 60 * 1000
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
        refreshingRef.current = false
        return
      }
    } catch {
      // ignore
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
