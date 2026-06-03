import { useLocation, useNavigate } from 'react-router-dom'
import { Home, Trophy, BookOpen, User, BookOpenCheck } from 'lucide-react'
import { ROUTES } from '@/constants'
import { cn } from '@/lib/utils'
import { BlinkingBot } from './TabBar'
import { useAuthStore } from '@/store/auth'

const navItems = [
  { path: ROUTES.HOME, label: '首页', icon: Home },
  { path: ROUTES.RANK, label: '发现', icon: Trophy },
  { path: ROUTES.AI, label: 'AI 助手', icon: BlinkingBot },
  { path: ROUTES.BOOKSHELF, label: '书架', icon: BookOpen },
  { path: ROUTES.PROFILE, label: '我的', icon: User },
]

export function DesktopSidebar() {
  const location = useLocation()
  const navigate = useNavigate()
  const userInfo = useAuthStore((s) => s.userInfo)

  const isActive = (path: string) => {
    if (path === ROUTES.HOME) return location.pathname === '/' || location.pathname === ROUTES.HOME
    return location.pathname.startsWith(path)
  }

  return (
    <aside className="hidden md:flex md:w-60 lg:w-64 md:flex-col md:border-r md:border-border/50 bg-sidebar text-sidebar-foreground h-full shrink-0">
      {/* Logo */}
      <div className="flex items-center gap-2.5 px-5 py-5 border-b border-sidebar-border">
        <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-primary shadow-sm">
          <BookOpenCheck className="h-5 w-5 text-primary-foreground" strokeWidth={2.5} />
        </div>
        <div>
          <h1 className="text-lg font-bold bg-gradient-to-r from-primary to-primary/70 bg-clip-text text-transparent">KBook</h1>
          <p className="text-[10px] text-sidebar-foreground/50 -mt-0.5">智能阅读平台</p>
        </div>
      </div>

      {/* Navigation */}
      <nav className="flex-1 px-3 py-4 space-y-1">
        {navItems.map((item) => {
          const active = isActive(item.path)
          const Icon = item.icon
          return (
            <button
              key={item.path}
              onClick={() => navigate(item.path)}
              className={cn(
                'flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-all duration-200',
                active
                  ? 'bg-sidebar-primary/10 text-sidebar-primary'
                  : 'text-sidebar-foreground/60 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'
              )}
            >
              <div className={cn(
                'flex h-8 w-8 items-center justify-center rounded-lg transition-all duration-200',
                active
                  ? 'bg-sidebar-primary text-sidebar-primary-foreground shadow-sm'
                  : 'bg-transparent'
              )}>
                <Icon className="h-[18px] w-[18px]" strokeWidth={active ? 2.2 : 1.8} />
              </div>
              <span>{item.label}</span>
              {active && (
                <div className="ml-auto h-1.5 w-1.5 rounded-full bg-sidebar-primary" />
              )}
            </button>
          )
        })}
      </nav>

      {/* User info at bottom */}
      <div className="border-t border-sidebar-border px-4 py-4">
        <div className="flex items-center gap-3">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-sidebar-primary/10 text-sidebar-primary font-bold text-sm">
            {userInfo?.nickname?.[0] || 'U'}
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-sm font-medium truncate">{userInfo?.nickname || '未登录'}</p>
            <p className="text-[10px] text-sidebar-foreground/40 truncate">{userInfo?.email || ''}</p>
          </div>
        </div>
      </div>
    </aside>
  )
}
