export interface CommentVO {
  id: number
  content: string
  userId: number
  nickname: string
  avatar: string | null
  createdAt: string
}

export async function getComments() { return { data: [] } }
export async function addComment() { return { data: null } }
export async function deleteComment() { return { data: null } }
export async function getBookComments(_bookId: number, page = 1, size = 10) {
  return { data: { list: [], total: 0, page, size } }
}
export async function countBookComments(_bookId: number) {
  return { data: 0 }
}
