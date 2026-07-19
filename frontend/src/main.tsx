import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import { ThemeProvider } from 'next-themes'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Toaster } from '@/components/ui/sonner'
import { ErrorBoundary } from '@/components/ErrorBoundary'
import { useAuthStore } from '@/store/auth'
import { initTokenSyncListener } from '@/utils/token-sync'
import { useTokenRefresh } from '@/hooks/useTokenRefresh'
import { refreshAccessToken, clearAuthAndRedirect } from '@/utils/token-refresh'
import { router } from '@/router'
import './index.css'
import './App.css'

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.getRegistrations().then(registrations => {
    for (const registration of registrations) {
      registration.unregister()
    }
  })
}

// TanStack Query 客户端配置
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 1000 * 60 * 5, // 5 分钟
      gcTime: 1000 * 60 * 30, // 30 分钟
    },
  },
})

// 启动时恢复认证状态（try-catch 兜底，部分移动端浏览器隐私模式下 localStorage 会抛异常）
try {
  useAuthStore.getState().hydrate()
} catch { /* ignore */ }

// 初始化多标签页 token 同步监听（try-catch 兜底，部分移动端 BroadcastChannel 会抛异常）
try {
  initTokenSyncListener(
    (token, refreshToken) => {
      try {
        localStorage.setItem('kbook_token', token)
        localStorage.setItem('kbook_refresh_token', refreshToken)
      } catch { /* ignore */ }
      useAuthStore.getState().hydrate()
    },
    () => {
      try {
        localStorage.removeItem('kbook_token')
        localStorage.removeItem('kbook_refresh_token')
        localStorage.removeItem('kbook_user_info')
      } catch { /* ignore */ }
      useAuthStore.setState({ token: null, userInfo: null, isAuthenticated: false })
    },
  )
} catch { /* ignore */ }

/**
 * 启动时预校验 access token：
 * - 未过期 → 直接渲染
 * - 已过期/即将过期 → 同步等待 refresh 完成（避免渲染后又被 401 踢回登录页的"假登录态"闪现）
 * - refresh 失败 → 直接跳登录页，不渲染主应用
 *
 * 用 async IIFE 而非顶层 await，避免 Vite build target 兼容性问题。
 */
async function bootstrapAuth(): Promise<boolean> {
  const token = useAuthStore.getState().token
  if (!token) return true // 未登录，让路由守卫处理

  try {
    const parts = token.split('.')
    if (parts.length !== 3) return true
    const payload = JSON.parse(atob(parts[1]))
    if (!payload.exp) return true
    const expiresAt = payload.exp * 1000
    // 留 2 秒缓冲，未过期直接渲染
    if (Date.now() < expiresAt - 2000) return true

    // 已过期或即将过期 → 同步刷新
    const newToken = await refreshAccessToken()
    if (!newToken) {
      clearAuthAndRedirect()
      return false
    }
    // 刷新成功，重新 hydrate 加载新 token + userInfo
    useAuthStore.getState().hydrate()
    return true
  } catch {
    // 解析异常不阻断渲染，让后续 useTokenRefresh 兜底
    return true
  }
}

function App() {
  useTokenRefresh()
  return <RouterProvider router={router} />
}

;(async () => {
  const ok = await bootstrapAuth()
  if (!ok) return // clearAuthAndRedirect 已跳转，不再渲染

  createRoot(document.getElementById('root')!).render(
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider attribute="class" defaultTheme="system" enableSystem disableTransitionOnChange>
          <App />
          <Toaster position="top-center" richColors closeButton duration={4000} style={{ marginTop: '60px' }} />
        </ThemeProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  )
})()
