import request from '@/utils/request'

export interface BookTrashItem {
  trashId: number
  bookId: number
  title: string
  author: string | null
  coverUrl: string | null
  format: string
  formatTags: string | null
  fileSize: number | null
  rating: number
  trashedAt: string
}

export function moveToTrash(bookId: number) {
  return request.post(`/book-trash/${bookId}`)
}

export function removeFromTrash(bookId: number) {
  return request.delete(`/book-trash/${bookId}`)
}

export function checkInTrash(bookId: number) {
  return request.get<boolean>(`/book-trash/check/${bookId}`)
}

export function getTrashList() {
  return request.get<BookTrashItem[]>('/book-trash')
}

export function getTrashCount() {
  return request.get<number>('/book-trash/count')
}
