/**
 * Edge 浏览器 TTS 多音色支持工具
 *
 * Edge (Chromium) 内置多个 Microsoft 神经网络中文语音，名称格式为
 * "Microsoft XXX - Chinese (Simplified, PRC)" 之类，但具体名称因系统版本、
 * 语言包、是否在线而异。
 *
 * 本模块**不硬编码任何语音名称**，而是通过以下动态策略利用 Edge 多音色：
 *
 *   1. 筛选中文云端语音（localService === false）→ 这些就是 Microsoft Neural TTS
 *   2. 按名称字母序排序 → 分配稳定、可复现
 *   3. 每个角色通过 key hash 映射到不同的云端语音 → 真实音色差异
 *
 * 非 Edge 回退：hash 分配到全部中文语音（云端 + 本地），行为同原有逻辑。
 *
 * ⚠️ 无声语音检测：
 *   部分语音（尤其是 localService=true 的本地语音）可能未安装语音包而无法发声。
 *   使用 voiceTester 模块通过"时长试探法"自动检测并排除无声语音。
 */

/** 检测是否为 Edge 浏览器（含桌面版/Android/iOS） */
export function isEdgeBrowser(): boolean {
  if (typeof navigator === 'undefined') return false
  const ua = navigator.userAgent
  // Edg/  → 桌面版, EdgA/ → Android, EdgiOS/ → iOS
  return /Edg/.test(ua)
}

