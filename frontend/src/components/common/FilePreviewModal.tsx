import { useState, useEffect, useCallback, useRef } from 'react'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Download, FileText, File, Loader2, RotateCcw } from 'lucide-react'
import { cn } from '@/lib/utils'
import { fetchWithAuth, getAuthBlobUrl } from '@/utils/auth-image'
import { toast } from 'sonner'

interface FilePreviewModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  fileUrl: string
  fileName: string
  fileType: string
}

function getFileCategory(fileName: string): 'image' | 'pdf' | 'video' | 'audio' | 'text' | 'document' {
  const ext = fileName.toLowerCase().split('.').pop() || ''
  if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'].includes(ext)) return 'image'
  if (ext === 'pdf') return 'pdf'
  if (['mp4', 'mov', 'avi', 'mkv', 'wmv', 'flv'].includes(ext)) return 'video'
  if (['txt', 'md'].includes(ext)) return 'text'
  return 'document'
}

function getFileIcon(fileName: string) {
  const ext = fileName.toLowerCase().split('.').pop() || ''
  switch (ext) {
    case 'pdf': return <FileText className="h-12 w-12 text-red-500" />
    case 'doc': case 'docx': return <FileText className="h-12 w-12 text-blue-600" />
    case 'xls': case 'xlsx': return <FileText className="h-12 w-12 text-green-600" />
    case 'ppt': case 'pptx': return <FileText className="h-12 w-12 text-orange-500" />
    case 'txt': return <FileText className="h-12 w-12 text-gray-500" />
    case 'md': return <FileText className="h-12 w-12 text-purple-500" />
    default: return <File className="h-12 w-12 text-muted-foreground" />
  }
}

