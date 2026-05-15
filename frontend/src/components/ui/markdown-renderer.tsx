import React from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'
import { useNavigate } from 'react-router-dom'
import InlineBookCard, { type InlineBookCardData } from '@/components/ai/InlineBookCard'

interface MarkdownRendererProps {
  content: string
  className?: string
  /** 后端下发的书名→bookId 映射，用于把《书名》替换为可点击卡片 */
  bookMap?: Record<string, number>
}

/**
 * 解析图书块数据 — 主格式为 《书名》(bid:数字)，兼容旧 [BOOK:id=数字]
 * 支持多种 AI 工具输出：
 *   《书名》(bid:123) 作者:xxx 格式:EPUB 评分:4.5 阅读:100次
 *   《书名》(bid:123) 作者:xxx 阅读:100次 评分:4.5          （排行榜）
 *   《书名》(bid:123) 作者:xxx 格式:EPUB 评分:4.5 匹配度:85%（推荐）
 *   《书名》(bid:123)                                         （最简）
 */
function parseBookData(blockText: string): InlineBookCardData | null {
  // 提取 bookId — 主格式 (bid:123)，备选 [BOOK:id=123]
  let idMatch = blockText.match(/\(bid:(\d+)\)/)
  if (!idMatch) {
    idMatch = blockText.match(/\[BOOK:id=(\d+)\]/)
  }
  if (!idMatch) return null

  const bookId = parseInt(idMatch[1], 10)

  // 提取书名
  const titleMatch = blockText.match(/《(.+?)》/)
  const title = titleMatch ? titleMatch[1] : '未知'

  // 提取作者
  const authorMatch = blockText.match(/作者[：:]\s*(.+?)(?:\s+格式|\s+评分|\s+阅读|\s+匹配度|\s+推荐原因|\s*$)/)
  const author = authorMatch ? authorMatch[1].trim() : null

  // 提取格式
  const formatMatch = blockText.match(/格式[：:]\s*(\S+)/)
  const format = formatMatch ? formatMatch[1] : ''

  // 提取评分
  const ratingMatch = blockText.match(/评分[：:]\s*([\d.]+)/)
  const rating = ratingMatch ? parseFloat(ratingMatch[1]) : 0

  // 提取阅读次数
  const readCountMatch = blockText.match(/阅读[：:]\s*(\d+)次/)
  const readCount = readCountMatch ? parseInt(readCountMatch[1], 10) : 0

  // 提取推荐理由 / 简介 / 匹配度
  const reasonMatch = blockText.match(/推荐原因[：:]\s*(.+?)(?:\s*$)/)
  const descMatch = blockText.match(/简介[：:]\s*(.+?)(?:\s*$)/)
  const matchReason = blockText.match(/匹配度[：:]\s*([\d.]+)%/)
  const aiReason = blockText.match(/👉\s*(.+?)(?:\s*$)/)
  const description = aiReason ? aiReason[1].trim()
    : reasonMatch ? reasonMatch[1].trim()
    : descMatch ? descMatch[1].trim()
    : matchReason ? `匹配度 ${matchReason[1]}%`
    : null

  return { bookId, title, author, format, rating, readCount, description }
}

/**
 * 用 bookMap 映射把内容中的《书名》替换为 [BOOK:id=X]《书名》
 * 仅替换 bookMap 中存在的书名，不碰未映射的《书名》
 */
function injectBookIds(content: string, bookMap: Record<string, number>): string {
  // 构建书名→bookId 的查找表（按书名长度降序，优先匹配长书名）
  const entries = Object.entries(bookMap)
    .filter(([title]) => title.length > 0)
    .sort((a, b) => b[0].length - a[0].length)

  if (entries.length === 0) return content

  // 对每个书名，在内容中查找《书名》并注入 ID
  let result = content
  for (const [title, bookId] of entries) {
    // 转义书名中的正则特殊字符
    const escapedTitle = title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    // 匹配《书名》，但排除已经带 [BOOK:id= 或 (bid: 的
    const regex = new RegExp(
      `(?<!\\[BOOK:id=\\d+\\])(?<!\\(bid:\\d+\\))《${escapedTitle}》`,
      'g'
    )
    result = result.replace(regex, `[BOOK:id=${bookId}]《${title}》`)
  }
  return result
}

