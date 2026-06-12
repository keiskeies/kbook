import request from '@/utils/request'
import type { PageResult } from '@/types/common'

/** 通知 VO */
export interface NotificationVO {
  id: number
  triggerUserId: number | null
  triggerUserNickname: string
  triggerUserAvatar: string | null
  type: 'COMMENT_REPLY' | 'COMMENT_LIKED' | 'COMMENT_FAVORITED' | 'NEW_REVIEW' | 'ROUND_TABLE_REPORT'
  commentId: number | null
  bookId: number | null
  sessionId: string | null
  isRead: boolean
  createdAt: string
}

/** 获取通知列表 */
export function getNotifications(page = 1, size = 20) {
  return request.get<PageResult<NotificationVO>>('/user/notifications', { params: { page, size } })
}

/** 未读数 */
export function getUnreadCount() {
  return request.get<number>('/user/notifications/unread-count')
}

/** 标记已读 */
export function markAsRead(id: number) {
  return request.put(`/user/notifications/${id}/read`)
}

/** 全部已读 */
export function markAllAsRead() {
  return request.put('/user/notifications/read-all')
}
