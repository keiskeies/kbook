import { refreshAccessToken, clearAuthAndRedirect, getAccessToken } from './token-refresh'

const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'

/**
 * SSE 事件处理器
 */
export interface SseHandlers<TProgress, TDone> {
  onProgress: (data: TProgress) => void
  onDone: (data: TDone) => void
  onError: (error: Error) => void
}

/**
 * 创建 SSE GET 连接，支持 token 过期自动刷新
 *
 * @param url SSE 端点路径（不含 baseUrl，如 /books/admin/scan）
 * @param handlers 事件处理器
 * @param options 可选配置
 * @returns AbortController
 */
export function createSseConnection<TProgress, TDone>(
  url: string,
  handlers: SseHandlers<TProgress, TDone>,
  options?: {
    params?: Record<string, string | number | undefined>
    autoRetryOnAuthError?: boolean
  },
): AbortController {
  const controller = new AbortController()
  const { onProgress, onDone, onError } = handlers
  const { params, autoRetryOnAuthError = true } = options || {}

  // 构建查询参数
  const searchParams = new URLSearchParams()
  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined) searchParams.append(key, String(value))
    })
  }
  const queryString = searchParams.toString()
  const fullUrl = `${BASE_URL}${url}${queryString ? `?${queryString}` : ''}`

  let hasRetried = false

  async function connect() {
    const token = getAccessToken()

    try {
      const response = await fetch(fullUrl, {
        method: 'GET',
        headers: {
          'Authorization': token ? `Bearer ${token}` : '',
          'Accept': 'text/event-stream',
        },
        signal: controller.signal,
      })

      // 401/403 时尝试刷新 token 并重试一次
      if ((response.status === 401 || response.status === 403) && autoRetryOnAuthError && !hasRetried) {
        hasRetried = true
        const newToken = await refreshAccessToken()
        if (newToken) {
          connect()
          return
        }
        clearAuthAndRedirect()
        return
      }

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }

      const reader = response.body?.getReader()
      if (!reader) throw new Error('No readable stream')

      const decoder = new TextDecoder()
      let buffer = ''
      let receivedDone = false

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        let currentEvent = ''
        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            const data = line.slice(5).trim()
            try {
              const parsed = JSON.parse(data)
              if (currentEvent === 'progress') {
                onProgress(parsed)
              } else if (currentEvent === 'done') {
                receivedDone = true
                onDone(parsed)
              } else if (currentEvent === 'error') {
                onError(new Error(parsed.message || parsed || 'SSE 出错'))
              }
            } catch {
              // 非 JSON 数据忽略
            }
            currentEvent = ''
          }
        }
      }

      if (!receivedDone) {
        onError(new Error('SSE 流异常结束'))
      }
    } catch (err: unknown) {
      if (err instanceof Error && err.name !== 'AbortError') {
        onError(err)
      }
    }
  }

  connect()
  return controller
}

/**
 * SSE POST 流式连接，支持 token 过期自动刷新
 * 用于 AI 对话等需要 POST body 的 SSE 场景
 *
 * @param url SSE 端点路径（不含 baseUrl）
 * @param body 请求体
 * @param handlers 事件处理器（onChunk 用于逐字符/逐句接收）
 * @returns AbortController
 */
export function createSsePostConnection(
  url: string,
  body: unknown,
  handlers: {
    onChunk: (text: string) => void
    onDone: () => void
    onError: (error: Error) => void
    onThinking?: (status: string) => void
    onThinkingContent?: (chunk: string) => void
    onBookMap?: (bookMap: Record<string, number>) => void
  },
): AbortController {
  const controller = new AbortController()
  const { onChunk, onDone, onError, onThinking, onThinkingContent, onBookMap } = handlers
  const fullUrl = `${BASE_URL}${url}`

  let hasRetried = false

  async function connect() {
    const token = getAccessToken()

    try {
      const response = await fetch(fullUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': token ? `Bearer ${token}` : '',
          'Accept': 'text/event-stream',
        },
        body: JSON.stringify(body),
        signal: controller.signal,
      })

      // 401/403 时尝试刷新 token 并重试一次
      if ((response.status === 401 || response.status === 403) && !hasRetried) {
        hasRetried = true
        const newToken = await refreshAccessToken()
        if (newToken) {
          connect()
          return
        }
        clearAuthAndRedirect()
        onError(new Error('登录已过期，请重新登录'))
        return
      }

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }

      const reader = response.body?.getReader()
      if (!reader) throw new Error('No readable stream')

      const decoder = new TextDecoder()
      let buffer = ''
      let currentEventName = ''
      let dataLines: string[] = []

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEventName = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5))
          } else if (line === '') {
            // 空行 = SSE 事件结束，合并 data 行
            if (dataLines.length > 0) {
              const data = dataLines.join('\n')
              if (currentEventName === 'done') {
                onDone()
                return
              } else if (currentEventName === 'error') {
                onError(new Error(data))
                return
              } else if (currentEventName === 'thinking') {
                onThinking?.(data)
              } else if (currentEventName === 'thinking_content') {
                onThinkingContent?.(data)
              } else if (currentEventName === 'book_map') {
                try {
                  const bookMap = JSON.parse(data)
                  onBookMap?.(bookMap)
                } catch { /* ignore parse error */ }
              } else {
                if (data === '[DONE]') {
                  onDone()
                  return
                }
                onChunk(data)
              }
            }
            currentEventName = ''
            dataLines = []
          }
        }
      }
      onDone()
    } catch (err: unknown) {
      if (err instanceof Error && err.name !== 'AbortError') {
        onError(err)
      }
    }
  }

  const currentToken = getAccessToken()
  if (currentToken) {
    connect()
  } else {
    onError(new Error('需要登录后才能继续'))
  }

  return controller
}
