import { Component, type ReactNode } from 'react'
import { AlertCircle, RotateCcw, Home } from 'lucide-react'

interface Props {
  children: ReactNode
}

interface State {
  hasError: boolean
  error: Error | null
}

export class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props)
    this.state = { hasError: false, error: null }
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error }
  }

  handleReload = () => {
    this.setState({ hasError: false, error: null })
  }

  handleGoHome = () => {
    window.location.href = '/home'
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex min-h-screen flex-col items-center justify-center bg-background p-6">
          <div className="flex h-20 w-20 items-center justify-center rounded-2xl bg-destructive/10">
            <AlertCircle className="h-10 w-10 text-destructive" />
          </div>
          <h2 className="mt-6 text-lg font-semibold">页面出错了</h2>
          <p className="mt-2 max-w-xs text-center text-sm text-muted-foreground">
            应用遇到了一个意外错误，请尝试刷新页面或返回首页
          </p>
          {this.state.error && (
            <details className="mt-4 max-w-sm">
              <summary className="cursor-pointer text-xs text-muted-foreground hover:text-foreground">
                错误详情
              </summary>
              <pre className="mt-2 max-h-32 overflow-auto rounded-lg bg-muted p-3 text-xs text-muted-foreground">
                {this.state.error.message}
              </pre>
            </details>
          )}
          <div className="mt-6 flex gap-3">
            <button
              onClick={this.handleReload}
              className="flex items-center gap-2 rounded-xl border px-5 py-2.5 text-sm font-medium hover:bg-muted transition-colors"
            >
              <RotateCcw className="h-4 w-4" />
              重试
            </button>
            <button
              onClick={this.handleGoHome}
              className="flex items-center gap-2 rounded-xl bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground shadow-md shadow-primary/20"
            >
              <Home className="h-4 w-4" />
              回到首页
            </button>
          </div>
        </div>
      )
    }

    return this.props.children
  }
}
