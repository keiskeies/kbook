import { useRef, useEffect, useCallback } from 'react'
import { useLocation } from 'react-router-dom'
import { useKeepAliveStore } from '@/store/keepAlive'

export function useScrollRestore(
  scrollRef: React.RefObject<HTMLElement | null>,
  key?: string,
) {
  const location = useLocation()
  const cacheKey = key || location.pathname
  const saveScroll = useKeepAliveStore((s) => s.saveScroll)
  const getScroll = useKeepAliveStore((s) => s.getScroll)
  const hasRestoredRef = useRef(false)

  const handleScroll = useCallback(() => {
    const el = scrollRef.current
    if (el) {
      saveScroll(cacheKey, el.scrollTop)
    }
  }, [cacheKey, saveScroll, scrollRef])

  useEffect(() => {
    if (hasRestoredRef.current) return
    hasRestoredRef.current = true

    const saved = getScroll(cacheKey)
    if (saved !== undefined && saved > 0 && scrollRef.current) {
      requestAnimationFrame(() => {
        if (scrollRef.current) {
          scrollRef.current.scrollTop = saved
        }
      })
    }
  }, [cacheKey, getScroll, scrollRef])

  useEffect(() => {
    return () => {
      const el = scrollRef.current
      if (el) {
        saveScroll(cacheKey, el.scrollTop)
      }
    }
  }, [cacheKey, saveScroll, scrollRef])

  return { handleScroll }
}
