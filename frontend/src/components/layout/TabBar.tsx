import { useLocation, useNavigate } from 'react-router-dom'
import { Home, Compass, User } from 'lucide-react'
import { ROUTES } from '@/constants'
import { cn } from '@/utils/cn'
import { useUiStore } from '@/store/ui'

const tabs = [
  { path: ROUTES.HOME, label: '首页', icon: Home },
  { path: ROUTES.DISCOVER, label: '发现', icon: Compass },
  { path: ROUTES.PROFILE, label: '我的', icon: User },
]

export function TabBar() {
  const location = useLocation()
  const navigate = useNavigate()
  const tabBarVisible = useUiStore((s) => s.tabBarVisible)

  const isActive = (path: string) => location.pathname.startsWith(path)

  if (!tabBarVisible) return null

  return (
    <nav className="md:hidden fixed bottom-0 left-0 right-0 z-50 border-t bg-background/80 backdrop-blur-xl safe-area-bottom">
      <div className="mx-auto flex h-16 max-w-lg items-center justify-around px-2">
        {tabs.map((tab) => {
          const active = isActive(tab.path)
          const Icon = tab.icon

          return (
            <button
              key={tab.path}
              onClick={() => navigate(tab.path)}
              className="flex flex-col items-center gap-0.5 py-1 min-w-[48px]"
            >
              <div className={cn(
                'flex h-8 w-8 items-center justify-center rounded-lg transition-all duration-200',
                active ? 'bg-primary/10' : ''
              )}>
                <Icon
                  className={cn(
                    'h-[18px] w-[18px] transition-all duration-200',
                    active ? 'text-primary' : 'text-muted-foreground'
                  )}
                  strokeWidth={active ? 2.5 : 1.8}
                />
              </div>
              <span
                  className={cn(
                    'whitespace-nowrap text-xs transition-colors duration-200',
                    active ? 'font-semibold text-primary' : 'text-muted-foreground'
                  )}
                >
                  {tab.label}
                </span>
            </button>
          )
        })}
      </div>
    </nav>
  )
}
