import { Outlet } from 'react-router-dom'
import { TabBar } from './TabBar'

/**
 * 应用主布局 - 包含底部TabBar
 */
export function AppLayout() {
  return (
    <div className="relative min-h-screen bg-background">
      <main className="pb-20">
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
