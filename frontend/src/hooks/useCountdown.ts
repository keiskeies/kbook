import { useState, useCallback, useRef } from 'react'

/**
 * 验证码倒计时 Hook
 * @param seconds 倒计时秒数，默认60
 */
export function useCountdown(seconds: number = 60) {
  const [countdown, setCountdown] = useState(0)
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const start = useCallback(() => {
    if (countdown > 0) return
    setCountdown(seconds)
    timerRef.current = setInterval(() => {
      setCountdown((prev) => {
        if (prev <= 1) {
          if (timerRef.current) clearInterval(timerRef.current)
          return 0
        }
        return prev - 1
      })
    }, 1000)
  }, [countdown, seconds])

  const reset = useCallback(() => {
    if (timerRef.current) clearInterval(timerRef.current)
    setCountdown(0)
  }, [])

  return { countdown, start, reset, isCounting: countdown > 0 }
}
