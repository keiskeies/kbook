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
