import request from '@/utils/request'
import type { PageResult } from '@/types/common'

/** 评论 VO */
export interface CommentVO {
  id: number
  userId: number
  bookId: number
  chapterId: string | null
  parentId: number | null
  content: string
  likeCount: number
  replyCount: number
  favoriteCount: number
  liked: boolean
  favorited: boolean
  createdAt: string
  userNickname: string
  userAvatar: string | null
  bookTitle?: string
  bookCoverUrl?: string | null
}

/** 发表评论 */
export function createComment(data: {
  bookId: number
  chapterId?: string | null
  parentId?: number | null
  content: string
}) {
  return request.post<CommentVO>('/comments', data)
}

/** 删除评论 */
export function deleteComment(id: number) {
  return request.delete(`/comments/${id}`)
}

/** 获取书籍评论 */
export function getBookComments(bookId: number, page = 1, size = 20) {
  return request.get<PageResult<CommentVO>>(`/comments/book/${bookId}`, { params: { page, size } })
}

/** 获取章节评论 */
export function getChapterComments(bookId: number, chapterId: string, page = 1, size = 20) {
  return request.get<PageResult<CommentVO>>(`/comments/chapter/${bookId}`, { params: { chapterId, page, size } })
}

/** 获取评论回复 */
export function getCommentReplies(commentId: number) {
  return request.get<CommentVO[]>(`/comments/${commentId}/replies`)
}

/** 高分书评 */
export function getTopRatedComments(minLikes = 1, page = 1, size = 20) {
  return request.get<PageResult<CommentVO>>('/comments/top-rated', { params: { minLikes, page, size } })
}

/** 点赞 */
export function likeComment(id: number) {
  return request.post(`/comments/${id}/like`)
}

/** 取消点赞 */
export function unlikeComment(id: number) {
  return request.delete(`/comments/${id}/like`)
}

/** 收藏 */
export function favoriteComment(id: number) {
  return request.post(`/comments/${id}/favorite`)
}

/** 取消收藏 */
export function unfavoriteComment(id: number) {
  return request.delete(`/comments/${id}/favorite`)
}

/** 统计书籍评论数 */
export function countBookComments(bookId: number) {
  return request.get<number>(`/comments/count/book/${bookId}`)
}
