import request from '@/utils/request'
import type { PageResult } from '@/types/common'
import type { AiProviderConfig, ConnectionTestResult } from '@/types/ai'

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
  return request.get<ReviewStats>('/admin/stats')
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

/** 管理员发送绑定邮箱验证码 */
export function sendBindEmailCode(email: string) {
  return request.post('/admin/bind-email/send-code', { email })
}

/** 管理员绑定邮箱 */
export function bindEmail(email: string, code: string) {
  return request.post('/admin/bind-email', { email, code })
}

/** 发送邀请邮件 */
export interface InviteResult {
  email: string
  inviteCode: string
}

export function sendInvitation(email: string, bookId?: number) {
  return request.post<InviteResult>('/admin/invite', { email, bookId })
}

// ==================== AI 提供商配置管理 ====================

/** 获取所有 AI 配置 */
export function getAiConfigs() {
  return request.get<AiProviderConfig[]>('/admin/ai-provider')
}

/** 获取当前活跃的 AI 配置 */
export function getActiveAiConfig() {
  return request.get<AiProviderConfig>('/admin/ai-provider/active')
}

/** 保存 AI 配置（新增或更新） */
export function saveAiConfig(config: AiProviderConfig) {
  return request.post<AiProviderConfig>('/admin/ai-provider', config)
}

/** 删除 AI 配置 */
export function deleteAiConfig(id: number) {
  return request.delete(`/admin/ai-provider/${id}`)
}

/** 启用 AI 配置 */
export function enableAiConfig(id: number) {
  return request.post<AiProviderConfig>(`/admin/ai-provider/${id}/enable`)
}

/** 禁用 AI 配置 */
export function disableAiConfig(id: number) {
  return request.post(`/admin/ai-provider/${id}/disable`)
}

/** 测试已保存的 AI 配置连接 */
export function testAiConfig(id: number) {
  return request.post<ConnectionTestResult>(`/admin/ai-provider/${id}/test`, {}, { timeout: 120000 })
}

/** 测试未保存的 AI 配置连接（实时测试） */
export function testAiConnection(config: AiProviderConfig) {
  return request.post<ConnectionTestResult>('/admin/ai-provider/test', config, { timeout: 120000 })
}
