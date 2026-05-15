import { useEffect, useState } from 'react'
import { getMatchScores } from '@/api/book'
import { useAuthStore } from '@/store/auth'

/**
 * 批量获取图书与当前用户的匹配分
 * @param bookIds 图书ID列表
 * @returns Map<bookId, matchScore>，0~1 之间，无画像时为空
 */
export function useMatchScores(bookIds: number[]) {
  const [scores, setScores] = useState<Record<string, number>>({})
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)

  useEffect(() => {
    if (!isAuthenticated || !bookIds || bookIds.length === 0) return

    getMatchScores(bookIds)
      .then((res) => {
        const data = (res as any)?.data || (res as any) || {}
        setScores(data)
      })
      .catch(() => setScores({}))
  }, [isAuthenticated, bookIds.join(',')])

  return scores
}
