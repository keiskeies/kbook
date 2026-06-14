import request from '@/utils/request'

/** 管理员发送绑定邮箱验证码 */
export function sendBindEmailCode(email: string) {
  return request.post('/admin/account/bind-email/send-code', { email })
}

/** 管理员绑定邮箱 */
export function bindEmail(email: string, code: string) {
  return request.post('/admin/account/bind-email', { email, code })
}
