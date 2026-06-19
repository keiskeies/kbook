/**
 * 语音服务 — 统一 Azure / 讯飞 / 浏览器兜底
 *
 * 使用方式：
 *   const svc = speechService
 *   await svc.init()                         // 初始化（取 Token / 签名 URL）
 *   svc.speak('你好世界', 'zh-CN-XiaoxiaoNeural', () => { ... })
 *   svc.stop()
 */

import { getAzureToken, getXfyunAuth } from '@/api/speech'
import type { SpeechServiceStatus } from '@/types/speech'

type SpeakCallback = () => void

function clamp(v: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, v))
}

function toBase64(text: string): string {
  const bytes = new TextEncoder().encode(text)
  let binaryStr = ''
  bytes.forEach(b => binaryStr += String.fromCharCode(b))
  return btoa(binaryStr)
}

export class SpeechService {
  private provider: 'azure' | 'xfyun' | 'browser' = 'browser'
  private azureToken: string | null = null
  private azureRegion: string | null = null
  private xfyunAppId: string | null = null
  private xfyunWsBaseUrl: string | null = null  // pattern: wss://tts-api.xfyun.cn/v2/tts?host=...&date=...&authorization=...
  private initPromise: Promise<void> | null = null
  private status: SpeechServiceStatus = 'idle'

  // Azure SDK 实例（懒加载）
  private _azureSdk: any = null

  private async loadAzureSdk(): Promise<any> {
    if (this._azureSdk) return this._azureSdk
    try {
      this._azureSdk = await import('microsoft-cognitiveservices-speech-sdk')
    } catch {
      console.warn('Azure Speech SDK 未安装')
    }
    return this._azureSdk
  }

  private get azureSdk(): any {
    return this._azureSdk
  }

  private currentSynthesizer: any = null
  private currentWs: WebSocket | null = null
  private currentAudioCtx: AudioContext | null = null
  private onEndCallback: SpeakCallback | null = null

  // ─── 初始化 ──────────────────────────────────────────────────

  /**
   * 初始化语音服务 — 优先级：
   *   1. 桌面版 Edge → 直接用浏览器（自带微软神经语音）
   *   2. Azure → 前端 Token 直连
   *   3. 讯飞 → 前端 WebSocket 直连
   *   4. 浏览器兜底
   */
  async init(): Promise<SpeechServiceStatus> {
    if (this.initPromise) return this.initPromise.then(() => this.status)

    this.initPromise = (async () => {
      // 0. 桌面版 Edge 自带微软神经语音，不需要走云 TTS，省钱
      if (/Edg\//.test(navigator.userAgent)) {
        this.provider = 'browser'
        console.log('[Speech] 桌面 Edge，使用本地微软神经语音')
        return
      }

      // 1. 尝试 Azure
      await this.loadAzureSdk()
      if (this.azureSdk) {
        try {
          const { token, region } = await getAzureToken()
          if (token) {
            this.azureToken = token
            this.azureRegion = region
            this.provider = 'azure'
            console.log('[Speech] Azure 初始化成功, region:', region)
            return
          }
        } catch (e) {
          console.log('[Speech] Azure 不可用:', (e as Error).message)
        }
      }

      // 2. 尝试讯飞
      try {
        const { wsUrl, appId } = await getXfyunAuth()
        if (wsUrl) {
          this.xfyunWsBaseUrl = wsUrl
          this.xfyunAppId = appId
          this.provider = 'xfyun'
          console.log('[Speech] 讯飞初始化成功')
          return
        }
      } catch (e) {
        console.log('[Speech] 讯飞不可用:', (e as Error).message)
      }

      // 3. 浏览器兜底
      this.provider = 'browser'
      console.log('[Speech] 使用浏览器 SpeechSynthesis')
    })()

    try {
      await this.initPromise
    } catch {
      this.provider = 'browser'
    }
    return this.provider === 'azure' || this.provider === 'xfyun' ? 'idle' : 'idle'
  }

  // ─── 朗读 ────────────────────────────────────────────────────

  /**
   * 朗读一段文本
   * @param text      - 要朗读的文本
   * @param voiceName - 音色名
   * @param rate      - 语速 (0.5~2.0, 默认 1.0)
   * @param pitch     - 音调 (0.5~2.0, 默认 1.0)
   * @param onEnd     - 朗读完成回调
   */
  speak(text: string, voiceName: string, rate = 1.0, pitch = 1.0, onEnd?: SpeakCallback): void {
    rate = clamp(rate, 0.5, 2.0)
    pitch = clamp(pitch, 0.5, 2.0)

    if (!text.trim()) {
      onEnd?.()
      return
    }

    this.onEndCallback = onEnd || null

    switch (this.provider) {
      case 'azure':
        this.speakAzure(text, voiceName, rate, pitch)
        break
      case 'xfyun':
        this.speakXfyun(text, voiceName, rate, pitch)
        break
      default:
        this.speakBrowser(text, rate, pitch)
    }
  }

  /** 停止朗读 */
  stop(): void {
    if (this.currentSynthesizer) {
      try { this.currentSynthesizer.close() } catch {}
      this.currentSynthesizer = null
    }
    if (this.currentWs) {
      this.currentWs.close()
      this.currentWs = null
    }
    if (this.currentAudioCtx) {
      this.currentAudioCtx.close()
      this.currentAudioCtx = null
    }
    if (this.provider === 'browser' && window.speechSynthesis) {
      window.speechSynthesis.cancel()
    }
    this.status = 'idle'
  }

  get isSpeaking(): boolean {
    return this.status === 'speaking'
  }

