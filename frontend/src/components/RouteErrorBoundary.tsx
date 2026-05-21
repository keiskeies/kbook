import { useNavigate, useRouteError } from 'react-router-dom'
import { AlertCircle, RotateCcw, Home } from 'lucide-react'

export function RouteErrorBoundary() {
  const navigate = useNavigate()
  const error = useRouteError()

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-background p-6">
      <div className="flex h-20 w-20 items-center justify-center rounded-2xl bg-destructive/10">
        <AlertCircle className="h-10 w-10 text-destructive" />
      </div>
      <h2 className="mt-6 text-lg font-semibold">页面出错了</h2>
      <p className="mt-2 max-w-xs text-center text-sm text-muted-foreground">
        应用遇到了一个意外错误，请尝试刷新页面或返回首页
      </p>
      {error != null && (
        <details className="mt-4 max-w-sm">
          <summary className="cursor-pointer text-xs text-muted-foreground hover:text-foreground">
            错误详情
          </summary>
          <pre className="mt-2 max-h-32 overflow-auto rounded-lg bg-muted p-3 text-xs text-muted-foreground">
            {error instanceof Error ? error.message : String(error)}
          </pre>
        </details>
      )}
      <div className="mt-6 flex gap-3">
        <button
          onClick={() => window.location.reload()}
          className="flex items-center gap-2 rounded-xl border px-5 py-2.5 text-sm font-medium hover:bg-muted transition-colors"
        >
          <RotateCcw className="h-4 w-4" />
          重试
        </button>
        <button
          onClick={() => navigate('/home')}
          className="flex items-center gap-2 rounded-xl bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground shadow-md shadow-primary/20"
        >
          <Home className="h-4 w-4" />
          回到首页
        </button>
      </div>
    </div>
  )
}
