/**
 * 用真实 SSE 字节流回归「元数据事件混进回答正文」的问题。
 *
 * 直接编译 frontend/src/utils/sse-parser.ts 再 import，测的是线上同一份源码，
 * 不是复制一份逻辑过来。运行：node --test scripts/sse-parser.test.mjs
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'

const require = createRequire(new URL('../frontend/package.json', import.meta.url))
const esbuild = require('esbuild')
const source = readFileSync(new URL('../frontend/src/utils/sse-parser.ts', import.meta.url), 'utf8')
const { code } = esbuild.transformSync(source, { loader: 'ts', format: 'esm', target: 'es2020' })

const tmpDir = mkdtempSync(join(tmpdir(), 'kbook-sse-'))
const modulePath = join(tmpDir, 'sse-parser.mjs')
writeFileSync(modulePath, code)
process.on('exit', () => rmSync(tmpDir, { recursive: true, force: true }))

const { SseParser, isTextEvent } = await import(pathToFileURL(modulePath).href)

/** Spring SseEmitter 的实际输出：event: 后面不带空格，每帧以空行结束。 */
function springFrame(eventName, data) {
  return `event:${eventName}\ndata:${data}\n\n`
}

function feed(chunks) {
  const frames = []
  const parser = new SseParser((eventName, data) => {
    frames.push({ eventName, data })
    return false
  })
  for (const chunk of chunks) parser.append(chunk)
  return frames
}

/** 复刻 createSsePostConnection 兜底处的判断：只有正文事件才累加到回答。 */
function renderAnswer(frames) {
  let answer = ''
  for (const frame of frames) {
    if (frame.eventName === 'done' || frame.data === '[DONE]') break
    if (isTextEvent(frame.eventName)) answer += frame.data
  }
  return answer
}

const fullStream = [
  springFrame('session_id', 'book-1-ab12'),
  springFrame('message', '先说这件事。'),
  springFrame('message', '接着往下说。'),
  springFrame('conversation_id', '12345'),
  springFrame('scale_mode', 'large'),
  springFrame('done', '[DONE]'),
].join('')

test('解析器正确识别 Spring 的事件名与 data', () => {
  const frames = feed([fullStream])
  assert.equal(frames.length, 6)
  assert.deepEqual(frames[3], { eventName: 'conversation_id', data: '12345' })
  assert.deepEqual(frames[4], { eventName: 'scale_mode', data: 'large' })
  assert.equal(frames[1].data, '先说这件事。')
})

test('对话 id 与档位事件都不会落进回答正文', () => {
  const answer = renderAnswer(feed([fullStream]))
  assert.equal(answer, '先说这件事。接着往下说。')
  assert.ok(!answer.includes('12345'))
  assert.ok(!answer.includes('large'))
})

test('一帧被 TCP 切成两半也能正确还原', () => {
  const cut = fullStream.indexOf('12345') + 2
  const frames = feed([fullStream.slice(0, cut), fullStream.slice(cut)])
  assert.deepEqual(frames[3], { eventName: 'conversation_id', data: '12345' })
  assert.equal(renderAnswer(frames), '先说这件事。接着往下说。')
})

test('event 行带空格、无事件名、多行 data 都按 SSE 规范处理', () => {
  const frames = feed([
    'event: conversation_id\ndata: 99\n\n',
    'data:默认事件也是正文\n\n',
    'event:message\ndata:第一行\ndata:第二行\n\n',
  ])
  assert.equal(frames[0].eventName, 'conversation_id')
  assert.equal(frames[1].eventName, '')
  assert.equal(frames[2].data, '第一行\n第二行')
  assert.equal(renderAnswer(frames), '默认事件也是正文第一行\n第二行')
})

test('正文白名单是反过来列的，未知事件名一律不进正文', () => {
  assert.equal(isTextEvent(''), true)
  assert.equal(isTextEvent('message'), true)
  for (const name of ['conversation_id', 'scale_mode', 'session_id', 'thinking', 'retry', 'future_event']) {
    assert.equal(isTextEvent(name), false, `${name} 不能当正文`)
  }
})