  get activeProvider(): string {
    return this.provider
  }

  // ─── Azure 实现 ──────────────────────────────────────────────

  private speakAzure(text: string, voiceName: string, rate: number, pitch: number): void {
    if (!this.azureToken || !this.azureRegion || !this.azureSdk) {
      this.speakBrowser(text, rate, pitch)
      return
    }

    this.status = 'speaking'

    const SpeechConfig = this.azureSdk.SpeechConfig
    const SpeechSynthesizer = this.azureSdk.SpeechSynthesizer

    const speechConfig = SpeechConfig.fromAuthorizationToken(this.azureToken, this.azureRegion)
    speechConfig.speechSynthesisVoiceName = voiceName || 'zh-CN-XiaoxiaoNeural'
    console.log('[TTS] Azure voice:', speechConfig.speechSynthesisVoiceName)

    this.currentSynthesizer = new SpeechSynthesizer(speechConfig)

    this.currentSynthesizer.speakTextAsync(
      text,
      () => {
        this.currentSynthesizer?.close()
        this.currentSynthesizer = null
        this.status = 'idle'
        this.onEndCallback?.()
      },
      (err: any) => {
        console.warn('[Speech] Azure 合成失败:', err)
        this.currentSynthesizer?.close()
        this.currentSynthesizer = null
        this.speakBrowser(text, rate, pitch)
      },
    )
  }

  // ─── 讯飞 WebSocket 实现 ──────────────────────────────────────

  private speakXfyun(text: string, voiceName: string, rate: number, pitch: number): void {
    if (!this.xfyunWsBaseUrl || !this.xfyunAppId) {
      this.speakBrowser(text, rate, pitch)
      return
    }

    this.status = 'speaking'

    const ws = new WebSocket(this.xfyunWsBaseUrl)
    this.currentWs = ws

    ws.onopen = () => {
      const params = {
        common: { app_id: this.xfyunAppId },
        business: {
          aue: 'raw',
          auf: 'audio/L16;rate=16000',
          vcn: voiceName || 'xiaoyan',
          speed: 50,
          volume: 50,
          pitch: 50,
          tte: 'utf8',
        },
        data: {
          status: 2, // 一次性合成
          text: toBase64(text),
        },
      }
      ws.send(JSON.stringify(params))
    }

    ws.onmessage = (event) => {
      try {
        const res = JSON.parse(event.data)
        if (res.code !== 0) {
          console.warn('[Speech] 讯飞合成失败:', res.message)
          ws.close()
          this.speakBrowser(text, rate, pitch)
          return
        }
        if (res.data?.audio) {
          this.playPcmAudio(res.data.audio)
        }
        if (res.data?.status === 2) {
          ws.close()
        }
      } catch {
        // 非 JSON 数据，忽略
      }
    }

    ws.onclose = () => {
      this.currentWs = null
      this.status = 'idle'
      this.onEndCallback?.()
    }

    ws.onerror = () => {
      ws.close()
      this.currentWs = null
      this.speakBrowser(text, rate, pitch)
    }
  }

  // ─── 浏览器兜底 ──────────────────────────────────────────────

  private speakBrowser(text: string, rate: number, pitch: number): void {
    const synth = window.speechSynthesis
    if (!synth) {
      this.onEndCallback?.()
      return
    }

    synth.cancel()
    this.status = 'speaking'

    const utterance = new SpeechSynthesisUtterance(text)
    utterance.lang = 'zh-CN'
    utterance.rate = rate
    utterance.pitch = pitch

    // Edge 浏览器需要显式设置 voice，否则可能无声
    const voices = synth.getVoices()
    const zhVoice = voices.find(v => {
      const lang = (v.lang || '').toLowerCase()
      return (lang.startsWith('zh-cn') || lang.startsWith('cmn')) && !v.localService
    }) || voices.find(v => {
      const lang = (v.lang || '').toLowerCase()
      return lang.startsWith('zh-cn') || lang.startsWith('cmn')
    })
    if (zhVoice) {
      utterance.voice = zhVoice
      console.log('[TTS] Browser voice:', zhVoice.name, '| lang:', zhVoice.lang, '| local:', zhVoice.localService)
    } else {
      console.log('[TTS] Browser: 未找到中文语音, 用系统默认')
    }

    utterance.onend = () => {
      this.status = 'idle'
      this.onEndCallback?.()
    }
    utterance.onerror = () => {
      this.status = 'idle'
      this.onEndCallback?.()
    }

    synth.speak(utterance)
  }

  // ─── PCM 播放 ────────────────────────────────────────────────

  private playPcmAudio(base64Audio: string): void {
    try {
      const binaryStr = atob(base64Audio)
      const bytes = new Uint8Array(binaryStr.length)
      for (let i = 0; i < binaryStr.length; i++) bytes[i] = binaryStr.charCodeAt(i)

      const int16 = new Int16Array(bytes.buffer)
      const float32 = new Float32Array(int16.length)
      for (let i = 0; i < int16.length; i++) float32[i] = int16[i] / 32768

      const ctx = this.currentAudioCtx || new AudioContext({ sampleRate: 16000 })
      this.currentAudioCtx = ctx
      if (ctx.state === 'suspended') ctx.resume()

      const buffer = ctx.createBuffer(1, float32.length, 16000)
      buffer.getChannelData(0).set(float32)

      const source = ctx.createBufferSource()
      source.buffer = buffer
      source.connect(ctx.destination)
      source.start()
    } catch (e) {
      console.warn('[Speech] PCM 播放失败:', e)
    }
  }
}

export const speechService = new SpeechService()
