import request from '@/utils/request'
import type { PageResult } from '@/types/common'
import type { CommentVO } from '@/api/comment'

/** 用户主页 VO */
export interface UserProfileVO {
  id: number
  nickname: string
  avatar: string | null
  bio: string | null
  mood: string | null
  age: number | null
  gender: string | null
  mbti: string | null
  followerCount: number
  followingCount: number
  isFollowing: boolean
  completedBooks: number
  readingBooks: number
}

/** 用户书籍项 */
export interface UserBookItem {
  bookId: number
  title: string
  author: string | null
  coverUrl: string | null
  format: string
  progress: number
}

/** 用户书籍 VO */
export interface UserBooksVO {
  readingBooks: UserBookItem[]
  completedBooks: UserBookItem[]
}

/** 获取用户主页 */
export function getUserProfile(userId: number) {
  return request.get<UserProfileVO>(`/user-profile/${userId}`)
}

/** 获取用户书籍 */
export function getUserBooks(userId: number) {
  return request.get<UserBooksVO>(`/user-profile/${userId}/books`)
}

/** 获取用户书评 */
export function getUserComments(userId: number, page = 1, size = 10) {
  return request.get<PageResult<CommentVO>>(`/user-profile/${userId}/comments`, { params: { page, size } })
}

/** 更新简介 */
export function updateBio(bio: string) {
  return request.put('/user-profile/bio', { bio })
}
