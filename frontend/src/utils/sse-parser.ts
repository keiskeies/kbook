/**
 * 最小 SSE 帧解析器。只处理字符串，不碰任何浏览器 API，方便用真实字节流做回归测试。
 *
 * 事件以空行分隔。event 行的值允许带或不带前导空格，Spring SseEmitter 输出的是
 * `event:conversation_id` 这种不带空格的形式。多行 data 用换行连接。
 */
export class SseParser {
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

/**
 * 只有这两种事件名承载回答正文：无事件名的默认事件，以及后端显式命名的 message。
 *
 * 反过来列白名单，而不是列「已知元数据事件」黑名单，是因为后端随时可能新增事件。
 * 旧版前端遇到不认识的事件名时，宁可丢掉也不能把它当回答内容显示出来。
 */
export function isTextEvent(eventName: string): boolean {
  return eventName === '' || eventName === 'message'
}
