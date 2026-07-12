import { useQuery } from '@tanstack/react-query'
import { getMatchScores } from '@/api/book'
import { useAuthStore } from '@/store/auth'

/**
 * 批量获取图书与当前用户的匹配分
 * @param bookIds 图书ID列表
 * @returns Map<bookId, matchScore>，0~1 之间，无画像时为空
 */
export function useMatchScores(bookIds: number[]) {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)

  const { data: scores = {} } = useQuery({
    queryKey: ['match-scores', ...bookIds],
    queryFn: () => getMatchScores(bookIds),
    enabled: isAuthenticated && bookIds.length > 0,
  })

  return scores
}
