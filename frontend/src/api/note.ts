import request from '@/utils/request'
import type { PageResult } from '@/types/common'

export type NoteType = 'THOUGHT' | 'EXCERPT' | 'REVIEW_CAPTURE' | 'CONVERSATION'
export type NoteSourceType = 'MANUAL' | 'REVIEW' | 'COMMENT' | 'AI_CHAT' | 'ROUND_TABLE'
export type SourceStatus = 'ACTIVE' | 'DELETED'
export type CaptureMode = 'FULL' | 'EXCERPT' | 'REFERENCE'

export interface NoteSourceSnapshot {
  authorId: number | null
  authorName: string | null
  authorAvatar: string | null
  sourceTitle: string | null
  sourceContent: string | null
  rating: number | null
  isSpoiler: boolean | null
  excerpt: string | null
  sourceCreatedAt: string | null
}

export interface NoteVO {
  id: number
  title: string | null
  contentMd: string | null
  noteType: NoteType
  sourceType: NoteSourceType
  sourceId: number | null
  sourceStatus: SourceStatus
  snapshot: NoteSourceSnapshot | null
  captureMode: CaptureMode | null
  bookId: number | null
  bookTitle: string | null
  bookCover: string | null
  occurredAt: string
  version: number
  createdAt: string
  updatedAt: string
}

export interface NotePayload {
  title?: string | null
  contentMd?: string | null
  occurredAt?: string | null
}

export interface NoteRevisionVO {
  id: number
  revisionNo: number
  title: string | null
  contentMd: string | null
  occurredAt: string | null
  editedAt: string | null
}

export interface NoteInsightVO {
  id: number
  periodStart: string
  periodEnd: string
  title: string | null
  contentMd: string | null
  noteCount: number | null
  createdAt: string
}

export interface NoteStatsVO {
  total: number
  weekCount: number
  bookCount: number
  streakDays: number
  thoughtCount: number
  captureCount: number
  excerptCount: number
}

export interface CapturePayload {
  mode: CaptureMode
  excerpt?: string | null
  myThought?: string | null
}

export interface TimelineParams {
  type?: NoteType
  bookId?: number
  from?: string
  to?: string
  page?: number
  size?: number
}

export function createNote(payload: NotePayload) {
  return request.post<NoteVO>('/notes', payload)
}

export function getTimeline(params: TimelineParams) {
  return request.get<PageResult<NoteVO>>('/notes/timeline', { params })
}

export function countNotes() {
  return request.get<number>('/notes/count')
}

export function getNoteStats() {
  return request.get<NoteStatsVO>('/notes/stats')
}

export function getThrowback() {
  return request.get<NoteVO | null>('/notes/throwback')
}

export function searchNotes(keyword: string, page = 1, size = 20) {
  return request.get<PageResult<NoteVO>>('/notes/search', { params: { keyword, page, size } })
}

export function semanticSearchNotes(q: string, limit = 20) {
  return request.get<NoteVO[]>('/notes/search/semantic', { params: { q, limit } })
}

export function generateInsight(payload: { from?: string | null; to?: string | null } = {}) {
  return request.post<NoteInsightVO>('/notes/insights', payload)
}

export function listInsights() {
  return request.get<NoteInsightVO[]>('/notes/insights')
}

export function getRevisions(noteId: number) {
  return request.get<NoteRevisionVO[]>(`/notes/${noteId}/revisions`)
}

export function captureReview(reviewId: number, payload: CapturePayload) {
  return request.post<NoteVO>(`/notes/capture/review/${reviewId}`, payload)
}

export function getNote(noteId: number) {
  return request.get<NoteVO>(`/notes/${noteId}`)
}

export function updateNote(noteId: number, payload: NotePayload) {
  return request.put<NoteVO>(`/notes/${noteId}`, payload)
}

export function deleteNote(noteId: number) {
  return request.delete<void>(`/notes/${noteId}`)
}
