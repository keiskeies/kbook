import React from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'
import { useNavigate } from 'react-router-dom'

interface MarkdownRendererProps {
  content: string
  className?: string
}

/** 书籍链接预处理：将 [BOOK:id=123]《书名》 转为 [📖《书名》](/reader/123) */
function preprocessBookLinks(text: string): string {
  return text.replace(
    /\[BOOK:id=(\d+)\]《(.+?)》/g,
    (_match, bookId, title) => `[📖《${title}》](kbook://book/${bookId})`
  )
}

/**
 * AI 消息 Markdown 渲染组件
 * 支持：标题、列表、代码块、粗体斜体、引用、表格、书名号高亮、图书链接
 */
export default function MarkdownRenderer({ content, className = '' }: MarkdownRendererProps) {
  const navigate = useNavigate()

  const handleClick = (e: React.MouseEvent) => {
    const target = e.target as HTMLElement
    const anchor = target.closest('a')
    if (anchor) {
      const href = anchor.getAttribute('href')
      if (href?.startsWith('kbook://book/')) {
        e.preventDefault()
        const bookId = href.replace('kbook://book/', '')
        navigate(`/reader/${bookId}`)
      }
    }
  }

  const processed = preprocessBookLinks(content)

  return (
    <div className={`markdown-body ${className}`} onClick={handleClick}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[rehypeHighlight]}
        components={{
          // 书名号高亮
          p: ({ children, ...props }) => (
            <p {...props}>
              {highlightBookTitle(children)}
            </p>
          ),
          // 行内代码
          code: ({ className: codeClassName, children, ...props }) => {
            const isInline = !codeClassName
            if (isInline) {
              return (
                <code className="rounded bg-muted px-1.5 py-0.5 text-xs font-mono" {...props}>
                  {children}
                </code>
              )
            }
            return (
              <code className={codeClassName} {...props}>
                {children}
              </code>
            )
          },
          // 代码块容器
          pre: ({ children, ...props }) => (
            <pre className="rounded-lg bg-muted/80 p-3 overflow-x-auto text-xs my-2" {...props}>
              {children}
            </pre>
          ),
          // 列表样式
          ul: ({ children, ...props }) => (
            <ul className="list-disc pl-5 my-1 space-y-0.5" {...props}>
              {children}
            </ul>
          ),
          ol: ({ children, ...props }) => (
            <ol className="list-decimal pl-5 my-1 space-y-0.5" {...props}>
              {children}
            </ol>
          ),
          // 引用
          blockquote: ({ children, ...props }) => (
            <blockquote className="border-l-3 border-primary/40 pl-3 my-2 text-muted-foreground" {...props}>
              {children}
            </blockquote>
          ),
          // 标题
          h1: ({ children, ...props }) => (
            <h1 className="text-base font-bold mt-3 mb-1" {...props}>{children}</h1>
          ),
          h2: ({ children, ...props }) => (
            <h2 className="text-sm font-bold mt-2.5 mb-1" {...props}>{children}</h2>
          ),
          h3: ({ children, ...props }) => (
            <h3 className="text-sm font-semibold mt-2 mb-0.5" {...props}>{children}</h3>
          ),
          // 链接（支持 kbook:// 内部协议）
          a: ({ href, children, ...props }) => {
            if (href?.startsWith('kbook://')) {
              return (
                <a
                  href={href}
                  className="inline-flex items-center gap-1 rounded-md bg-primary/10 px-2 py-0.5 text-primary font-medium hover:underline cursor-pointer"
                  {...props}
                >
                  {children}
                </a>
              )
            }
            return (
              <a href={href} target="_blank" rel="noopener noreferrer" className="text-primary hover:underline" {...props}>
                {children}
              </a>
            )
          },
          // 表格
          table: ({ children, ...props }) => (
            <div className="overflow-x-auto my-2">
              <table className="text-xs border-collapse border border-border/50" {...props}>
                {children}
              </table>
            </div>
          ),
          th: ({ children, ...props }) => (
            <th className="border border-border/50 px-2 py-1 bg-muted font-medium" {...props}>
              {children}
            </th>
          ),
          td: ({ children, ...props }) => (
            <td className="border border-border/50 px-2 py-1" {...props}>
              {children}
            </td>
          ),
          // 分隔线
          hr: ({ ...props }) => (
            <hr className="my-3 border-border/50" {...props} />
          ),
        }}
      >
        {processed}
      </ReactMarkdown>
    </div>
  )
}

/** 递归处理 React children，将《书名号》内容高亮 */
function highlightBookTitle(children: React.ReactNode): React.ReactNode {
  if (typeof children === 'string') {
    const parts = children.split(/(《[^》]+》)/g)
    if (parts.length <= 1) return children
    return parts.map((part, i) =>
      part.startsWith('《') && part.endsWith('》')
        ? <span key={i} className="text-primary font-medium">{part}</span>
        : part
    )
  }
  if (Array.isArray(children)) {
    return children.map((child, i) => (
      <React.Fragment key={i}>{highlightBookTitle(child)}</React.Fragment>
    ))
  }
  return children
}
