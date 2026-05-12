import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { register, sendCode } from '@/api/auth'
import { ROUTES } from '@/constants'
import { useCountdown } from '@/hooks/useCountdown'
import { toast } from 'sonner'
import ClickCaptcha from '@/components/auth/ClickCaptcha'

const MBTI_OPTIONS = ['INTJ','INTP','ENTJ','ENTP','INFJ','INFP','ENFJ','ENFP','ISTJ','ISFJ','ESTJ','ESFJ','ISTP','ISFP','ESTP','ESFP']

export default function RegisterPage() {
  const navigate = useNavigate()
  const { countdown, start: startCountdown } = useCountdown(60)

  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [sendingCode, setSendingCode] = useState(false)
  const [showCaptcha, setShowCaptcha] = useState(false)

  // 画像字段（可选）
  const [birthday, setBirthday] = useState('')
  const [gender, setGender] = useState('')
  const [married, setMarried] = useState('')
  const [hasChildren, setHasChildren] = useState('')
  const [mbti, setMbti] = useState('')

  const handleSendCodeClick = () => {
    if (!email.trim()) {
      toast.error('输入邮箱')
      return
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      toast.error('邮箱格式不正确')
      return
    }
    setShowCaptcha(true)
  }

  const handleCaptchaSuccess = async (captchaId: string) => {
    setShowCaptcha(false)
    try {
      setSendingCode(true)
      await sendCode(email, 'register', captchaId)
      startCountdown()
      toast.success('验证码已发送')
    } catch (err: any) {
      toast.error(err.message || '验证码发送未完成')
    } finally {
      setSendingCode(false)
    }
  }

  const handleRegister = async () => {
    if (!email.trim()) {
      toast.error('输入邮箱')
      return
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      toast.error('邮箱格式不正确')
      return
    }
    if (!code.trim()) {
      toast.error('输入验证码')
      return
    }
    if (password.length < 6 || password.length > 20) {
      toast.error('密码长度应为6-20位')
      return
    }
    if (password !== confirmPassword) {
      toast.error('两次密码不一致')
      return
    }

    try {
      setLoading(true)
      await register({
        email, code, password,
        birthday: birthday || undefined,
        gender: gender || undefined,
        married: married ? married === 'yes' : undefined,
        hasChildren: hasChildren ? hasChildren === 'yes' : undefined,
        mbti: mbti || undefined,
      })
      toast.success('注册完成，等待管理员审核中')
      navigate(ROUTES.LOGIN)
    } catch (err: any) {
      toast.error(err.message || '注册未完成')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen flex-col px-6 pt-safe-top">
      <div className="py-8">
        <h1 className="mb-2 text-2xl font-bold">创建账号</h1>
        <p className="text-sm text-muted-foreground">注册 KBook，开始你的阅读之旅</p>
      </div>

      <div className="space-y-4">
        <input
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="你的邮箱"
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
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder="设置密码（6-20位）"
          autoComplete="new-password"
          className="w-full rounded-xl border bg-background px-4 py-3 text-sm outline-none focus:ring-2 focus:ring-primary"
        />

        <input
          type="password"
          value={confirmPassword}
          onChange={(e) => setConfirmPassword(e.target.value)}
          placeholder="确认密码"
          autoComplete="new-password"
          className="w-full rounded-xl border bg-background px-4 py-3 text-sm outline-none focus:ring-2 focus:ring-primary"
        />

        {/* 画像信息（可选） */}
        <div className="rounded-xl border border-dashed p-4 space-y-3">
          <p className="text-xs text-muted-foreground">以下信息可选填，用于个性化推荐</p>

          <input
            type="date"
            value={birthday}
            onChange={(e) => setBirthday(e.target.value)}
            max={new Date().toISOString().split('T')[0]}
            placeholder="出生日期"
            className="w-full rounded-lg border bg-background px-3 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary"
          />

          <select
            value={gender}
            onChange={(e) => setGender(e.target.value)}
            className="w-full rounded-lg border bg-background px-3 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary"
          >
            <option value="">选择性别（可选）</option>
            <option value="MALE">男</option>
            <option value="FEMALE">女</option>
            <option value="OTHER">其他</option>
          </select>

          <select
            value={married}
            onChange={(e) => setMarried(e.target.value)}
            className="w-full rounded-lg border bg-background px-3 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary"
          >
            <option value="">婚姻状况（可选）</option>
            <option value="yes">已婚</option>
            <option value="no">未婚</option>
          </select>

          <select
            value={hasChildren}
            onChange={(e) => setHasChildren(e.target.value)}
            className="w-full rounded-lg border bg-background px-3 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary"
          >
            <option value="">是否有孩子（可选）</option>
            <option value="yes">有孩子</option>
            <option value="no">无孩子</option>
          </select>

          <select
            value={mbti}
            onChange={(e) => setMbti(e.target.value)}
            className="w-full rounded-lg border bg-background px-3 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary"
          >
            <option value="">MBTI 人格（可选）</option>
            {MBTI_OPTIONS.map(m => (
              <option key={m} value={m}>{m}</option>
            ))}
          </select>
        </div>

        <button
          onClick={handleRegister}
          disabled={loading}
          className="w-full rounded-xl bg-primary py-3 text-sm font-medium text-primary-foreground disabled:opacity-50"
        >
          {loading ? '注册中...' : '注册'}
        </button>
      </div>

      <div className="mt-6 text-center text-sm text-muted-foreground">
        已有账号？
        <button onClick={() => navigate(ROUTES.LOGIN)} className="text-primary">
          去登录
        </button>
      </div>

      <p className="mt-4 text-center text-xs text-muted-foreground">
        注册即表示同意<a href="/terms" className="text-primary hover:underline">《用户协议》</a>和<a href="/privacy" className="text-primary hover:underline">《隐私政策》</a>
      </p>

      {/* 点击验证码弹窗 */}
      <ClickCaptcha
        open={showCaptcha}
        onSuccess={handleCaptchaSuccess}
        onCancel={() => setShowCaptcha(false)}
      />
    </div>
  )
}