/** 获取 Edge 变体标识，用于调试 */
export function getEdgeVariant(): string | null {
  if (typeof navigator === 'undefined') return null
  const ua = navigator.userAgent
  if (/EdgiOS/.test(ua)) return 'Edge iOS'
  if (/EdgA/.test(ua)) return 'Edge Android'
  if (/Edg\//.test(ua)) return 'Edge Desktop'
  return null
}

/**
 * 获取已排序的中文语音列表
 *
 * Edge 策略：云端语音 (localService=false) 优先，
 *           然后按 name 字母序排序，确保每次结果一致。
 * 非 Edge：按 name 字母序排序全部中文语音。
 */
export function getSortedChineseVoices(synth: SpeechSynthesis): SpeechSynthesisVoice[] {
  const allVoices = synth.getVoices()
  const chineseVoices = allVoices.filter(v => {
    const lang = (v.lang || '').toLowerCase()
    if (!(lang.startsWith('zh') || lang.startsWith('cmn'))) return false
    // 排除粤语/台湾腔
    if (lang.includes('-hk') || lang.includes('-tw') || lang.includes('-yue')) return false
    return true
  })
  if (chineseVoices.length === 0) return []

  if (isEdgeBrowser()) {
    // 云端神经语音优先，再按 name 排序
    return [...chineseVoices].sort((a, b) => {
      if (a.localService !== b.localService) return a.localService ? 1 : -1
      return a.name.localeCompare(b.name)
    })
  }

  return [...chineseVoices].sort((a, b) => a.name.localeCompare(b.name))
}

/**
 * 为角色分配中文语音
 *
 * 策略：使用角色 key 的 hash 映射到可用语音列表中的一个。
 * 同一角色每次会话获得相同语音；不同角色有概率被分配到不同语音。
 *
 * @param roleKey   - 角色 key（如 'HOST', 'PRO_1', 'PHILOSOPHER'）
 * @param voiceCache - 角色→语音 缓存 Map（函数内部写入，后续调用直接返回缓存值）
 * @param zhVoices  - 来自 getSortedChineseVoices() 的已排序列表
 * @returns 分配的 SpeechSynthesisVoice，zhVoices 为空时返回 null
 */
export function assignVoiceForRole(
  roleKey: string,
  voiceCache: Map<string, SpeechSynthesisVoice>,
  zhVoices: SpeechSynthesisVoice[],
): SpeechSynthesisVoice | null {
  if (zhVoices.length === 0) return null

  const cached = voiceCache.get(roleKey)
  if (cached) return cached

  const voice = zhVoices[hashStr(roleKey) % zhVoices.length]
  voiceCache.set(roleKey, voice)
  return voice
}

function hashStr(s: string): number {
  let hash = 0
  for (let i = 0; i < s.length; i++)
    hash = ((hash << 5) - hash + s.charCodeAt(i)) | 0
  return Math.abs(hash)
}

// ─── 无声语音检测集成 ──────────────────────────────────────────

import { voiceTester } from './voiceTester'

/**
 * 返回有声音的中文语音列表
 *
 * 在 getSortedChineseVoices 的基础上，进一步用 VoiceTester 过滤掉
 * 实际不会发声的静音语音（如未安装语言包的本地语音）。
 *
 * @param synth - window.speechSynthesis 实例
 * @returns 经过声音测试过滤的中文语音列表
 */
export async function getWorkingChineseVoices(
  synth: SpeechSynthesis,
): Promise<SpeechSynthesisVoice[]> {
  const sorted = getSortedChineseVoices(synth)
  if (sorted.length === 0) return []

  // 先用 getVoices 的最新状态给 voiceTester 预热
  return voiceTester.filterWorking(sorted)
}

/**
 * 角色分配语音时优先排除已知静音语音
 *
 * 在 assignVoiceForRole 的基础上，确保不会被分配到 silent 的语音。
 * 如果可用语音列表中有部分语音已标记为 silent，会将其排除后重新分配。
 *
 * @param roleKey - 角色 key
 * @param voiceCache - 角色→语音 缓存 Map
 * @param zhVoices - 来自 getSortedChineseVoices() 的列表
 * @param excludeSilent - 是否排除已知静音语音（默认 true）
 */
export function assignVoiceForRoleSafe(
  roleKey: string,
  voiceCache: Map<string, SpeechSynthesisVoice>,
  zhVoices: SpeechSynthesisVoice[],
  excludeSilent = true,
): SpeechSynthesisVoice | null {
  if (zhVoices.length === 0) return null

  if (!excludeSilent) {
    return assignVoiceForRole(roleKey, voiceCache, zhVoices)
  }

  // 过滤掉已知静音的语音
  const working = zhVoices.filter(v => voiceTester.getStatus(v.name) !== 'silent')
  if (working.length === 0) {
    // 全都未知 → 用原列表（至少尝试发声）
    return assignVoiceForRole(roleKey, voiceCache, zhVoices)
  }
  return assignVoiceForRole(roleKey, voiceCache, working)
}

/**
 * 轮询获取浏览器语音列表
 *
 * 浏览器提供的 SpeechSynthesis.onvoiceschanged 事件在各平台可靠性不一，
 * 某些情况下语音列表加载较慢或需要反复调用 getVoices() 才能获取完整列表。
 * 本函数提供一个可取消的轮询机制。
 *
 * @param onVoices - 每次获取到语音列表时的回调
 * @param intervalMs - 轮询间隔（默认 3 秒）
 * @param maxRetries - 最大轮询次数（默认 10 次 = 30 秒，0=不限）
 * @returns 取消函数
 */
export function startVoicePolling(
  onVoices: (voices: SpeechSynthesisVoice[]) => void,
  intervalMs = 3000,
  maxRetries = 10,
): () => void {
  let retries = 0
  let timer: ReturnType<typeof setInterval> | null = null
  let stopped = false

  const poll = () => {
    if (stopped) return
    if (maxRetries > 0 && retries >= maxRetries) {
      stop()
      return
    }
    retries++

    const synth = window.speechSynthesis
    if (!synth) return

    const voices = synth.getVoices()
    if (voices.length > 0) {
      onVoices(voices)
      // 一旦拿到语音就停止轮询（除非语音为空才会继续）
      stop()
    }
  }

  const stop = () => {
    stopped = true
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  // 立即执行一次
  poll()

  // 如果没有拿到语音，启动定时轮询
  if (!stopped) {
    timer = setInterval(poll, intervalMs)
  }

  return stop
}

export type { VoiceTestStatus } from './voiceTester'
