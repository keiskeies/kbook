import { useNavigate } from 'react-router-dom'
import { BookOpen, ArrowLeft, Home } from 'lucide-react'

export default function NotFoundPage() {
  const navigate = useNavigate()

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-background p-6">
      <div className="flex h-20 w-20 items-center justify-center rounded-2xl bg-primary/10">
        <BookOpen className="h-10 w-10 text-primary/40" />
      </div>
      <h1 className="mt-6 text-6xl font-bold text-primary/20">404</h1>
      <h2 className="mt-2 text-lg font-semibold">页面不存在</h2>
      <p className="mt-2 text-sm text-muted-foreground text-center">
        你访问的页面可能已被移除或暂时不可用
      </p>
      <div className="mt-8 flex gap-3">
        <button
          onClick={() => navigate(-1)}
          className="flex items-center gap-2 rounded-xl border px-5 py-2.5 text-sm font-medium hover:bg-muted transition-colors"
        >
          <ArrowLeft className="h-4 w-4" />
          返回上页
        </button>
        <button
          onClick={() => navigate('/home')}
          className="flex items-center gap-2 rounded-xl bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground shadow-md shadow-primary/20 hover:bg-primary/90 transition-colors"
        >
          <Home className="h-4 w-4" />
          回到首页
        </button>
      </div>
    </div>
  )
}
