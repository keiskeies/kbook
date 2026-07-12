import { useState } from 'react'
import { LazyImage } from '@/components/ui/lazy-image'

/** 预定义渐变色，基于书名 hash 选取 */
const GRADIENTS = [
  'from-rose-400 to-pink-500',
  'from-violet-500 to-purple-600',
  'from-blue-400 to-indigo-500',
  'from-cyan-400 to-teal-500',
  'from-emerald-400 to-green-500',
  'from-amber-400 to-orange-500',
  'from-fuchsia-400 to-pink-500',
  'from-sky-400 to-blue-500',
  'from-lime-400 to-emerald-500',
  'from-red-400 to-rose-500',
  'from-indigo-400 to-violet-500',
  'from-teal-400 to-cyan-500',
]

/** 简单字符串 hash */
function hashStr(s: string): number {
  let h = 0
  for (let i = 0; i < s.length; i++) {
    h = ((h << 5) - h + s.charCodeAt(i)) | 0
  }
  return Math.abs(h)
}

/** 根据 title 获取渐变 class */
function getGradient(title: string): string {
  return GRADIENTS[hashStr(title) % GRADIENTS.length]
}

export interface BookCoverProps {
  coverUrl: string | null
  title: string
  author?: string | null
  format?: string
  /** 预设尺寸: xs(h-9 w-7) sm(h-16 w-12) md(h-20 w-14) lg(h-36 aspect-3/4) xl(h-44 w-32) */
  size?: 'xs' | 'sm' | 'md' | 'lg' | 'xl'
  className?: string
  showFormatTag?: boolean
}

export default function BookCover({
  coverUrl,
  title,
  author,
  format,
  size = 'lg',
  className = '',
  showFormatTag = true,
}: BookCoverProps) {
  const [imgError, setImgError] = useState(false)

  const hasCover = coverUrl && !imgError
  const gradient = getGradient(title || 'book')

  // 尺寸映射
  const sizeMap: Record<string, string> = {
    xs: 'h-9 w-7 rounded',
    sm: 'h-16 w-12 rounded-lg',
    md: 'h-20 w-14 rounded',
    lg: 'aspect-[3/4] w-full rounded-xl',
    xl: 'h-44 w-32 rounded-2xl',
  }

  // 格式标签字号映射
  const tagSizeMap: Record<string, string> = {
    xs: 'text-xs px-0.5 py-px',
    sm: 'text-xs px-1 py-0.5',
    md: 'text-xs px-1 py-0.5',
    lg: 'text-xs px-1.5 py-0.5',
    xl: 'text-xs px-1.5 py-0.5',
  }

  // 书名字号映射
  const titleSizeMap: Record<string, string> = {
    xs: 'text-xs leading-tight',
    sm: 'text-xs leading-tight',
    md: 'text-xs leading-tight',
    lg: 'text-xs leading-snug',
    xl: 'text-sm leading-snug',
  }

  // 作者字号映射
  const authorSizeMap: Record<string, string> = {
    xs: '',
    sm: 'text-xs',
    md: 'text-xs',
    lg: 'text-xs',
    xl: 'text-xs',
  }

  const showFormat = showFormatTag && format && (format === 'PDF' || format === 'TXT')
  const showAuthor = author && size !== 'xs'

  return (
    <div
      className={`relative overflow-hidden bg-muted shadow-md ${sizeMap[size]} ${className}`}
    >
      {hasCover ? (
        <LazyImage
          src={coverUrl ?? ''}
          alt={title}
          className="h-full w-full"
          onError={() => setImgError(true)}
        />
      ) : (
        <div
          className={`flex h-full w-full flex-col items-center justify-center bg-gradient-to-br ${gradient} p-2`}
        >
          <span
            className={`font-bold text-white/90 text-center line-clamp-3 ${titleSizeMap[size]}`}
          >
            {title || '未知'}
          </span>
          {showAuthor && (
            <span
              className={`mt-1 text-white/50 text-center truncate max-w-full ${authorSizeMap[size]}`}
            >
              {author}
            </span>
          )}
        </div>
      )}

      {/* 格式标签 */}
      {showFormat && (
        <span
          className={`absolute right-1 top-1 rounded-md bg-black/50 font-medium text-white backdrop-blur-sm ${tagSizeMap[size]}`}
        >
          {format}
        </span>
      )}
    </div>
  )
}
