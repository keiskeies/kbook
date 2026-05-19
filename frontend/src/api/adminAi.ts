import { STORAGE_KEYS } from '@/constants'
import { createSsePostConnection } from '@/utils/sse-request'
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
  onThinking?: (status: string) => void,
  onThinkingContent?: (chunk: string) => void,
): AbortController {
  return createSsePostConnection(
    '/admin/ai/chat/stream',
    data,
    { onChunk, onDone, onError, onThinking, onThinkingContent },
  )
}
