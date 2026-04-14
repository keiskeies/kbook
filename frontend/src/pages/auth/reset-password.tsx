import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { resetPassword, sendCode } from '@/api/auth'
import { ROUTES } from '@/constants'
import { useCountdown } from '@/hooks/useCountdown'
import { toast } from 'sonner'
import ClickCaptcha from '@/components/auth/ClickCaptcha'

export default function ResetPasswordPage() {
  const navigate = useNavigate()
  const { countdown, start: startCountdown } = useCountdown(60)

  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [sendingCode, setSendingCode] = useState(false)
  const [showCaptcha, setShowCaptcha] = useState(false)

  const handleSendCodeClick = () => {
    if (!email.trim()) {
      toast.error('请输入邮箱')
      return
    }
    setShowCaptcha(true)
  }

  const handleCaptchaSuccess = async (captchaId: string) => {
    setShowCaptcha(false)
    try {
      setSendingCode(true)
      await sendCode(email, 'reset', captchaId)
      startCountdown()
      toast.success('验证码已发送')
    } catch (err: any) {
      toast.error(err.message || '发送失败')
    } finally {
      setSendingCode(false)
    }
  }

  const handleReset = async () => {
    if (!email.trim()) {
      toast.error('请输入邮箱')
      return
    }
    if (!code.trim()) {
      toast.error('请输入验证码')
      return
    }
    if (newPassword.length < 6 || newPassword.length > 20) {
      toast.error('密码长度应为6-20位')
      return
    }
    if (newPassword !== confirmPassword) {
      toast.error('两次密码不一致')
      return
    }

    try {
      setLoading(true)
      await resetPassword(email, code, newPassword)
      toast.success('密码重置成功，请重新登录')
      navigate(ROUTES.LOGIN)
    } catch (err: any) {
      toast.error(err.message || '重置失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen flex-col px-6 pt-safe-top">
      <div className="py-8">
        <h1 className="mb-2 text-2xl font-bold">重置密码</h1>
        <p className="text-sm text-muted-foreground">通过邮箱验证码重置您的密码</p>
      </div>

      <div className="space-y-4">
        <input
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="请输入注册邮箱"
          autoComplete="email"
          className="w-full rounded-xl border bg-background px-4 py-3 text-sm outline-none focus:ring-2 focus:ring-primary"
        />

        <div className="flex gap-3">
          <input
            type="text"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder="验证码"
            maxLength={6}
            inputMode="numeric"
            className="flex-1 rounded-xl border bg-background px-4 py-3 text-sm outline-none focus:ring-2 focus:ring-primary"
          />
          <button
            onClick={handleSendCodeClick}
            disabled={countdown > 0 || sendingCode}
            className="flex-shrink-0 rounded-xl bg-primary px-4 py-3 text-sm text-primary-foreground disabled:opacity-50"
          >
            {sendingCode ? '发送中...' : countdown > 0 ? `${countdown}s` : '获取验证码'}
          </button>
        </div>

        <input
          type="password"
          value={newPassword}
          onChange={(e) => setNewPassword(e.target.value)}
          placeholder="新密码（6-20位）"
          autoComplete="new-password"
          className="w-full rounded-xl border bg-background px-4 py-3 text-sm outline-none focus:ring-2 focus:ring-primary"
        />

        <input
          type="password"
          value={confirmPassword}
          onChange={(e) => setConfirmPassword(e.target.value)}
          placeholder="确认新密码"
          autoComplete="new-password"
          className="w-full rounded-xl border bg-background px-4 py-3 text-sm outline-none focus:ring-2 focus:ring-primary"
        />

        <button
          onClick={handleReset}
          disabled={loading}
          className="w-full rounded-xl bg-primary py-3 text-sm font-medium text-primary-foreground disabled:opacity-50"
        >
          {loading ? '重置中...' : '重置密码'}
        </button>
      </div>

      <div className="mt-6 text-center text-sm text-muted-foreground">
        想起密码了？
        <button onClick={() => navigate(ROUTES.LOGIN)} className="text-primary">
          返回登录
        </button>
      </div>

      {/* 点击验证码弹窗 */}
      <ClickCaptcha
        open={showCaptcha}
        onSuccess={handleCaptchaSuccess}
        onCancel={() => setShowCaptcha(false)}
      />
    </div>
  )
}
