import { getTimeline, type NoteVO } from '@/api/note'
import { NOTE_TYPE_LABEL } from '@/components/note/meta'

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

function noteText(note: NoteVO): string {
  if (note.contentMd?.trim()) return note.contentMd.trim()
  const s = note.snapshot
  if (!s) return ''
  return s.excerpt?.trim() || s.sourceContent?.trim() || s.sourceTitle?.trim() || ''
}

function timestamp(iso: string) {
  return iso.slice(0, 16).replace('T', ' ')
}

function dayTitle(day: string) {
  const [y, m, d] = day.split('-').map(Number)
  const date = new Date(y, m - 1, d)
  return `${y}年${m}月${d}日 ${WEEKDAYS[date.getDay()]}`
}

/** 分页拉取全部笔记（最多 10k 条） */
export async function fetchAllNotes(): Promise<NoteVO[]> {
  const size = 200
  const all: NoteVO[] = []
  let page = 1
  let total = Infinity
  while (all.length < total && page <= 50) {
    const res = await getTimeline({ page, size, sort: 'occurred' })
    all.push(...res.list)
    total = res.total
    if (res.list.length === 0) break
    page++
  }
  return all.sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))
}

export function notesToMarkdown(notes: NoteVO[]): string {
  const lines: string[] = [
    '# 我的笔记',
    '',
    `导出时间：${new Date().toLocaleString('zh-CN')}`,
    `共 ${notes.length} 条`,
    '',
  ]

  let currentDay = ''
  for (const note of notes) {
    const day = note.occurredAt.slice(0, 10)
    if (day !== currentDay) {
      currentDay = day
      lines.push('---', '', `## ${dayTitle(day)}`, '')
    }
    lines.push(`### ${note.title?.trim() || `${NOTE_TYPE_LABEL[note.noteType]}笔记`}`)
    const meta: string[] = [`- 类型：${NOTE_TYPE_LABEL[note.noteType]}`]
    if (note.bookTitle) meta.push(`- 书籍：《${note.bookTitle}》`)
    if ((note.tags ?? []).length) meta.push(`- 标签：${(note.tags ?? []).map((t) => `#${t}`).join(' ')}`)
    meta.push(`- 时间：${timestamp(note.occurredAt)}`)
    if (note.pinned) meta.push('- 已置顶')
    if (note.favorited) meta.push('- 已收藏')
    lines.push(...meta, '')

    const body = noteText(note)
    if (body) lines.push(body, '')
    if (note.snapshot?.authorName || note.snapshot?.sourceTitle) {
      lines.push(
        `> 收录自 ${note.snapshot?.authorName ?? '匿名读者'} 的书评${note.snapshot?.sourceTitle ? `《${note.snapshot.sourceTitle}》` : ''}`,
        ''
      )
    }
  }
  return lines.join('\n')
}

function download(filename: string, content: string, mime: string) {
  const blob = new Blob([content], { type: mime })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/** 导出全部笔记，返回导出条数 */
export async function exportNotes(format: 'md' | 'json'): Promise<number> {
  const notes = await fetchAllNotes()
  const date = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  const suffix = `${date.getFullYear()}${p(date.getMonth() + 1)}${p(date.getDate())}`

  if (format === 'json') {
    download(`kbook-notes-${suffix}.json`, JSON.stringify(notes, null, 2), 'application/json;charset=utf-8')
  } else {
    download(`kbook-notes-${suffix}.md`, notesToMarkdown(notes), 'text/markdown;charset=utf-8')
  }
  return notes.length
}
