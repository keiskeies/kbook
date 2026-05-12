import { useState } from 'react'
import { useAuthStore } from '@/store/auth'
import { changePassword } from '@/api/auth'
import { toast } from 'sonner'
import { ArrowLeft } from 'lucide-react'
import { useNavigate } from 'react-router-dom'

export default function ChangePasswordPage() {
  const navigate = useNavigate()
  const [oldPassword, setOldPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const { userInfo } = useAuthStore()

  const handleChange = async () => {
    if (!oldPassword.trim()) {
      toast.error('输入当前密码')
      return
    }
    if (newPassword.length < 6 || newPassword.length > 20) {
      toast.error('新密码长度应为6-20位')
      return
    }
    if (newPassword !== confirmPassword) {
      toast.error('两次密码不一致')
      return
    }
    if (oldPassword === newPassword) {
      toast.error('新密码不能与原密码相同')
      return
    }

    try {
      setLoading(true)
      await changePassword(oldPassword, newPassword)
      toast.success('密码已修改，现在可以重新登录')
      useAuthStore.getState().logout()
      navigate('/login')
    } catch (err: any) {
      toast.error(err.message || '修改未完成')
    } finally {
      setLoading(false)
    }
  }

  const hasPassword = userInfo?.emailBound ?? false

  return (
    <div className="flex min-h-screen flex-col px-6 pt-safe-top">
      <header className="flex items-center gap-3 py-3">
        <button onClick={() => navigate(-1)} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="text-lg font-semibold">修改密码</h1>
      </header>

      <div className="mt-4 space-y-4">
        {hasPassword && (
          <input
            type="password"
            value={oldPassword}
            onChange={(e) => setOldPassword(e.target.value)}
            placeholder="原密码"
            autoComplete="current-password"
            className="w-full rounded-xl border bg-background px-4 py-3 text-sm outline-none focus:ring-2 focus:ring-primary"
          />
        )}

        <input
          type="password"
          value={newPassword}
          onChange={(e) => setNewPassword(e.target.value)}
          placeholder={hasPassword ? '新密码（6-20位）' : '设置密码（6-20位）'}
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

        <button
          onClick={handleChange}
          disabled={loading}
          className="w-full rounded-xl bg-primary py-3 text-sm font-medium text-primary-foreground disabled:opacity-50"
        >
          {loading ? '提交中...' : '确认修改'}
        </button>
      </div>
    </div>
  )
}
