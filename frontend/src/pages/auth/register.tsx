import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { register, sendCode } from '@/api/auth'
import { ROUTES } from '@/constants'
import { useCountdown } from '@/hooks/useCountdown'
import { toast } from 'sonner'
import ClickCaptcha from '@/components/auth/ClickCaptcha'
import { BookOpen, Mail, Lock, KeyRound } from 'lucide-react'

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
      await register({ email, code, password })
      toast.success('注册成功！')
      // 注册成功后跳转到 Onboarding 引导页
      navigate('/onboarding', { replace: true })
    } catch (err: any) {
      toast.error(err.message || '注册未完成')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen page-enter">
      {/* PC端左侧品牌区 */}
      <div className="hidden md:flex md:w-[45%] lg:w-[50%] bg-gradient-to-br from-primary/90 via-primary/80 to-primary/60 items-center justify-center p-12">
        <div className="max-w-md text-center">
          <div className="mx-auto mb-8 flex h-20 w-20 items-center justify-center rounded-3xl bg-white/20 shadow-2xl backdrop-blur-sm">
            <BookOpen className="h-10 w-10 text-white" strokeWidth={2.5} />
          </div>
          <h2 className="text-h1 font-bold text-white mb-4">加入 KBook</h2>
          <p className="text-lg text-white/80 leading-relaxed">注册后完善画像，获取精准推荐</p>
          <p className="mt-3 text-sm text-white/60">AI 个性化推荐 · 智能问答 · 跨端阅读</p>
        </div>
      </div>

      {/* 右侧表单区 — 移动端全屏 / PC端半屏 */}
      <div className="flex flex-1 flex-col items-center px-6 md:px-12 lg:px-20 pt-safe-top bg-gradient-to-b from-primary/5 to-background md:from-background md:to-background md:justify-center">
        <div className="w-full max-w-md md:max-w-sm">
          {/* 顶部品牌 — 移动端 */}
          <div className="py-8 md:py-0 md:mb-8 flex flex-col items-center md:items-start">
            <div className="md:hidden flex h-14 w-14 items-center justify-center rounded-2xl bg-primary mb-4">
              <BookOpen className="h-7 w-7 text-primary-foreground" strokeWidth={2.5} />
            </div>
            <h1 className="text-h3 font-bold">创建账号</h1>
            <p className="text-sm text-muted-foreground mt-1">注册后完善画像，获取精准推荐</p>
          </div>

          <div className="space-y-4">
            {/* 邮箱 */}
            <div className="relative">
              <Mail className="absolute left-3.5 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="你的邮箱"
                autoComplete="email"
                className="w-full rounded-xl border bg-background pl-10 pr-4 py-3 text-sm outline-none focus:ring-2 focus:ring-primary/50 transition-shadow"
              />
            </div>

            {/* 验证码 */}
            <div className="flex gap-3">
              <div className="relative flex-1">
                <KeyRound className="absolute left-3.5 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                <input
                  type="text"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  placeholder="验证码"
                  maxLength={6}
                  inputMode="numeric"
                  className="w-full rounded-xl border bg-background pl-10 pr-4 py-3 text-sm outline-none focus:ring-2 focus:ring-primary/50 transition-shadow"
                />
              </div>
              <button
                onClick={handleSendCodeClick}
                disabled={countdown > 0 || sendingCode}
                className="flex-shrink-0 rounded-xl bg-primary px-4 py-3 text-sm text-primary-foreground disabled:opacity-50 font-medium"
              >
                {sendingCode ? '发送中...' : countdown > 0 ? `${countdown}s` : '获取验证码'}
              </button>
            </div>

            {/* 密码 */}
            <div className="relative">
              <Lock className="absolute left-3.5 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="设置密码（6-20位）"
                autoComplete="new-password"
                className="w-full rounded-xl border bg-background pl-10 pr-4 py-3 text-sm outline-none focus:ring-2 focus:ring-primary/50 transition-shadow"
              />
            </div>

            {/* 确认密码 */}
            <div className="relative">
              <Lock className="absolute left-3.5 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
              <input
                type="password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                placeholder="确认密码"
                autoComplete="new-password"
                className="w-full rounded-xl border bg-background pl-10 pr-4 py-3 text-sm outline-none focus:ring-2 focus:ring-primary/50 transition-shadow"
              />
            </div>

            <button
              onClick={handleRegister}
              disabled={loading}
              className="w-full rounded-xl bg-primary py-3 text-sm font-semibold text-primary-foreground disabled:opacity-50 shadow-md shadow-primary/20 active:scale-[0.98] md:hover:bg-primary/90 transition-transform"
            >
              {loading ? '注册中...' : '注册'}
            </button>
          </div>

          <div className="mt-6 text-center text-sm text-muted-foreground">
            已有账号？
            <button onClick={() => navigate(ROUTES.LOGIN)} className="text-primary font-medium ml-1">
              去登录
            </button>
          </div>

          <p className="mt-4 text-center text-xs text-muted-foreground">
            注册即表示同意<a href="/terms" className="text-primary hover:underline">《用户协议》</a>和<a href="/privacy" className="text-primary hover:underline">《隐私政策》</a>
          </p>
        </div>
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
