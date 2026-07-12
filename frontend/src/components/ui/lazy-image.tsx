import { useInView } from 'react-intersection-observer'
import { useState } from 'react'
import { cn } from '@/lib/utils'

interface LazyImageProps {
  src: string
  alt: string
  className?: string
  onLoad?: () => void
  onError?: () => void
}

/**
 * 基于 IntersectionObserver 的懒加载图片组件。
 * - rootMargin=200px 提前加载，比 native loading="lazy" 响应更快
 * - 加载中显示脉冲占位块
 * - 加载完成后 300ms 淡入
 */
export function LazyImage({ src, alt, className, onLoad, onError }: LazyImageProps) {
  const [loaded, setLoaded] = useState(false)
  const { ref, inView } = useInView({
    triggerOnce: true,
    rootMargin: '200px',
  })

  return (
    <div ref={ref} className={cn('relative h-full w-full overflow-hidden', className)}>
      {!loaded && <div className="absolute inset-0 animate-pulse bg-muted/50" />}
      {inView && (
        <img
          src={src}
          alt={alt}
          className={cn(
            'h-full w-full object-cover transition-opacity duration-300',
            loaded ? 'opacity-100' : 'opacity-0',
          )}
          onLoad={() => {
            setLoaded(true)
            onLoad?.()
          }}
          onError={onError}
        />
      )}
    </div>
  )
}