/**
 * 逐行解析内容，将 [BOOK:id=...] 行识别为图书卡片，其余为文本。
 * 相比正则方案，此方法不会出现最后一本书吞掉后续文本的 bug。
 */
function segmentContent(content: string): Array<
  | { type: 'text'; content: string }
  | { type: 'book'; content: string; book: InlineBookCardData }
> {
  const segments: Array<
    | { type: 'text'; content: string }
    | { type: 'book'; content: string; book: InlineBookCardData }
  > = []

  const lines = content.split('\n')
  let textBuffer: string[] = []
  let i = 0

  while (i < lines.length) {
    const line = lines[i]

    // 检测图书行：《书名》(bid:数字) 或 [BOOK:id=数字]
    if (/《.+?》\(bid:\d+\)/.test(line) || /\[BOOK:id=\d+\]/.test(line)) {
      // 先输出积攒的文本
      flushText()

      // 收集图书块：当前行 + 可选续行（👉推荐理由 / 缩进"简介："）
      let bookBlock = line
      i++
      while (i < lines.length) {
        const nextLine = lines[i]
        // 👉 推荐理由续行（AI 系统提示词要求格式）
        if (/^\s*👉\s/.test(nextLine)) {
          bookBlock += '\n' + nextLine
          i++
          break
        }
        // 缩进的简介续行（personalizeRecommend 格式）
        if (/^\s{2,}简介[：:]/.test(nextLine)) {
          bookBlock += '\n' + nextLine
          i++
        } else if (/《.+?》\(bid:\d+\)/.test(nextLine) || /\[BOOK:id=\d+\]/.test(nextLine)) {
          break
        } else if (nextLine.trim() === '') {
          break
        } else {
          break
        }
      }

      const book = parseBookData(bookBlock)
      if (book) {
        segments.push({ type: 'book', content: bookBlock, book })
      } else {
        textBuffer.push(bookBlock)
      }
    } else {
      textBuffer.push(line)
      i++
    }
  }

  // 输出剩余文本
  flushText()

  // 无 BOOK 标记时整段为文本
  if (segments.length === 0 && content.trim()) {
    segments.push({ type: 'text', content: content.trim() })
  }

  return segments

  function flushText() {
    const text = textBuffer.join('\n').trim()
    if (text) {
      segments.push({ type: 'text', content: text })
    }
    textBuffer = []
  }
}

/**
 * AI 消息 Markdown 渲染组件
 * 支持：标题、列表、代码块、粗体斜体、引用、表格、书名号高亮、内嵌图书卡片
 * 兜底：当 [BOOK:id=] 缺失时，《书名》自动变成搜索链接
 */
export default function MarkdownRenderer({ content, className = '', bookMap }: MarkdownRendererProps) {
  const navigate = useNavigate()

  // 用后端下发的 bookMap 把《书名》替换为可点击的 [BOOK:id=X]《书名》
  const enrichedContent = bookMap && Object.keys(bookMap).length > 0
    ? injectBookIds(content, bookMap)
    : content

  const handleClick = (e: React.MouseEvent) => {
    const target = e.target as HTMLElement
    const anchor = target.closest('a')
    if (anchor) {
      const href = anchor.getAttribute('href')
      if (href?.startsWith('kbook://book-detail/')) {
        e.preventDefault()
        const bookId = href.replace('kbook://book-detail/', '')
        navigate(`/book/${bookId}`)
      } else if (href?.startsWith('kbook://search/')) {
        e.preventDefault()
        const query = href.replace('kbook://search/', '')
        navigate(`/search?keyword=${encodeURIComponent(query)}`)
      }
    }
  }

  const segments = segmentContent(enrichedContent)

  // 如果没有分段或只有文本，直接渲染 markdown（带《书名》搜索链接兜底）
  if (segments.length === 1 && segments[0].type === 'text') {
    return (
      <div className={`markdown-body ${className}`} onClick={handleClick}>
        <ReactMarkdown
          remarkPlugins={[remarkGfm]}
          rehypePlugins={[rehypeHighlight]}
          components={markdownComponents}
        >
          {segments[0].content}
        </ReactMarkdown>
      </div>
    )
  }

  // 混合渲染：文本段 + 图书卡片交替
  return (
    <div className={`markdown-body ${className}`} onClick={handleClick}>
      {segments.map((seg, i) => {
        if (seg.type === 'book' && seg.book) {
          return <InlineBookCard key={`book-${seg.book.bookId}-${i}`} book={seg.book} />
        }
        return (
          <ReactMarkdown
            key={`text-${i}`}
            remarkPlugins={[remarkGfm]}
            rehypePlugins={[rehypeHighlight]}
            components={markdownComponents}
          >
            {seg.content}
          </ReactMarkdown>
        )
      })}
    </div>
  )
}

