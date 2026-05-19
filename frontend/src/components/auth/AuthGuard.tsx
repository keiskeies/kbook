import { Navigate, useLocation } from 'react-router-dom'
import { useAuthStore } from '@/store/auth'
import { ROUTES, USER_STATUS } from '@/constants'
import { Button } from '@/components/ui/button'
import { LogOut, Clock, ShieldOff } from 'lucide-react'

interface AuthGuardProps {
  children: React.ReactNode
}

/**
 * 路由守卫 - 检查认证状态、审核状态、管理员邮箱绑定
 * - 未登录 → 跳转登录页
 * - 管理员未绑定邮箱 → 强制跳转绑定邮箱页
 * - 待审核 → 显示审核提示页
 * - 已封禁 → 显示封禁提示页
 * - 已通过 → 正常访问
 */
export function AuthGuard({ children }: AuthGuardProps) {
  const { isAuthenticated, userInfo, logout } = useAuthStore()
  const location = useLocation()

  if (!isAuthenticated) {
    return <Navigate to={ROUTES.LOGIN} state={{ from: location }} replace />
  }

  // 管理员强制绑定邮箱（绑定邮箱页本身不拦截）
  if (userInfo?.role === 'ADMIN' && !userInfo.emailBound && location.pathname !== ROUTES.ADMIN_BIND_EMAIL) {
    return <Navigate to={ROUTES.ADMIN_BIND_EMAIL} replace />
  }

  if (userInfo?.status === USER_STATUS.PENDING) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center bg-background p-6">
        <div className="mb-6 flex h-20 w-20 items-center justify-center rounded-full bg-primary/10">
          <Clock className="h-10 w-10 text-primary" />
        </div>
        <h2 className="mb-2 text-xl font-semibold">账号审核中</h2>
        <p className="mb-6 text-center text-sm text-muted-foreground">
          您的账号正在审核中，请耐心等待管理员通过。<br />
          审核通过后即可正常使用。
        </p>
        <Button variant="outline" size="sm" onClick={logout}>
          <LogOut className="mr-2 h-4 w-4" />
          退出登录
        </Button>
      </div>
    )
  }

  if (userInfo?.status === USER_STATUS.BANNED) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center bg-background p-6">
        <div className="mb-6 flex h-20 w-20 items-center justify-center rounded-full bg-destructive/10">
          <ShieldOff className="h-10 w-10 text-destructive" />
        </div>
        <h2 className="mb-2 text-xl font-semibold">账号已被封禁</h2>
        <p className="mb-6 text-center text-sm text-muted-foreground">
          您的账号已被管理员封禁，如有疑问请联系管理员。
        </p>
        <Button variant="outline" size="sm" onClick={logout}>
          <LogOut className="mr-2 h-4 w-4" />
          退出登录
        </Button>
      </div>
    )
  }

  return <>{children}</>
}

/**
 * 管理员路由守卫 - 仅管理员可访问
 */
export function AdminGuard({ children }: AuthGuardProps) {
  const { userInfo } = useAuthStore()

  if (userInfo?.role !== 'ADMIN') {
    return <Navigate to={ROUTES.HOME} replace />
  }

  return <>{children}</>
}

/**
 * 游客路由守卫 - 已登录用户访问登录/注册页时重定向
 */
export function GuestGuard({ children }: AuthGuardProps) {
  const { isAuthenticated, userInfo } = useAuthStore()

  if (isAuthenticated && userInfo?.status === USER_STATUS.APPROVED) {
    return <Navigate to={ROUTES.HOME} replace />
  }

  return <>{children}</>
}
