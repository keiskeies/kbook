import { useState } from 'react'
import { useAuthStore } from '@/store/auth'
import { sendBindEmailCode, bindEmail } from '@/api/admin'
import { useCountdown } from '@/hooks/useCountdown'
import { ArrowLeft, Mail } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'

/**
 * 管理员强制绑定邮箱页
 * 管理员登录后若 emailBound=false，强制引导绑定邮箱
 * 绑定后开启密码重置功能
 */
export default function BindEmailPage() {
  const navigate = useNavigate()
  const { userInfo, updateUserInfo } = useAuthStore()
  const { countdown, start: startCountdown } = useCountdown(60)

  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [loading, setLoading] = useState(false)
  const [sendingCode, setSendingCode] = useState(false)

  const handleSendCode = async () => {
    if (!email.trim()) {
      toast.error('请输入邮箱')
      return
    }
    try {
      setSendingCode(true)
      await sendBindEmailCode(email)
      startCountdown()
      toast.success('验证码已发送')
    } catch (err: any) {
      toast.error(err.message || '发送失败')
    } finally {
      setSendingCode(false)
    }
  }

  const handleBind = async () => {
    if (!email.trim()) {
      toast.error('请输入邮箱')
      return
    }
    if (!code.trim()) {
      toast.error('请输入验证码')
      return
    }

    try {
      setLoading(true)
      const updatedUser = await bindEmail(email, code)
      updateUserInfo({
        email: updatedUser.email,
        emailBound: updatedUser.emailBound,
      })
      toast.success('邮箱绑定成功，现在可以重置密码了')
      navigate('/home', { replace: true })
    } catch (err: any) {
      toast.error(err.message || '绑定失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen flex-col px-6 pt-safe-top">
      <header className="flex items-center gap-3 py-3">
        <button onClick={() => navigate(-1)} className="flex h-9 w-9 items-center justify-center rounded-full bg-muted">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="text-lg font-semibold">绑定邮箱</h1>
      </header>

      <div className="mt-4 mb-6 rounded-2xl bg-primary/5 p-4">
        <div className="flex items-start gap-3">
          <Mail className="mt-0.5 h-5 w-5 flex-shrink-0 text-primary" />
          <div>
            <h3 className="text-sm font-medium">请绑定您的邮箱</h3>
            <p className="mt-1 text-xs text-muted-foreground">
              管理员账号需要绑定邮箱后才能使用密码重置功能。
              请输入您常用的邮箱地址。
            </p>
          </div>
        </div>
      </div>

      <div className="space-y-4">
        <input
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="请输入常用邮箱"
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
            onClick={handleSendCode}
            disabled={countdown > 0 || sendingCode}
            className="flex-shrink-0 rounded-xl bg-primary px-4 py-3 text-sm text-primary-foreground disabled:opacity-50"
          >
            {sendingCode ? '发送中...' : countdown > 0 ? `${countdown}s` : '获取验证码'}
          </button>
        </div>

        <button
          onClick={handleBind}
          disabled={loading}
          className="w-full rounded-xl bg-primary py-3 text-sm font-medium text-primary-foreground disabled:opacity-50"
        >
          {loading ? '绑定中...' : '确认绑定'}
        </button>
      </div>
    </div>
  )
}
