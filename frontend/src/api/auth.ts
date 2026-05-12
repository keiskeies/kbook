import request from '@/utils/request'

export interface LoginResult {
  token: string
  refreshToken: string
  userInfo: UserInfo
}

export interface UserInfo {
  id: number
  email: string
  nickname: string
  avatar: string | null
  role: 'USER' | 'ADMIN'
  status: 'PENDING' | 'APPROVED' | 'BANNED'
  emailBound: boolean
  birthday?: string | null
  gender?: 'MALE' | 'FEMALE' | 'OTHER' | null
  married?: boolean | null
  hasChildren?: boolean | null
  mbti?: string | null
  occupation?: string | null
  education?: string | null
  entrepreneurship?: string | null
  annualIncome?: string | null
  mood?: string | null
  bio?: string | null
  followerCount?: number
  followingCount?: number
}

/** 发送验证码 - scene: register | login | reset */
export function sendCode(email: string, scene: string = 'login', captchaId?: string) {
  return request.post('/auth/send-code', { email, scene, captchaId })
}

/** 验证码登录 */
export function loginByCode(email: string, code: string) {
  return request.post<LoginResult>('/auth/login/code', { email, code })
}

/** 密码登录 - 需要点击验证码 */
export function loginByPassword(email: string, password: string, captchaId: string) {
  return request.post<LoginResult>('/auth/login/password', { email, password, captchaId })
}

/** 注册 */
export function register(data: {
  email: string
  code: string
  password: string
  birthday?: string
  gender?: string
  married?: boolean
  hasChildren?: boolean
  mbti?: string
  occupation?: string
  education?: string
  entrepreneurship?: string
  annualIncome?: string
}) {
  return request.post('/auth/register', data)
}

/** 刷新 Token */
export function refreshToken(refreshToken: string) {
  return request.post<LoginResult>('/auth/refresh', { refreshToken })
}

/** 修改密码 */
export function changePassword(oldPassword: string, newPassword: string) {
  return request.post('/auth/change-password', { oldPassword, newPassword })
}

/** 重置密码 */
export function resetPassword(email: string, code: string, newPassword: string) {
  return request.post('/auth/reset-password', { email, code, newPassword })
}

/** 更新用户画像 */
export function updateTraits(data: {
  birthday?: string
  gender?: string
  married?: boolean
  hasChildren?: boolean
  mbti?: string
  occupation?: string
  education?: string
  entrepreneurship?: string
  annualIncome?: string
}) {
  return request.put('/user/profile/traits', data)
}

/** 更新心情状态 */
export function updateMood(mood: string) {
  return request.put('/user/profile/mood', null, { params: { mood } })
}
