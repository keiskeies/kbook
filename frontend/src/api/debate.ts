import request from '@/utils/request'
import { createSsePostConnectionWithEvents } from '@/utils/sse-request'
import type {
  DebateRole,
  DebateSession,
  DebateMessage,
  DebateScore,
  DebateReport,
  DebateTopic,
} from '@/types/debate'

/** 获取辩论角色列表 */
export function getDebateRoles() {
  return request.get<DebateRole[]>('/debate/roles')
}

/** 使用LLM优化用户自定义辩题 */
export function optimizeDebateTopic(
  bookId: number,
  manualTopic: string,
  manualProArg: string,
  manualConArg: string,
) {
  return request.post<DebateTopic>(`/debate/books/${bookId}/optimize-topic`, {
    manualTopic,
    manualProArg,
    manualConArg,
  })
}

/** 获取LLM推荐辩题 */
export function getDebateTopics(bookId: number) {
  return request.get<DebateTopic[]>(`/debate/books/${bookId}/topics`)
}

/** 创建辩论会话 */
export function createDebateSession(
  bookId: number,
  topic: string,
  topicSource?: string,
  bookContext?: string,
  proRoleKeys?: string,
  conRoleKeys?: string,
) {
  return request.post<DebateSession>(`/debate/books/${bookId}/sessions`, {
    topic,
    topicSource,
    bookContext,
    proRoleKeys,
    conRoleKeys,
  })
}

/** 获取辩论会话列表 */
export function getDebateSessions(bookId: number) {
  return request.get<DebateSession[]>(`/debate/books/${bookId}/sessions`)
}

/** 获取辩论历史消息 */
export function getDebateMessages(sessionId: string) {
  return request.get<DebateMessage[]>(`/debate/sessions/${sessionId}/messages`)
}

/** 删除辩论会话 */
export function deleteDebateSession(sessionId: string) {
  return request.delete(`/debate/sessions/${sessionId}`)
}

/** 获取单场辩论会话详情 */
export function getDebateSession(sessionId: string) {
  return request.get<DebateSession>(`/debate/sessions/${sessionId}`)
}

/** 自由辩论 — 获取下一发言人 */
export function getNextDebateSpeaker(sessionId: string) {
  return request.post<string>(`/debate/sessions/${sessionId}/next-speaker`)
}

/** 推进到下一轮 */
export function advanceDebateRound(sessionId: string) {
  return request.post<DebateSession>(`/debate/sessions/${sessionId}/advance-round`)
}

/** 获取辩论评分 */
export function getDebateScores(sessionId: string) {
  return request.get<DebateScore[]>(`/debate/sessions/${sessionId}/scores`)
}

/** 获取某轮评分 */
export function getDebateScoresByRound(sessionId: string, roundNumber: number) {
  return request.get<DebateScore[]>(`/debate/sessions/${sessionId}/scores/round/${roundNumber}`)
}

/** 触发辩论报告生成 */
export function triggerDebateReport(sessionId: string) {
  return request.post<DebateReport>(`/debate/sessions/${sessionId}/report`)
}

/** 获取辩论报告 */
export function getDebateReport(sessionId: string) {
  return request.get<DebateReport>(`/debate/sessions/${sessionId}/report`)
}

// ==================== SSE 流式发音 ====================

interface SseListeners {
  message: ((text: string) => void)[]
  done: (() => void)[]
  error: ((err: Error) => void)[]
}

function createSseSpeakConnection(
  url: string,
  body: Record<string, unknown>,
) {
  const listeners: SseListeners = { message: [], done: [], error: [] }

  const abortController = createSsePostConnectionWithEvents(
    url,
    body,
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
    onMessage: (handler: (text: string) => void) => { listeners.message.push(handler) },
    onDone: (handler: () => void) => { listeners.done.push(handler) },
    onError: (handler: (err: Error) => void) => { listeners.error.push(handler) },
  }
}

/** 开篇立论 SSE 发音 */
export function streamDebateOpeningSpeech(
  bookId: number,
  roleKey: string,
  sessionId: string,
  roundNumber: number,
) {
  return createSseSpeakConnection(
    `/debate/books/${bookId}/speak/opening`,
    { roleKey, sessionId, roundType: 'OPENING', roundNumber },
  )
}

/** 奇袭攻辩 SSE 发音 */
export function streamDebateAttackSpeech(
  bookId: number,
  roleKey: string,
  sessionId: string,
  roundNumber: number,
  opponentSpeech?: string,
) {
  return createSseSpeakConnection(
    `/debate/books/${bookId}/speak/attack`,
    { roleKey, sessionId, roundType: 'ATTACK', roundNumber, opponentSpeech },
  )
}

/** 自由辩论 SSE 发音 */
export function streamDebateFreeSpeech(
  bookId: number,
  roleKey: string,
  sessionId: string,
  roundNumber: number,
  lastSpeech?: string,
) {
  return createSseSpeakConnection(
    `/debate/books/${bookId}/speak/free`,
    { roleKey, sessionId, roundType: 'FREE', roundNumber, lastSpeech },
  )
}

/** 总结陈词 SSE 发音 */
export function streamDebateClosingSpeech(
  bookId: number,
  roleKey: string,
  sessionId: string,
  roundNumber: number,
) {
  return createSseSpeakConnection(
    `/debate/books/${bookId}/speak/closing`,
    { roleKey, sessionId, roundType: 'CLOSING', roundNumber },
  )
}

/** 交叉质询 SSE 发音 */
export function streamDebateCrossExamSpeech(
  bookId: number,
  roleKey: string,
  sessionId: string,
  roundNumber: number,
  examRole: string,
  defenderOpening?: string,
  questionContent?: string,
) {
  return createSseSpeakConnection(
    `/debate/books/${bookId}/speak/cross-exam`,
    { roleKey, sessionId, roundType: 'CROSS_EXAM', roundNumber, examRole, defenderOpening, questionContent },
  )
}

/** 驳论 SSE 发音 */
export function streamDebateRebuttalSpeech(
  bookId: number,
  roleKey: string,
  sessionId: string,
  roundNumber: number,
  opponentOpening?: string,
  crossExamContext?: string,
) {
  return createSseSpeakConnection(
    `/debate/books/${bookId}/speak/rebuttal`,
    { roleKey, sessionId, roundType: 'REBUTTAL', roundNumber, opponentOpening, crossExamContext },
  )
}

