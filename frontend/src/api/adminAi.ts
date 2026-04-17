import { STORAGE_KEYS } from '@/constants'
import type { AiChatRequest, AiChatResponse, AiCreateSessionResponse, AiConversation } from '@/types/ai'

const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api'

/** 创建管理员 AI 会话 */
export async function createAdminSession(): Promise<AiCreateSessionResponse> {
  const token = localStorage.getItem(STORAGE_KEYS.TOKEN)
  const res = await fetch(`${baseUrl}/admin/ai/sessions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': token ? `Bearer ${token}` : '',
    },
  })
  const json = await res.json()
  return json.data
}

/** 管理员非流式对话 */
export async function adminChat(data: AiChatRequest): Promise<AiChatResponse> {
  const token = localStorage.getItem(STORAGE_KEYS.TOKEN)
  const res = await fetch(`${baseUrl}/admin/ai/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': token ? `Bearer ${token}` : '',
    },
    body: JSON.stringify(data),
  })
  const json = await res.json()
  return json.data
}

/** 获取管理员对话历史 */
export async function getAdminHistory(sessionId: string): Promise<AiConversation[]> {
  const token = localStorage.getItem(STORAGE_KEYS.TOKEN)
  const res = await fetch(`${baseUrl}/admin/ai/history?sessionId=${encodeURIComponent(sessionId)}`, {
    headers: { 'Authorization': token ? `Bearer ${token}` : '' },
  })
  const json = await res.json()
  return json.data || []
}

/** 获取管理员会话列表 */
export async function getAdminSessions(): Promise<string[]> {
  const token = localStorage.getItem(STORAGE_KEYS.TOKEN)
  const res = await fetch(`${baseUrl}/admin/ai/sessions`, {
    headers: { 'Authorization': token ? `Bearer ${token}` : '' },
  })
  const json = await res.json()
  return json.data || []
}

/** 删除管理员会话 */
export async function deleteAdminSession(sessionId: string): Promise<void> {
  const token = localStorage.getItem(STORAGE_KEYS.TOKEN)
  await fetch(`${baseUrl}/admin/ai/sessions/${sessionId}`, {
    method: 'DELETE',
    headers: { 'Authorization': token ? `Bearer ${token}` : '' },
  })
}

/**
 * 管理员流式对话 — SSE
 * @returns AbortController 用于中断请求
 */
export function streamAdminChat(
  data: AiChatRequest,
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (error: Error) => void,
): AbortController {
  const controller = new AbortController()
  const getToken = () => localStorage.getItem(STORAGE_KEYS.TOKEN)

  const doFetch = (token: string, isRetry = false) => {
    fetch(`${baseUrl}/admin/ai/chat/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': token ? `Bearer ${token}` : '',
      },
      body: JSON.stringify(data),
      signal: controller.signal,
    })
      .then(async (response) => {
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

        while (true) {
          const { done, value } = await reader.read()
          if (done) break

          buffer += decoder.decode(value, { stream: true })
          const lines = buffer.split('\n')
          buffer = lines.pop() || ''

          for (const line of lines) {
            if (line.startsWith('event:message')) {
              // 下一个 line 是 data
            } else if (line.startsWith('data:')) {
              const text = line.slice(5)
              if (text === '[DONE]') {
                onDone()
                return
              }
              onChunk(text)
            } else if (line.startsWith('event:error')) {
              // 错误事件
            } else if (line.startsWith('event:done')) {
              onDone()
              return
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
    onError(new Error('未登录，请先登录'))
  }

  return controller
}

// ==================== 内部辅助 ====================

async function tryRefreshToken(): Promise<string | null> {
  const refreshToken = localStorage.getItem(STORAGE_KEYS.REFRESH_TOKEN)
  if (!refreshToken) return null
  try {
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
