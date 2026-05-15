import request from '@/utils/request'
import { STORAGE_KEYS } from '@/constants'

/**
 * 流式图书问答 — SSE
 * POST /api/books/{bookId}/chat/stream
 *
 * @returns AbortController 用于中断请求
 */
export function streamBookChat(
  bookId: number,
  data: { message: string; sessionId?: string },
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (error: Error) => void,
  onThinking?: (status: string) => void,
  onThinkingContent?: (chunk: string) => void,
): AbortController {
  const controller = new AbortController()
  const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api'
  const getToken = () => localStorage.getItem(STORAGE_KEYS.TOKEN)

  const doFetch = (token: string, isRetry = false) => {
    fetch(`${baseUrl}/books/${bookId}/chat/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': token ? `Bearer ${token}` : '',
      },
      body: JSON.stringify(data),
      signal: controller.signal,
    })
      .then(async (response) => {
        // 401 重试逻辑
        if (response.status === 401 && !isRetry) {
          const refreshed = await tryRefreshToken()
          if (refreshed) {
            doFetch(refreshed, true)
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
      })
      .catch((err) => {
        if (err.name !== 'AbortError') {
          onError(err)
        }
      })
  }

  const currentToken = getToken()
  if (currentToken) {
    doFetch(currentToken)
  } else {
    onError(new Error('需要登录后才能继续'))
  }

  return controller
}

/** 获取图书推荐问题 */
export function getBookSuggestedQuestions(bookId: number) {
  return request.get<string[]>(`/books/${bookId}/chat/suggestions`)
}

/** 获取图书问答历史 */
export function getBookChatHistory(bookId: number, sessionId?: string) {
  return request.get(`/books/${bookId}/chat/history`, {
    params: sessionId ? { sessionId } : {},
  })
}

// ==================== 内部辅助 ====================

async function tryRefreshToken(): Promise<string | null> {
  const refreshToken = localStorage.getItem(STORAGE_KEYS.REFRESH_TOKEN)
  if (!refreshToken) return null
  try {
    const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api'
    const { data: resp } = await (await import('axios')).default.post(`${baseUrl}/auth/refresh`, {
      refreshToken,
    })
    const newToken = resp.data.token
    const newRefreshToken = resp.data.refreshToken
    localStorage.setItem(STORAGE_KEYS.TOKEN, newToken)
    localStorage.setItem(STORAGE_KEYS.REFRESH_TOKEN, newRefreshToken)
    return newToken
  } catch {
    return null
  }
}

function clearAuthAndRedirect() {
  localStorage.removeItem(STORAGE_KEYS.TOKEN)
  localStorage.removeItem(STORAGE_KEYS.REFRESH_TOKEN)
  localStorage.removeItem(STORAGE_KEYS.USER_INFO)
  window.location.href = '/login'
}
