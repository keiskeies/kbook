import type { CaptureMode, NoteType } from '@/api/note'

export const NOTE_TYPE_LABEL: Record<NoteType, string> = {
  THOUGHT: '想法',
  EXCERPT: '摘录',
  REVIEW_CAPTURE: '收录书评',
  CONVERSATION: '对话',
}

export const CAPTURE_MODE_LABEL: Record<CaptureMode, string> = {
  FULL: '全文',
  EXCERPT: '摘录',
  REFERENCE: '引用',
}
