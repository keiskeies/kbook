import { useState } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { useAuthStore } from '@/store/auth'
import { loginByCode, loginByPassword, sendCode } from '@/api/auth'
import { ROUTES } from '@/constants'
import { useCountdown } from '@/hooks/useCountdown'
import { toast } from 'sonner'
import { BookOpen } from 'lucide-react'
import ClickCaptcha from '@/components/auth/ClickCaptcha'

type LoginMode = 'code' | 'password'

export default function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { setAuth } = useAuthStore()
  const { countdown, start: startCountdown } = useCountdown(60)

  const [mode, setMode] = useState<LoginMode>('code')
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [sendingCode, setSendingCode] = useState(false)

  // 点击验证码
  const [showCaptcha, setShowCaptcha] = useState(false)
  const [captchaPurpose, setCaptchaPurpose] = useState<'sendCode' | 'login'>('sendCode')

  const from = (location.state as { from?: string })?.from || ROUTES.HOME

  // 验证码登录 - 点击"获取验证码" → 弹出点击验证
  const handleSendCodeClick = () => {
    if (!email.trim()) {
      toast.error('输入邮箱')
      return
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      toast.error('邮箱格式不正确')
      return
    }
    setCaptchaPurpose('sendCode')
    setShowCaptcha(true)
  }

  // 密码登录 - 点击"登录" → 弹出点击验证
  const handlePasswordLoginClick = () => {
    if (!email.trim()) {
      toast.error('请输入邮箱')
      return
    }
    if (!password.trim()) {
      toast.error('请输入密码')
      return
    }
    setCaptchaPurpose('login')
    setShowCaptcha(true)
  }

  // 点击验证成功回调
  const handleCaptchaSuccess = async (captchaId: string) => {
    setShowCaptcha(false)

    if (captchaPurpose === 'sendCode') {
      try {
        setSendingCode(true)
        await sendCode(email, 'login', captchaId)
        startCountdown()
        toast.success('验证码已发送')
      } catch (err: any) {
        toast.error(err.message || '验证码发送未完成')
      } finally {
        setSendingCode(false)
      }
    } else {
      try {
        setLoading(true)
        const result = await loginByPassword(email, password, captchaId)
        handleLoginResult(result)
      } catch (err: any) {
        toast.error(err.message || '暂时无法登录')
      } finally {
        setLoading(false)
      }
    }
  }

  // 验证码登录（不需要点击验证）
  const handleCodeLogin = async () => {
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
      const result = await loginByCode(email, code)
      handleLoginResult(result)
    } catch (err: any) {
      toast.error(err.message || '暂时无法登录')
    } finally {
      setLoading(false)
    }
  }

  const handleLoginResult = (result: any) => {
    setAuth(result.token, result.refreshToken, result.userInfo)
    if (result.userInfo.status === 'PENDING') {
      toast.warning('账号审核中，请耐心等待管理员通过')
    } else if (result.userInfo.status === 'BANNED') {
      toast.error('账号已被封禁')
    } else {
      toast.success('欢迎回来')
      navigate(from, { replace: true })
    }
  }

  // 统一登录按钮
  const handleLogin = () => {
    if (mode === 'code') {
      handleCodeLogin()
    } else {
      handlePasswordLoginClick()
    }
  }

  return (
    <div className="flex min-h-screen page-enter">
      {/* PC端左侧品牌区 */}
      <div className="hidden md:flex md:w-[45%] lg:w-[50%] bg-gradient-to-br from-primary via-primary/90 to-primary/70 items-center justify-center p-12">
        <div className="max-w-md text-center">
          <div className="mx-auto mb-8 flex h-20 w-20 items-center justify-center rounded-3xl bg-white/20 shadow-2xl backdrop-blur-sm">
            <BookOpen className="h-10 w-10 text-white" strokeWidth={2.5} />
          </div>
          <h2 className="text-h1 font-bold text-white mb-4">KBook</h2>
          <p className="text-lg text-white/80 leading-relaxed">AI 驱动的智能阅读平台</p>
          <p className="mt-3 text-sm text-white/60">个性化推荐 · AI 问答 · 沉浸式阅读</p>
        </div>
      </div>

      {/* 右侧表单区 — 移动端全屏 / PC端半屏 */}
      <div className="flex flex-1 flex-col items-center px-6 md:px-12 lg:px-20 pt-safe-top md:justify-center">
        <div className="w-full max-w-md md:max-w-sm">
          {/* 品牌头部 — 移动端 */}
          <div className="py-8 md:py-0 md:mb-8">
            <div className="md:hidden mb-4 flex h-12 w-12 items-center justify-center rounded-2xl bg-primary shadow-lg shadow-primary/25">
              <BookOpen className="h-6 w-6 text-primary-foreground" strokeWidth={2.5} />
            </div>
            <h1 className="mb-2 text-h3 font-bold">欢迎回来</h1>
            <p className="text-sm text-muted-foreground">登录 KBook，开启阅读之旅</p>
          </div>

          {/* 登录方式切换 */}
          <div className="mb-6 flex rounded-2xl bg-muted p-1">
            <button
              className={`flex-1 rounded-xl py-2.5 text-sm font-semibold transition-all duration-200 ${
                mode === 'code' ? 'bg-card shadow-sm text-foreground' : 'text-muted-foreground'
              }`}
              onClick={() => { setMode('code'); setCode(''); setPassword('') }}
            >
              验证码登录
            </button>
            <button
              className={`flex-1 rounded-xl py-2.5 text-sm font-semibold transition-all duration-200 ${
                mode === 'password' ? 'bg-card shadow-sm text-foreground' : 'text-muted-foreground'
              }`}
              onClick={() => { setMode('password'); setCode(''); setPassword('') }}
            >
              密码登录
            </button>
          </div>

          {/* 表单 */}
          <div className="space-y-4">
            <div>
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="你的邮箱"
                autoComplete="email"
                className="w-full rounded-2xl border bg-card px-4 py-3.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 transition-shadow"
              />
            </div>

            {mode === 'code' ? (
              <div className="flex gap-3">
                <input
                  type="text"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  placeholder="邮箱验证码"
                  maxLength={6}
                  inputMode="numeric"
                  className="flex-1 rounded-2xl border bg-card px-4 py-3.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 transition-shadow"
                />
                <button
                  onClick={handleSendCodeClick}
                  disabled={countdown > 0 || sendingCode}
                  className="flex-shrink-0 rounded-2xl bg-primary px-4 py-3.5 text-sm font-semibold text-primary-foreground disabled:opacity-50 shadow-md shadow-primary/20"
                >
                  {sendingCode ? '发送中...' : countdown > 0 ? `${countdown}s` : '获取验证码'}
                </button>
              </div>
            ) : (
              <>
                <input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="请输入密码"
                  autoComplete="current-password"
                  className="w-full rounded-2xl border bg-card px-4 py-3.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 transition-shadow"
                />
                <div className="text-right">
                  <button
                    onClick={() => navigate(ROUTES.LOGIN + '/reset-password')}
                    className="text-xs text-primary font-medium"
                  >
                    忘记密码？
                  </button>
                </div>
              </>
            )}

            <button
              onClick={handleLogin}
              disabled={loading}
              className="w-full rounded-2xl bg-primary py-3.5 text-sm font-semibold text-primary-foreground disabled:opacity-50 shadow-lg shadow-primary/25 active:scale-[0.98] md:hover:bg-primary/90 transition-transform"
            >
              {loading ? '登录中...' : '登录'}
            </button>
          </div>

          {/* 底部链接 */}
          <div className="mt-6 text-center text-sm text-muted-foreground">
            还没有账号？
            <button
              onClick={() => navigate(ROUTES.REGISTER)}
              className="text-primary font-semibold"
            >
              去注册
            </button>
          </div>
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
