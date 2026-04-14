import request from '@/utils/request'
import type { BookshelfItem } from '@/types/book'

/** 获取书架列表 */
export function getBookshelf() {
  return request.get<BookshelfItem[]>('/bookshelf')
}

/** 加入书架 */
export function addToBookshelf(bookId: number) {
  return request.post(`/bookshelf/${bookId}`)
}

/** 从书架移除 */
export function removeFromBookshelf(bookId: number) {
  return request.delete(`/bookshelf/${bookId}`)
}

/** 检查是否在书架中 */
export function checkInBookshelf(bookId: number) {
  return request.get<boolean>(`/bookshelf/check/${bookId}`)
}

/** 获取书架数量 */
export function getBookshelfCount() {
  return request.get<number>('/bookshelf/count')
}
