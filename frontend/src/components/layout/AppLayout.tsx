import { Outlet } from 'react-router-dom'
import { TabBar } from './TabBar'
import { useUiStore } from '@/store/ui'

/**
 * 应用主布局 - 包含底部TabBar
 */
export function AppLayout() {
  const tabBarVisible = useUiStore((s) => s.tabBarVisible)

  return (
    <div className="relative min-h-screen bg-background">
      <main className={tabBarVisible ? 'pb-20' : ''}>
        <Outlet />
      </main>
      <TabBar />
    </div>
  )
}

/**
 * 空白布局 - 无TabBar（用于登录、阅读器等全屏页面）
 */
export function BlankLayout() {
  return (
    <div className="min-h-screen bg-background">
      <Outlet />
    </div>
  )
}
