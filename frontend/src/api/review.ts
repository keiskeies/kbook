import request from '@/utils/request'
import type { PageResult } from '@/types/common'

export interface ReviewVO {
  id: number
  bookId: number
  bookTitle: string | null
  bookAuthor: string | null
  bookCoverUrl: string | null
  userId: number
  nickname: string | null
  avatar: string | null
  rating: number | null
  title: string | null
  contentMd: string
  isSpoiler: boolean
  captureCount: number
  progressPercent: number | null
  chapterId: string | null
  mine: boolean
  createdAt: string
  updatedAt: string
}

export interface ReviewPayload {
  rating?: number | null
  title?: string | null
  contentMd: string
  isSpoiler?: boolean
}

export type ReviewSort = 'latest' | 'hot' | 'rating'

export function createReview(bookId: number, payload: ReviewPayload) {
  return request.post<ReviewVO>(`/books/${bookId}/reviews`, payload)
}

export function getBookReviews(bookId: number, params: { sort?: ReviewSort; page?: number; size?: number }) {
  return request.get<PageResult<ReviewVO>>(`/books/${bookId}/reviews`, { params })
}

export function getReview(reviewId: number) {
  return request.get<ReviewVO>(`/reviews/${reviewId}`)
}

export function getMyReviews(page = 1, size = 10) {
  return request.get<PageResult<ReviewVO>>('/users/me/reviews', { params: { page, size } })
}

export function updateReview(reviewId: number, payload: ReviewPayload) {
  return request.put<ReviewVO>(`/reviews/${reviewId}`, payload)
}

export function deleteReview(reviewId: number) {
  return request.delete<void>(`/reviews/${reviewId}`)
}