// ==================== Markdown 公共组件配置 ====================

const markdownComponents = {
  // 书名号高亮
  p: ({ children, ...props }: any) => (
    <p {...props}>
      {highlightBookTitle(children)}
    </p>
  ),
  // 行内代码
  code: ({ className: codeClassName, children, ...props }: any) => {
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
  pre: ({ children, ...props }: any) => (
    <pre className="rounded-lg bg-muted/80 p-3 overflow-x-auto text-xs my-2" {...props}>
      {children}
    </pre>
  ),
  // 列表样式
  ul: ({ children, ...props }: any) => (
    <ul className="list-disc pl-5 my-1 space-y-0.5" {...props}>
      {children}
    </ul>
  ),
  ol: ({ children, ...props }: any) => (
    <ol className="list-decimal pl-5 my-1 space-y-0.5" {...props}>
      {children}
    </ol>
  ),
  // 引用
  blockquote: ({ children, ...props }: any) => (
    <blockquote className="border-l-3 border-primary/40 pl-3 my-2 text-muted-foreground" {...props}>
      {children}
    </blockquote>
  ),
  // 标题
  h1: ({ children, ...props }: any) => (
    <h1 className="text-base font-bold mt-3 mb-1" {...props}>{children}</h1>
  ),
  h2: ({ children, ...props }: any) => (
    <h2 className="text-sm font-bold mt-2.5 mb-1" {...props}>{children}</h2>
  ),
  h3: ({ children, ...props }: any) => (
    <h3 className="text-sm font-semibold mt-2 mb-0.5" {...props}>{children}</h3>
  ),
  // 链接（支持 kbook:// 内部协议）
  a: ({ href, children, ...props }: any) => {
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
  table: ({ children, ...props }: any) => (
    <div className="overflow-x-auto my-2">
      <table className="text-xs border-collapse border border-border/50" {...props}>
        {children}
      </table>
    </div>
  ),
  th: ({ children, ...props }: any) => (
    <th className="border border-border/50 px-2 py-1 bg-muted font-medium" {...props}>
      {children}
    </th>
  ),
  td: ({ children, ...props }: any) => (
    <td className="border border-border/50 px-2 py-1" {...props}>
      {children}
    </td>
  ),
  // 分隔线
  hr: ({ ...props }: any) => (
    <hr className="my-3 border-border/50" {...props} />
  ),
}

// ==================== 辅助函数 ====================

/** 递归处理 React children，将《书名号》内容高亮并变为可点击的搜索链接 */
function highlightBookTitle(children: React.ReactNode): React.ReactNode {
  if (typeof children === 'string') {
    const parts = children.split(/(《[^》]+》)/g)
    if (parts.length <= 1) return children
    return parts.map((part, i) => {
      if (part.startsWith('《') && part.endsWith('》')) {
        const title = part.slice(1, -1)
        return (
          <a
            key={i}
            href={`kbook://search/${encodeURIComponent(title)}`}
            className="text-primary font-medium hover:underline cursor-pointer"
          >
            {part}
          </a>
        )
      }
      return part
    })
  }
  if (Array.isArray(children)) {
    return children.map((child, i) => (
      <React.Fragment key={i}>{highlightBookTitle(child)}</React.Fragment>
    ))
  }
  return children
}
