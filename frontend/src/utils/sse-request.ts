import { refreshAccessToken, clearAuthAndRedirect, getAccessToken } from './token-refresh'

const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'

export interface SseHandlers<TProgress, TDone> {
  onProgress: (data: TProgress) => void
  onDone: (data: TDone) => void
  onError: (error: Error) => void
}

/** SSE 协议行解析器 */
class SseParser {
  private buffer = ''
  private currentEvent = ''
  private dataLines: string[] = []
  private onEvent: (eventName: string, data: string) => boolean | void

  constructor(onEvent: (eventName: string, data: string) => boolean | void) {
    this.onEvent = onEvent
  }

  append(chunk: string): boolean {
    this.buffer += chunk
    const lines = this.buffer.split('\n')
    this.buffer = lines.pop() || ''

    for (const line of lines) {
      if (line.startsWith('event:')) {
        this.currentEvent = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        this.dataLines.push(line.slice(5))
      } else if (line === '') {
        if (this.dataLines.length > 0) {
          const data = this.dataLines.join('\n')
          const stop = this.onEvent(this.currentEvent, data)
          if (stop) return true
        }
        this.currentEvent = ''
        this.dataLines = []
      }
    }
    return false
  }

  flush() {
    if (this.dataLines.length > 0) {
      this.onEvent(this.currentEvent, this.dataLines.join('\n'))
    }
  }
}

function canUseFetchStream(): boolean {
  return typeof ReadableStream !== 'undefined' && typeof TextDecoder !== 'undefined'
}

/**
 * XHR 流式连接（当 ReadableStream 不可用时的回退方案）
 * 支持旧版 Safari、部分国产手机浏览器等
 */
function createXhrSseStream(
  method: string,
  url: string,
  headers: Record<string, string>,
  body: string | undefined,
  onEvent: (eventName: string, data: string) => boolean | void,
  onError: (error: Error) => void,
  signal: AbortSignal,
) {
  try {
    const xhr = new XMLHttpRequest()
    xhr.open(method, url, true)

    Object.entries(headers).forEach(([key, value]) => {
      if (value) xhr.setRequestHeader(key, value)
    })

    const parser = new SseParser(onEvent)
    let completed = false
    let lastProcessedLength = 0

    xhr.onprogress = () => {
      const newText = xhr.responseText.substring(lastProcessedLength)
      lastProcessedLength = xhr.responseText.length
      completed = parser.append(newText)
    }

    xhr.onloadend = () => {
      if (!completed) {
        parser.flush()
        onError(new Error('SSE 流异常结束'))
      }
    }

    xhr.onerror = () => onError(new Error('XHR 请求失败'))

    signal.addEventListener('abort', () => xhr.abort(), { once: true })

    xhr.send(body)
  } catch (err) {
    onError(err instanceof Error ? err : new Error('XHR 创建失败'))
  }
}

