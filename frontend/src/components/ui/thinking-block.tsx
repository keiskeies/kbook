import { useState, useEffect, useRef } from 'react'
import { ChevronDown, ChevronRight, Brain } from 'lucide-react'

interface ThinkingBlockProps {
  content: string
  streaming?: boolean
}

/**
 * AI 思考过程展示组件 — 可折叠区块
 * 思考中自动展开，思考结束自动收起
 */
export default function ThinkingBlock({ content, streaming }: ThinkingBlockProps) {
  const [expanded, setExpanded] = useState(streaming ?? false)
  const prevStreaming = useRef(streaming)

  // 思考结束（streaming: true → false）时自动收起
  useEffect(() => {
    if (prevStreaming.current && !streaming) {
      setExpanded(false)
    }
    prevStreaming.current = streaming
  }, [streaming])

  if (!content) return null

  // 截取前30字作为摘要
  const summary = content.length > 30 ? content.slice(0, 30) + '...' : content

  return (
    <div className="mb-2 rounded-lg border border-border/40 bg-muted/40">
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center gap-1.5 px-3 py-1.5 text-xs text-muted-foreground hover:text-foreground transition-colors"
      >
        {expanded ? (
          <ChevronDown className="h-3.5 w-3.5 shrink-0" />
        ) : (
          <ChevronRight className="h-3.5 w-3.5 shrink-0" />
        )}
        <Brain className="h-3.5 w-3.5 shrink-0" />
        {expanded && !streaming && (
          <span className="font-medium">思考过程</span>
        )}
        {!expanded && !streaming && (
          <span className="ml-1 truncate text-muted-foreground/70">{summary}</span>
        )}
        {streaming && (
          <span className="ml-1 inline-flex gap-0.5">
            <span className="h-1 w-1 animate-pulse rounded-full bg-muted-foreground/50" />
            <span className="h-1 w-1 animate-pulse rounded-full bg-muted-foreground/50 [animation-delay:200ms]" />
            <span className="h-1 w-1 animate-pulse rounded-full bg-muted-foreground/50 [animation-delay:400ms]" />
          </span>
        )}
      </button>
      {expanded && (
        <div className="border-t border-border/30 px-3 py-2 text-xs leading-relaxed text-muted-foreground whitespace-pre-wrap">
          {content}
          {streaming && (
            <span className="ml-0.5 inline-block h-2.5 w-0.5 animate-pulse bg-muted-foreground/50" />
          )}
        </div>
      )}
    </div>
  )
}
