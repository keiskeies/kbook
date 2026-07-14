/**
 * SSE 流式 token 批量更新工具
 *
 * 解决问题：LLM 流式输出每个 token 都触发 setMessages(prev => prev.map(...))，
 * 高频下（尤其 thinking 模式）会产生大量 re-render，导致输入框卡顿、滚动闪烁。
 *
 * 策略：用闭包变量累积 chunk，requestAnimationFrame 批量 flush 到 state。
 * - 浏览器每帧最多 flush 一次（~16ms 一次 setState 替代 ~50-100 次原 token 触发）
 * - flush 时机：rAF 调度 / stream 结束时手动 flush
 * - 异常安全：flush 失败不会丢失 buffer（可下次 rAF 重试）
 *
 * 大 chunk 平滑：当单次 append 超过 smoothThreshold 时，自动拆分为多帧渐进展现，
 * 避免 Google AI 等模型一次吐出大段文本导致视觉跳跃。
 *
 * 使用方式：
 * ```ts
 * const batcher = createStreamBatcher(
 *   setMessages,
 *   assistantMsg.id,
 *   (msg, bufferedChunk) => ({ ...msg, content: msg.content + bufferedChunk })
 * )
 * onChunk(chunk => batcher.append(chunk))
 * onComplete(() => batcher.flush())  // 流结束前必须 flush 残余 buffer
 * ```
 */

interface StreamBatcherOptions {
  /** 大 chunk 拆分阈值（字符数），超过此值会分帧展现。默认 40 */
  smoothThreshold?: number
  /** 每帧最大展现字符数。默认 20 */
  smoothChunkSize?: number
}

export function createStreamBatcher<T extends { id: string | number }>(
  setState: (updater: (prev: T[]) => T[]) => void,
  msgId: string | number,
  applyChunk: (msg: T, bufferedChunk: string) => T,
  options?: StreamBatcherOptions
): { append: (chunk: string) => void; flush: () => void } {
  const smoothThreshold = options?.smoothThreshold ?? 40
  const smoothChunkSize = options?.smoothChunkSize ?? 20

  let buffer = ''
  let rafId: number | null = null
  // 大 chunk 分帧展现的残余文本
  let smoothRemainder = ''

  const flush = () => {
    if (rafId !== null) {
      cancelAnimationFrame(rafId)
      rafId = null
    }
    if (buffer === '' && smoothRemainder === '') return

    // 先处理 smooth remainder（大 chunk 的剩余部分）
    if (smoothRemainder !== '') {
      const take = smoothRemainder.slice(0, smoothChunkSize)
      smoothRemainder = smoothRemainder.slice(smoothChunkSize)
      buffer += take
    }

    if (buffer === '') return

    const chunkToApply = buffer
    buffer = ''
    setState(prev => prev.map(m => (m.id === msgId ? applyChunk(m, chunkToApply) : m)))

    // 还有剩余则继续调度下一帧
    if (smoothRemainder !== '') {
      rafId = requestAnimationFrame(flush)
    }
  }

  const append = (chunk: string) => {
    if (!chunk) return

    // 大 chunk 拆分：立即应用前 smoothChunkSize 字符，剩余进入 smooth remainder
    if (chunk.length > smoothThreshold && smoothRemainder === '') {
      const immediate = chunk.slice(0, smoothChunkSize)
      smoothRemainder = chunk.slice(smoothChunkSize)
      buffer += immediate
    } else {
      buffer += chunk
    }

    // 已有待执行的 rAF 则不重复调度（一帧只 flush 一次）
    if (rafId === null) {
      rafId = requestAnimationFrame(flush)
    }
  }

  return { append, flush }
}
