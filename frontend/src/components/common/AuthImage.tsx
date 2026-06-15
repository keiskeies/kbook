import { useState, useEffect } from 'react'
import { getAuthBlobUrl, getCachedBlobUrl } from '@/utils/auth-image'
import { cn } from '@/utils/cn'

interface AuthImageProps extends React.ImgHTMLAttributes<HTMLImageElement> {
  src: string
}

export default function AuthImage({ src, className, ...props }: AuthImageProps) {
  const [blobUrl, setBlobUrl] = useState(() => getCachedBlobUrl(src))

  useEffect(() => {
    if (!src) return
    const cached = getCachedBlobUrl(src)
    if (cached) {
      setBlobUrl(cached)
      return
    }
    getAuthBlobUrl(src)
      .then(url => setBlobUrl(url))
      .catch(() => setBlobUrl(undefined))
  }, [src])

  if (!blobUrl) {
    return (
      <div
        className={cn('animate-pulse bg-muted rounded-lg', className)}
        style={props.style}
      />
    )
  }

  return <img src={blobUrl} className={className} {...props} />
}
