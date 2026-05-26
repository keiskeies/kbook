import request from '@/utils/request'

/** 用户阅读偏好项 */
export interface UserBookPreferenceItem {
  id: number
  userId: number
  category: 'TAG' | 'AUTHOR' | 'FORMAT'
  value: string
  type: 'EXCLUDE' | 'INCLUDE'
  createdAt: string
}

// ==================== 排除偏好（不想看） ====================

/** 添加排除偏好 */
export function addExcludePreference(category: string, value: string) {
  return request.post<UserBookPreferenceItem>('/user/preferences/exclude', { category, value })
}

/** 取消排除偏好（恢复推荐） */
export function removeExcludePreference(category: string, value: string) {
  return request.delete('/user/preferences/exclude', { params: { category, value } })
}

/** 获取当前用户所有排除偏好 */
export function getExcludePreferences() {
  return request.get<UserBookPreferenceItem[]>('/user/preferences/exclude')
}

// ==================== 喜欢偏好（想看） ====================

/** 添加喜欢偏好 */
export function addIncludePreference(category: string, value: string) {
  return request.post<UserBookPreferenceItem>('/user/preferences/include', { category, value })
}

/** 取消喜欢偏好 */
export function removeIncludePreference(category: string, value: string) {
  return request.delete('/user/preferences/include', { params: { category, value } })
}

/** 获取当前用户所有喜欢偏好 */
export function getIncludePreferences() {
  return request.get<UserBookPreferenceItem[]>('/user/preferences/include')
}

// ==================== 全部偏好 ====================

/** 获取当前用户所有偏好 */
export function getAllPreferences() {
  return request.get<UserBookPreferenceItem[]>('/user/preferences')
}