/**
 * 创建 SSE GET 连接，支持 token 过期自动刷新
 * 自动降级：浏览器支持 ReadableStream 用 fetch，否则用 XHR
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

  const searchParams = new URLSearchParams()
  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined) searchParams.append(key, String(value))
    })
  }
  const queryString = searchParams.toString()
  const fullUrl = `${BASE_URL}${url}${queryString ? `?${queryString}` : ''}`

  let hasRetried = false

  function sseEventHandler(eventName: string, data: string): boolean {
    try {
      const parsed = JSON.parse(data)
      if (eventName === 'progress') { onProgress(parsed); return false }
      if (eventName === 'done') { onDone(parsed); return true }
      if (eventName === 'error') { onError(new Error(parsed.message || parsed || 'SSE 出错')); return true }
    } catch { /* 非 JSON 数据忽略 */ }
    return false
  }

  function doXhr(token: string | null) {
    createXhrSseStream(
      'GET', fullUrl,
      { 'Authorization': token ? `Bearer ${token}` : '', 'Accept': 'text/event-stream' },
      undefined,
      sseEventHandler,
      onError,
      controller.signal,
    )
  }

  async function connect() {
    const token = getAccessToken()

    if (!canUseFetchStream()) {
      doXhr(token)
      return
    }

    try {
      const response = await fetch(fullUrl, {
        method: 'GET',
        headers: {
          'Authorization': token ? `Bearer ${token}` : '',
          'Accept': 'text/event-stream',
        },
        signal: controller.signal,
      })

      if ((response.status === 401 || response.status === 403) && autoRetryOnAuthError && !hasRetried) {
        hasRetried = true
        const newToken = await refreshAccessToken()
        if (newToken) { connect(); return }
        clearAuthAndRedirect()
        return
      }

      if (!response.ok) throw new Error(`HTTP ${response.status}`)

      const reader = response.body?.getReader()
      if (!reader) { doXhr(token); return }

      const decoder = new TextDecoder()
      const parser = new SseParser(sseEventHandler)
      let receivedDone = false

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        receivedDone = parser.append(decoder.decode(value, { stream: true }))
      }

      if (!receivedDone) onError(new Error('SSE 流异常结束'))
    } catch (err: unknown) {
      if (err instanceof Error && err.name !== 'AbortError') onError(err)
    }
  }

  connect()
  return controller
}

/**
 * SSE POST 流式连接，支持 token 过期自动刷新
 * 自动降级：浏览器支持 ReadableStream 用 fetch，否则用 XHR
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
    onSessionId?: (sessionId: string) => void
    onFollowUpQuestions?: (questionsJson: string) => void
  },
): AbortController {
  const controller = new AbortController()
  const { onChunk, onDone, onError, onThinking, onThinkingContent, onBookMap, onSessionId, onFollowUpQuestions } = handlers
  const fullUrl = `${BASE_URL}${url}`

  let hasRetried = false

  function handleSseEvent(eventName: string, data: string): boolean {
    if (eventName === 'done') { onDone(); return true }
    if (eventName === 'error') { onError(new Error(data)); return true }
    if (eventName === 'thinking') { onThinking?.(data); return false }
    if (eventName === 'thinking_content') { onThinkingContent?.(data); return false }
    if (eventName === 'book_map') {
      try { onBookMap?.(JSON.parse(data)) } catch { /* ignore */ }
      return false
    }
    if (eventName === 'session_id') { onSessionId?.(data); return false }
    if (eventName === 'follow_up_questions') { onFollowUpQuestions?.(data); return false }
    // 未明确处理的事件名（如 message）也作为消息块处理
    if (data === '[DONE]') { onDone(); return true }
    onChunk(data)
    return false
  }

  function doXhr(token: string) {
    createXhrSseStream(
      'POST', fullUrl,
      { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}`, 'Accept': 'text/event-stream' },
      JSON.stringify(body),
      handleSseEvent,
      onError,
      controller.signal,
    )
  }

  async function connect() {
    const token = getAccessToken()
    if (!token) { onError(new Error('需要登录后才能继续')); return }

    if (!canUseFetchStream()) {
      doXhr(token)
      return
    }

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

      if ((response.status === 401 || response.status === 403) && !hasRetried) {
        hasRetried = true
        const newToken = await refreshAccessToken()
        if (newToken) { connect(); return }
        clearAuthAndRedirect()
        onError(new Error('登录已过期，请重新登录'))
        return
      }

      if (!response.ok) throw new Error(`HTTP ${response.status}`)

      const reader = response.body?.getReader()
      if (!reader) { doXhr(token); return }

      const decoder = new TextDecoder()
      const parser = new SseParser(handleSseEvent)

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        const stop = parser.append(decoder.decode(value, { stream: true }))
        if (stop) return
      }
      onDone()
    } catch (err: unknown) {
      if (err instanceof Error && err.name !== 'AbortError') onError(err)
    }
  }

  connect()
  return controller
}