function PinchZoomImage({ src, alt }: { src: string; alt: string }) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [transform, setTransform] = useState({ scale: 1, x: 0, y: 0 })
  const lastTouchRef = useRef<{ distance: number; x: number; y: number; scale: number; tx: number; ty: number } | null>(null)
  const isDraggingRef = useRef(false)
  const lastSingleTouchRef = useRef<{ x: number; y: number; tx: number; ty: number } | null>(null)

  const resetTransform = useCallback(() => {
    setTransform({ scale: 1, x: 0, y: 0 })
  }, [])

  const clampTranslate = useCallback((x: number, y: number, scale: number) => {
    if (!containerRef.current) return { x, y }
    const container = containerRef.current
    const imgW = container.scrollWidth * scale
    const imgH = container.scrollHeight * scale
    const cW = container.clientWidth
    const cH = container.clientHeight
    const overflowX = Math.max(0, (imgW - cW) / 2)
    const overflowY = Math.max(0, (imgH - cH) / 2)
    return {
      x: Math.max(-overflowX, Math.min(overflowX, x)),
      y: Math.max(-overflowY, Math.min(overflowY, y)),
    }
  }, [])

  const handleTouchStart = useCallback((e: React.TouchEvent) => {
    if (e.touches.length === 2) {
      e.preventDefault()
      const dx = e.touches[0].clientX - e.touches[1].clientX
      const dy = e.touches[0].clientY - e.touches[1].clientY
      lastTouchRef.current = {
        distance: Math.hypot(dx, dy),
        x: (e.touches[0].clientX + e.touches[1].clientX) / 2,
        y: (e.touches[0].clientY + e.touches[1].clientY) / 2,
        scale: transform.scale,
        tx: transform.x,
        ty: transform.y,
      }
      isDraggingRef.current = false
      lastSingleTouchRef.current = null
    } else if (e.touches.length === 1) {
      if (transform.scale > 1) {
        lastSingleTouchRef.current = {
          x: e.touches[0].clientX,
          y: e.touches[0].clientY,
          tx: transform.x,
          ty: transform.y,
        }
        isDraggingRef.current = true
      }
    }
  }, [transform])

  const handleTouchMove = useCallback((e: React.TouchEvent) => {
    if (e.touches.length === 2 && lastTouchRef.current) {
      e.preventDefault()
      const dx = e.touches[0].clientX - e.touches[1].clientX
      const dy = e.touches[0].clientY - e.touches[1].clientY
      const distance = Math.hypot(dx, dy)
      const scaleRatio = distance / lastTouchRef.current.distance
      const newScale = Math.max(1, Math.min(5, lastTouchRef.current.scale * scaleRatio))
      const cx = (e.touches[0].clientX + e.touches[1].clientX) / 2
      const cy = (e.touches[0].clientY + e.touches[1].clientY) / 2
      const panX = cx - lastTouchRef.current.x
      const panY = cy - lastTouchRef.current.y
      const clamped = clampTranslate(
        lastTouchRef.current.tx + panX,
        lastTouchRef.current.ty + panY,
        newScale
      )
      setTransform({ scale: newScale, x: clamped.x, y: clamped.y })
    } else if (e.touches.length === 1 && isDraggingRef.current && lastSingleTouchRef.current) {
      e.preventDefault()
      const dx = e.touches[0].clientX - lastSingleTouchRef.current.x
      const dy = e.touches[0].clientY - lastSingleTouchRef.current.y
      const clamped = clampTranslate(
        lastSingleTouchRef.current.tx + dx,
        lastSingleTouchRef.current.ty + dy,
        transform.scale
      )
      setTransform(prev => ({ ...prev, x: clamped.x, y: clamped.y }))
    }
  }, [clampTranslate, transform.scale])

  const handleTouchEnd = useCallback(() => {
    lastTouchRef.current = null
    isDraggingRef.current = false
    lastSingleTouchRef.current = null
    setTransform(prev => {
      if (prev.scale <= 1) return { scale: 1, x: 0, y: 0 }
      return prev
    })
  }, [])

  const handleWheel = useCallback((e: React.WheelEvent) => {
    e.preventDefault()
    const delta = e.deltaY > 0 ? 0.9 : 1.1
    setTransform(prev => {
      const newScale = Math.max(1, Math.min(5, prev.scale * delta))
      if (newScale <= 1) return { scale: 1, x: 0, y: 0 }
      const clamped = clampTranslate(prev.x, prev.y, newScale)
      return { scale: newScale, x: clamped.x, y: clamped.y }
    })
  }, [clampTranslate])

  return (
    <div
      ref={containerRef}
      className="flex items-center justify-center min-h-[300px] max-h-[70vh] overflow-hidden relative touch-none select-none"
      onTouchStart={handleTouchStart}
      onTouchMove={handleTouchMove}
      onTouchEnd={handleTouchEnd}
      onWheel={handleWheel}
    >
      <img
        src={src}
        alt={alt}
        className="max-w-full max-h-[70vh] object-contain rounded-lg pointer-events-none"
        style={{
          transform: `translate(${transform.x}px, ${transform.y}px) scale(${transform.scale})`,
          transformOrigin: 'center center',
          transition: lastTouchRef.current ? 'none' : 'transform 0.15s ease-out',
        }}
        draggable={false}
      />
      {transform.scale > 1 && (
        <button
          onClick={resetTransform}
          className="absolute bottom-3 right-3 flex h-8 w-8 items-center justify-center rounded-full bg-black/50 text-white backdrop-blur-sm active:bg-black/70 transition-colors"
        >
          <RotateCcw className="h-4 w-4" />
        </button>
      )}
    </div>
  )
}

