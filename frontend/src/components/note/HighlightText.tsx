import { escapeRegExp } from '@/utils/text'

/** 把命中的搜索词标出来，普通文本原样输出 */
export function HighlightText({ text, keyword }: { text: string; keyword?: string }) {
  const trimmed = keyword?.trim()
  if (!trimmed) return <>{text}</>

  const parts = text.split(new RegExp(`(${escapeRegExp(trimmed)})`, 'gi'))
  const lower = trimmed.toLowerCase()
  return (
    <>
      {parts.map((part, i) =>
        part.toLowerCase() === lower ? (
          <mark key={i} className="rounded bg-warning/25 px-0.5 text-inherit">{part}</mark>
        ) : (
          <span key={i}>{part}</span>
        )
      )}
    </>
  )
}
