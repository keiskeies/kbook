import request from '@/utils/request'
import { createSsePostConnectionWithEvents } from '@/utils/sse-request'
import type { RoundTableRole, RoundTableSession, RoundTableMessage, RoundTableCoverage, RoundTableReport, NextSpeakerResult } from '@/types/roundTable'

/** 获取推荐角色列表（LLM选角） */
export function getRoundTableRoles(bookId: number, refresh?: boolean) {
  return request.get<RoundTableRole[]>(`/round-table/books/${bookId}/roles`, { params: { refresh } })
}

/** 创建圆桌派会话 */
export function createRoundTableSession(
  bookId: number,
  roleKeys: string[],
  roleConfigs: string,
) {
  return request.post<RoundTableSession>(`/round-table/books/${bookId}/sessions`, {
    roleKeys,
    roleConfigs,
  })
}

/** 获取书籍的圆桌派会话列表 */
export function getRoundTableSessions(bookId: number) {
  return request.get<RoundTableSession[]>(`/round-table/books/${bookId}/sessions`)
}

/** 获取全局圆桌派会话列表（发现页用） */
export function getGlobalRoundTableSessions(page = 0, size = 20, sort = 'recent', mine = false) {
  return request.get<any>(`/round-table/sessions`, { params: { page, size, sort, mine } })
}

/** 获取会话详情 */
export function getRoundTableSession(sessionId: string) {
  return request.get<RoundTableSession>(`/round-table/sessions/${sessionId}`)
}

/** 更新会话状态 */
export function updateRoundTableSessionStatus(sessionId: string, status: string) {
  return request.put<RoundTableSession>(`/round-table/sessions/${sessionId}/status`, { status })
}

/** 获取会话历史消息 */
export function getRoundTableMessages(sessionId: string) {
  return request.get<RoundTableMessage[]>(`/round-table/sessions/${sessionId}/messages`)
}

/** 删除会话 */
export function deleteRoundTableSession(sessionId: string) {
  return request.delete(`/round-table/sessions/${sessionId}`)
}

/** 获取覆盖度报告 */
export function getRoundTableCoverage(sessionId: string) {
  return request.get<RoundTableCoverage>(`/round-table/sessions/${sessionId}/coverage`)
}

/** 刷新覆盖度 */
export function refreshRoundTableCoverage(sessionId: string) {
  return request.post<RoundTableCoverage>(`/round-table/sessions/${sessionId}/coverage/refresh`)
}

/** 触发解读报告生成（异步，约2-3分钟） */
export function triggerRoundTableReport(sessionId: string) {
  return request.post<RoundTableReport>(`/round-table/sessions/${sessionId}/report`)
}

/** 获取解读报告 */
export function getRoundTableReport(sessionId: string) {
  return request.get<RoundTableReport>(`/round-table/sessions/${sessionId}/report`)
}

/** 导出讨论记录为 Markdown */
export async function exportRoundTableSession(sessionId: string) {
  const token = localStorage.getItem('kbook_token')
  const res = await fetch(`/api/round-table/sessions/${sessionId}/export`, {
    headers: { Authorization: `Bearer ${token || ''}` },
  })
  if (!res.ok) throw new Error('导出失败')
  const blob = await res.blob()
  const disposition = res.headers.get('Content-Disposition') || ''
  const match = disposition.match(/filename\*=UTF-8''(.+)/)
  const filename = match ? decodeURIComponent(match[1]) : '圆桌派讨论.md'
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

/** LLM 判断下一轮发言人（含是否应该结束讨论） */
export function getNextSpeaker(sessionId: string) {
  return request.post<NextSpeakerResult>(`/round-table/sessions/${sessionId}/next-speaker`)
}

/** 单角色发言 SSE */
export function streamCharacterSpeak(
  bookId: number,
  roleKey: string,
  sessionId: string,
  topic?: string,
): {
  abortController: AbortController
  onMessage: ((handler: (text: string) => void) => void)
  onDone: ((handler: () => void) => void)
  onError: ((handler: (err: Error) => void) => void)
} {
  const listeners = {
    message: [] as ((text: string) => void)[],
    done: [] as (() => void)[],
    error: [] as ((err: Error) => void)[],
  }

  const abortController = createSsePostConnectionWithEvents(
    `/round-table/books/${bookId}/speak`,
    { roleKey, sessionId, topic },
    {
      onEvent: (eventName: string, data: string) => {
        if (eventName === 'message') {
          try {
            const parsed = JSON.parse(data)
            listeners.message.forEach(h => h(parsed.text || ''))
          } catch { /* ignore */ }
        }
      },
      onDone: () => {
        listeners.done.forEach(h => h())
      },
      onError: (error: Error) => {
        listeners.error.forEach(h => h(error))
      },
    },
  )

  return {
    abortController,
    onMessage: (handler) => { listeners.message.push(handler) },
    onDone: (handler) => { listeners.done.push(handler) },
    onError: (handler) => { listeners.error.push(handler) },
  }
}
