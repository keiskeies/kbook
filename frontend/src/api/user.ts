import request from '@/utils/request'

export interface UserProfile {
  id: number
  email: string
  nickname: string
  avatar: string | null
  role: 'USER' | 'ADMIN'
  status: 'PENDING' | 'APPROVED' | 'BANNED'
  emailBound: boolean
}

/** 获取当前用户信息 */
export function getCurrentUser() {
  return request.get<UserProfile>('/user/me')
}

/** 更新用户资料（昵称） */
export function updateProfile(data: { nickname?: string }) {
  return request.put<UserProfile>('/user/profile', null, { params: data })
}

/** 上传头像 */
export function uploadAvatar(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post<UserProfile>('/user/avatar', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 300000,
  })
}
