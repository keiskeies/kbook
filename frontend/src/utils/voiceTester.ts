/**
 * 语音发声检测工具 — Edge 浏览器 TTS 音色静音检测
 *
 * 原理（时长试探法）：
 *   SpeechSynthesis API 不暴露音频数据，无法直接判断 voice 是否发声。
 *   我们用一段极短文本（"啊"）朗读该 voice，测量 onstart → onend 耗时。
 *   - 正常发声：朗读一个汉字约需 200-600ms
 *   - 无声：onstart 后几乎立即 onend（< 120ms），说明没有实际语音输出
 *
 * 使用方式：
 *   const tester = new VoiceTester()
 *   const hasSound = await tester.testVoice(voice)   // 测试单个
 *   const working = await tester.filterWorking(voices) // 批量过滤
 *   tester.getStatus(voiceName)  // 查询缓存结果
 */

/** 测试用的极短文本 */
const TEST_TEXT = '啊'

/** 无声判定阈值（毫秒）：朗读"啊"快于这个时间视为无声 */
const SILENCE_THRESHOLD_MS = 120

/** 批量测试并发数（串行执行，一次只测一个） */
const BATCH_CONCURRENCY = 1

export type VoiceTestStatus = 'untested' | 'has_sound' | 'silent' | 'testing'

interface TestResult {
  status: VoiceTestStatus
  durationMs?: number
  testedAt?: number
}

/**
 * 语音发声检测器
 *
 * 对每个 voiceName 只测一次，结果缓存整个会话期。
 * 串行测试，避免浏览器 SpeechSynthesis 队列冲突。
 */
export class VoiceTester {
  private cache = new Map<string, TestResult>()
  private testQueue: SpeechSynthesisVoice[] = []
  private processing = false

  /** 测试单个语音是否发声，返回 true=有声音 / false=无声 / null=无法测试 */
  async testVoice(voice: SpeechSynthesisVoice): Promise<boolean | null> {
    const existing = this.cache.get(voice.name)
    if (existing && existing.status !== 'testing') {
      return existing.status === 'has_sound'
    }

    return new Promise((resolve) => {
      const synth = window.speechSynthesis
      if (!synth) {
        this.cache.set(voice.name, { status: 'has_sound' }) // 兜底：假设有声
        resolve(null)
        return
      }

      this.cache.set(voice.name, { status: 'testing' })

      // 先 cancel 避免队列堆积
      try { synth.cancel() } catch { /* ignore */ }

      const utterance = new SpeechSynthesisUtterance(TEST_TEXT)
      utterance.voice = voice
      utterance.lang = voice.lang
      utterance.rate = 1.0
      utterance.pitch = 1.0
      utterance.volume = 1.0

      const startTime = performance.now()
      let ended = false

      utterance.onstart = () => {
        // 只是标记起始时间 (start 回调时已经算开始)
      }

      utterance.onend = () => {
        if (ended) return
        ended = true
        const duration = performance.now() - startTime
        const hasSound = duration > SILENCE_THRESHOLD_MS
        this.cache.set(voice.name, {
          status: hasSound ? 'has_sound' : 'silent',
          durationMs: Math.round(duration),
          testedAt: Date.now(),
        })
        resolve(hasSound)
      }

      utterance.onerror = () => {
        if (ended) return
        ended = true
        // 出错可能意味着该语音不可用，标记为无声
        this.cache.set(voice.name, {
          status: 'silent',
          durationMs: 0,
          testedAt: Date.now(),
        })
        resolve(false)
      }

      try {
        synth.speak(utterance)
      } catch {
        // speak 失败 → 标记无声
        this.cache.set(voice.name, { status: 'silent', durationMs: 0, testedAt: Date.now() })
        resolve(false)
      }
    })
  }

  /**
   * 批量测试，只返回有声音的语音列表
   * 串行执行，每次只测一个，避免浏览器队列冲突。
   */
  async filterWorking(voices: SpeechSynthesisVoice[]): Promise<SpeechSynthesisVoice[]> {
    // 先从缓存中筛出已知结果
    const cached: SpeechSynthesisVoice[] = []
    const untested: SpeechSynthesisVoice[] = []

    for (const v of voices) {
      const existing = this.cache.get(v.name)
      if (existing?.status === 'has_sound') cached.push(v)
      else if (existing?.status !== 'silent') untested.push(v)
    }

    if (untested.length === 0) return cached

    // 串行测试未测过的
    for (const voice of untested) {
      await this.testVoice(voice)
      const result = this.cache.get(voice.name)
      if (result?.status === 'has_sound') cached.push(voice)
    }

    return cached
  }

  /** 查询某个语音的测试状态 */
  getStatus(voiceName: string): VoiceTestStatus {
    return this.cache.get(voiceName)?.status ?? 'untested'
  }

  /** 获取测试详情（含耗时） */
  getDetail(voiceName: string): TestResult | undefined {
    return this.cache.get(voiceName)
  }

  /** 所有已测试的语音名列表 */
  get testedNames(): string[] {
    return Array.from(this.cache.keys())
  }

  /** 已知静音的语音名列表 */
  get silentNames(): string[] {
    return Array.from(this.cache.entries())
      .filter(([_, r]) => r.status === 'silent')
      .map(([name]) => name)
  }

  /** 清除缓存（强制重测） */
  clearCache() {
    this.cache.clear()
  }

  /** 手动标记某个语音为无声（由外部实时检测触发） */
  markSilent(voiceName: string) {
    this.cache.set(voiceName, {
      status: 'silent',
      durationMs: 0,
      testedAt: Date.now(),
    })
  }

  /** 手动标记某个语音为有声 */
  markHasSound(voiceName: string) {
    this.cache.set(voiceName, {
      status: 'has_sound',
      testedAt: Date.now(),
    })
  }
}

/** 单例导出 */
export const voiceTester = new VoiceTester()