export default function FilePreviewModal({ open, onOpenChange, fileUrl, fileName }: FilePreviewModalProps) {
  const category = getFileCategory(fileName)
  const [textContent, setTextContent] = useState<string | null>(null)
  const [imageBlobUrl, setImageBlobUrl] = useState<string | null>(null)
  const [mediaBlobUrl, setMediaBlobUrl] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const handleDownload = useCallback(async () => {
    try {
      const blobUrl = await getAuthBlobUrl(fileUrl)
      const a = document.createElement('a')
      a.href = blobUrl
      a.download = fileName
      a.target = '_blank'
      a.rel = 'noopener noreferrer'
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
    } catch {
      toast.error('下载失败')
    }
  }, [fileUrl, fileName])

  useEffect(() => {
    if (!open) {
      setTextContent(null)
      setImageBlobUrl(null)
      if (mediaBlobUrl) {
        URL.revokeObjectURL(mediaBlobUrl)
        setMediaBlobUrl(null)
      }
      return
    }

    if (category === 'image') {
      getAuthBlobUrl(fileUrl).then(url => setImageBlobUrl(url)).catch(() => setImageBlobUrl(null))
    } else if (category === 'text') {
      setLoading(true)
      setTextContent(null)
      fetchWithAuth(fileUrl)
        .then(res => {
          if (!res.ok) throw new Error('Failed to load')
          return res.text()
        })
        .then(text => {
          setTextContent(text)
          setLoading(false)
        })
        .catch(() => {
          setTextContent(null)
          setLoading(false)
        })
    } else if (category === 'pdf' || category === 'video' || category === 'audio') {
      setLoading(true)
      setMediaBlobUrl(null)
      fetchWithAuth(fileUrl)
        .then(res => {
          if (!res.ok) throw new Error('Failed to load')
          return res.blob()
        })
        .then(blob => {
          const url = URL.createObjectURL(blob)
          setMediaBlobUrl(url)
          setLoading(false)
        })
        .catch(() => {
          setMediaBlobUrl(null)
          setLoading(false)
        })
    }
  }, [open, category, fileUrl])

  useEffect(() => {
    return () => {
      if (mediaBlobUrl) {
        URL.revokeObjectURL(mediaBlobUrl)
      }
    }
  }, [mediaBlobUrl])

  const renderPreview = () => {
    switch (category) {
      case 'image':
        return imageBlobUrl ? <PinchZoomImage src={imageBlobUrl} alt={fileName} /> : (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
          </div>
        )

      case 'pdf':
        if (loading) {
          return (
            <div className="flex items-center justify-center py-12">
              <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
            </div>
          )
        }
        return (
          <div className="w-full h-[70vh] rounded-lg overflow-hidden border">
            {mediaBlobUrl ? (
              <iframe
                src={mediaBlobUrl}
                className="w-full h-full"
                title={fileName}
              />
            ) : (
              <div className="flex items-center justify-center h-full text-muted-foreground">
                无法加载PDF预览
              </div>
            )}
          </div>
        )

      case 'video':
        if (loading) {
          return (
            <div className="flex items-center justify-center py-12">
              <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
            </div>
          )
        }
        return (
          <div className="flex items-center justify-center min-h-[300px] max-h-[70vh] rounded-lg overflow-hidden bg-black/5">
            {mediaBlobUrl ? (
              <video
                src={mediaBlobUrl}
                controls
                className="max-w-full max-h-[70vh] w-full"
              >
                您的浏览器不支持视频播放
              </video>
            ) : (
              <div className="text-muted-foreground">无法加载视频预览</div>
            )}
          </div>
        )

      case 'text':
        if (loading) {
          return (
            <div className="flex items-center justify-center py-12">
              <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
            </div>
          )
        }
        return (
          <div className="bg-muted/30 rounded-lg p-4 max-h-[60vh] overflow-auto">
            <pre className="text-sm whitespace-pre-wrap break-words font-mono">
              {textContent || '无法加载文件内容'}
            </pre>
          </div>
        )

      case 'document':
      default:
        return (
          <div className="flex flex-col items-center justify-center py-12 gap-4">
            {getFileIcon(fileName)}
            <div className="text-center">
              <p className="font-medium">{fileName}</p>
              <p className="text-sm text-muted-foreground mt-1">此文件类型暂不支持在线预览</p>
            </div>
          </div>
        )
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className={cn(
        "max-w-3xl sm:max-w-3xl",
        category === 'document' && "sm:max-w-md"
      )}>
        <DialogHeader className="flex flex-row items-center justify-between gap-4 pr-8">
          <DialogTitle className="min-w-0 break-all text-base leading-snug">{fileName}</DialogTitle>
          <button
            onClick={handleDownload}
            className="flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-lg bg-primary text-primary-foreground hover:bg-primary/90 transition-colors shrink-0"
          >
            <Download className="h-4 w-4" />
            <span>下载</span>
          </button>
        </DialogHeader>
        {renderPreview()}
      </DialogContent>
    </Dialog>
  )
}
