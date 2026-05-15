import request from '@/utils/request'
import { STORAGE_KEYS } from '@/constants'
import type { AiCreateSessionResponse, AiChatResponse, AiChatRequest } from '@/types/ai'
import type { AiConversation } from '@/types/ai'

/** 创建新会话 */
export function createSession() {
  return request.post<AiCreateSessionResponse>('/ai/sessions')
}

/** 非流式对话 */
export function chat(data: AiChatRequest) {
  return request.post<AiChatResponse>('/ai/chat', data)
}

/** 获取对话历史 */
export function getHistory(sessionId: string) {
  return request.get<AiConversation[]>('/ai/history', { params: { sessionId } })
}

/** 获取会话列表 */
export function getSessions() {
  return request.get<string[]>('/ai/sessions')
}

/** 删除会话 */
export function deleteSession(sessionId: string) {
  return request.delete(`/ai/sessions/${sessionId}`)
}

/** 获取热门提问（基于全站用户提问统计） */
export function getHotPrompts(count = 4) {
  return request.get<string[]>('/ai/hot-prompts', { params: { count } })
}

/**
 * 流式对话 — SSE
 * 支持 401 自动刷新 Token 后重试
 * @returns 返回清理函数，用于中断连接
 */
export function streamChat(
  data: AiChatRequest,
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
    fetch(`${baseUrl}/ai/chat/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': token ? `Bearer ${token}` : '',
      },
      body: JSON.stringify(data),
      signal: controller.signal,
    })
      .then(async (response) => {
        // 401 时尝试刷新 Token 后重试一次
        if (response.status === 401 && !isRetry) {
          const refreshed = await tryRefreshToken()
          if (refreshed) {
            doFetch(refreshed, true)
            return
          }
          // 刷新失败，清理认证信息并跳转登录
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

/** 尝试刷新 Token，返回新 token 或 null */
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
