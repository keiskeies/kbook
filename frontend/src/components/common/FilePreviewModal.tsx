import { useState, useEffect, useCallback } from 'react'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Download, FileText, File, Loader2 } from 'lucide-react'
import { getAccessToken } from '@/utils/token-refresh'
import { cn } from '@/lib/utils'

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

export default function FilePreviewModal({ open, onOpenChange, fileUrl, fileName }: FilePreviewModalProps) {
  const category = getFileCategory(fileName)
  const [textContent, setTextContent] = useState<string | null>(null)
  const [blobUrl, setBlobUrl] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const token = getAccessToken()
  const urlWithToken = `${fileUrl}?token=${token || ''}`

  const handleDownload = useCallback(() => {
    const a = document.createElement('a')
    a.href = urlWithToken
    a.download = fileName
    a.target = '_blank'
    a.rel = 'noopener noreferrer'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
  }, [urlWithToken, fileName])

  useEffect(() => {
    if (!open) {
      setTextContent(null)
      if (blobUrl) {
        URL.revokeObjectURL(blobUrl)
        setBlobUrl(null)
      }
      return
    }

    if (category === 'text') {
      setLoading(true)
      setTextContent(null)
      fetch(urlWithToken)
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
      setBlobUrl(null)
      fetch(urlWithToken)
        .then(res => {
          if (!res.ok) throw new Error('Failed to load')
          return res.blob()
        })
        .then(blob => {
          const url = URL.createObjectURL(blob)
          setBlobUrl(url)
          setLoading(false)
        })
        .catch(() => {
          setBlobUrl(null)
          setLoading(false)
        })
    }
  }, [open, category, urlWithToken])

  useEffect(() => {
    return () => {
      if (blobUrl) {
        URL.revokeObjectURL(blobUrl)
      }
    }
  }, [blobUrl])

  const renderPreview = () => {
    switch (category) {
      case 'image':
        return (
          <div className="flex items-center justify-center min-h-[300px] max-h-[70vh]">
            <img
              src={urlWithToken}
              alt={fileName}
              className="max-w-full max-h-[70vh] object-contain rounded-lg"
            />
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
            {blobUrl ? (
              <iframe
                src={blobUrl}
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
            {blobUrl ? (
              <video
                src={blobUrl}
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
          <DialogTitle className="truncate text-base">{fileName}</DialogTitle>
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
