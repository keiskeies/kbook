import request from '@/utils/request'
import type { PageResult } from '@/types/common'

export interface AdminUser {
  id: number
  email: string
  nickname: string
  avatar: string | null
  role: 'USER' | 'ADMIN'
  status: 'PENDING' | 'APPROVED' | 'BANNED'
  emailBound: boolean
  createdAt: string
  updatedAt: string
}

export interface ReviewStats {
  PENDING: number
  APPROVED: number
  BANNED: number
  TOTAL: number
}

/** 审核统计 */
export function getReviewStats() {
  return request.get<ReviewStats>('/admin/users/stats')
}

/** 待审核用户列表 */
export function getPendingUsers(page: number = 1, size: number = 10) {
  return request.get<PageResult<AdminUser>>('/admin/users/pending', { params: { page, size } })
}

/** 按状态筛选用户 */
export function getUsersByStatus(statuses: string[], page: number = 1, size: number = 10) {
  return request.get<PageResult<AdminUser>>('/admin/users', {
    params: { statuses: statuses.join(','), page, size }
  })
}

/** 搜索用户 */
export function searchUsers(keyword: string, status?: string, page: number = 1, size: number = 10) {
  return request.get<PageResult<AdminUser>>('/admin/users/search', {
    params: { keyword, status, page, size }
  })
}

/** 审核通过 */
export function approveUser(userId: number) {
  return request.post(`/admin/users/${userId}/approve`)
}

/** 批量审核通过 */
export function batchApprove(userIds: number[]) {
  return request.post<{ count: number }>('/admin/users/batch-approve', { userIds })
}

/** 审核拒绝 */
export function rejectUser(userId: number) {
  return request.post(`/admin/users/${userId}/reject`)
}

/** 批量拒绝 */
export function batchReject(userIds: number[]) {
  return request.post<{ count: number }>('/admin/users/batch-reject', { userIds })
}

/** 解封用户 */
export function unbanUser(userId: number) {
  return request.post(`/admin/users/${userId}/unban`)
}

/** 封禁用户（封禁已通过的用户） */
export function banUser(userId: number) {
  return request.post(`/admin/users/${userId}/ban`)
}

/** 发送邀请邮件 */
export interface InviteResult {
  email: string
  inviteCode: string
}

export function sendInvitation(email: string, bookId?: number) {
  return request.post<InviteResult>('/admin/users/invite', { email, bookId })
}
