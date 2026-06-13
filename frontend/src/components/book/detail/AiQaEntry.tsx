import { useEffect, useState } from 'react'
import { MessageCircle, Sparkles } from 'lucide-react'
import { getBookSuggestedQuestions } from '@/api/bookChat'

interface AiQaEntryProps {
  bookId: number
  onOpenChat: (initialQuestion?: string) => void
  /** PC 端始终展开，手机端可收起 */
  isMobile?: boolean
}

export function AiQaEntry({ bookId, onOpenChat, isMobile }: AiQaEntryProps) {
  const [questions, setQuestions] = useState<string[]>([])
  const [loading, setLoading] = useState(false)
  const [showAll, setShowAll] = useState(false)

  useEffect(() => {
    if (!bookId) return
    setLoading(true)
    getBookSuggestedQuestions(bookId)
      .then((res: unknown) => {
        const data = (res as { data?: string[] })?.data ?? res as string[]
        if (Array.isArray(data)) setQuestions(data)
      })
      .catch(() => { /* ignore */ })
      .finally(() => setLoading(false))
  }, [bookId])

  // 分类问题：第一个通常是"这本书主要讲了什么"，后面是深度问题
  const overviewQuestions = questions.filter(q => q.includes('主要讲') || q.includes('讲了什么'))
  const deepQuestions = questions.filter(q => !(q.includes('主要讲') || q.includes('讲了什么')))

  const hasMore = questions.length > 6

  return (
    <div className="mb-4 rounded-2xl border border-border/50 bg-gradient-to-br from-primary/5 to-primary/[0.02] p-4">
      <div className="flex items-center gap-2 mb-3">
        <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-primary/10">
          <MessageCircle className="h-4 w-4 text-primary" />
        </div>
        <h3 className="text-sm font-bold">AI 深度问答</h3>
      </div>
      <p className="text-xs text-muted-foreground mb-3">
        基于全书内容，AI 可以为你解答任何关于这本书的问题
      </p>

      {loading ? (
        <div className="flex flex-wrap gap-2">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-8 w-28 animate-pulse rounded-full bg-muted" />
          ))}
        </div>
      ) : questions.length > 0 ? (
        <div className="space-y-2">
          {/* 概述类问题 — 突出显示 */}
          {overviewQuestions.length > 0 && (
            <div className="flex flex-col gap-2">
              {overviewQuestions.slice(0, 1).map((q, idx) => (
                <button
                  key={`overview-${idx}`}
                  onClick={() => onOpenChat(q)}
                  className="rounded-2xl border border-primary/30 bg-primary/10 px-3 py-1.5 text-xs font-semibold text-primary hover:bg-primary/15 hover:shadow-sm transition-all duration-200 text-left"
                  style={{ whiteSpace: 'normal', wordWrap: 'break-word', overflowWrap: 'break-word' }}
                >
                  {q}
                </button>
              ))}
            </div>
          )}
          {/* 深度问题 */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
            {(isMobile && !showAll ? deepQuestions.slice(0, 5) : deepQuestions).map((q, idx) => (
              <button
                key={idx}
                onClick={() => onOpenChat(q)}
                className="rounded-xl border border-primary/20 bg-white/50 dark:bg-white/5 px-3 py-1.5 text-xs font-medium text-primary hover:bg-primary/10 hover:shadow-sm transition-all duration-200 hover:-translate-y-0.5 text-left"
                style={{ whiteSpace: 'normal', wordWrap: 'break-word', overflowWrap: 'break-word' }}
              >
                {q}
              </button>
            ))}
          </div>
          {isMobile && hasMore && !showAll && (
            <button
              onClick={() => setShowAll(true)}
              className="text-xs text-muted-foreground hover:text-primary transition-colors mt-1"
            >
              展开更多问题 ({questions.length - 6}+)
            </button>
          )}
        </div>
      ) : (
        <button
          onClick={() => onOpenChat()}
          className="flex w-full items-center justify-center gap-2 rounded-xl bg-primary/10 py-2.5 text-sm font-medium text-primary hover:bg-primary/15 transition-colors"
        >
          <Sparkles className="h-4 w-4" />
          向 AI 提问
        </button>
      )}
    </div>
  )
}
