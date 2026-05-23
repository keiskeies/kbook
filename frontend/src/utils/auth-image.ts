import { getAccessToken } from '@/utils/token-refresh'

const blobCache = new Map<string, string>()

export function getAuthBlobUrl(url: string): Promise<string> {
  if (blobCache.has(url)) return Promise.resolve(blobCache.get(url)!)

  const token = getAccessToken()
  return fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
    .then(res => {
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      return res.blob()
    })
    .then(blob => {
      const blobUrl = URL.createObjectURL(blob)
      blobCache.set(url, blobUrl)
      return blobUrl
    })
}

export function getCachedBlobUrl(url: string): string | undefined {
  return blobCache.get(url)
}

export function fetchWithAuth(url: string): Promise<Response> {
  const token = getAccessToken()
  return fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
}

/**
 * 将原始文件 URL 转换为缩略图 URL（图片）
 * 例: /api/uploads/chat/abc.png → /api/uploads/chat/abc_thumbnail.png
 */
export function getThumbnailUrl(url: string): string {
  const dotIndex = url.lastIndexOf('.')
  if (dotIndex < 0) return url
  return url.substring(0, dotIndex) + '_thumbnail' + url.substring(dotIndex)
}

/**
 * 将视频文件 URL 转换为缩略图 URL
 * 例: /api/uploads/chat/abc.mp4 → /api/uploads/chat/abc_thumb.jpg
 */
export function getVideoThumbnailUrl(url: string): string {
  const dotIndex = url.lastIndexOf('.')
  if (dotIndex < 0) return ''
  return url.substring(0, dotIndex) + '_thumb.jpg'
}

const VIDEO_EXTENSIONS = ['mp4', 'mov', 'avi', 'mkv', 'webm', 'wmv', 'flv']

/** 判断文件名是否为视频文件 */
export function isVideoFileName(fileName: string): boolean {
  const ext = fileName.toLowerCase().split('.').pop() || ''
  return VIDEO_EXTENSIONS.includes(ext)
}
