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
    return lang.startsWith('zh') || lang.startsWith('cmn')
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
