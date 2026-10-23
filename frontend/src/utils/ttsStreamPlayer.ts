import {
  buildTtsStreamUrl,
  prepareTtsStream,
  releaseTtsStream,
} from '@/api/adminTts'

/**
 * 播放进度快照
 */
export interface PlaybackProgress {
  /** 当前播放位置（秒） */
  currentTime: number
  /** 已知总时长（秒）；渐进式流在下载完成前会逐步增长，未知时为 0 */
  duration: number
  /** 是否处于播放中 */
  playing: boolean
  /**
   * 当前是否支持精确定位（拖动跳转）
   * <p>
   * 渐进式流在下载完成前只缓冲了很小一段，此时拖动会失败；
   * 界面据此决定显示可拖动滑块还是只读进度条。
   */
  seekable: boolean
}

/**
 * 播放元信息，用于系统媒体控制（锁屏 / 通知栏 / 耳机按键）
 */
export interface PlaybackMetadata {
  /** 主标题，通常为书名 */
  title: string
  /** 副标题，通常为「图书问答」或章节名 */
  artist?: string
  /** 专辑名，通常为书名 */
  album?: string
  /** 封面图地址 */
  artworkUrl?: string
}

/**
 * 播放器对外回调
 */
export interface PlaybackCallbacks {
  /** 整篇播放完毕 */
  onEnd?: () => void
  /** 发生了无法自动恢复的错误 */
  onError?: (error: Error) => void
  /** 播放被系统中断且自动恢复失败（来电、其它 App 抢占音频焦点等） */
  onInterrupted?: () => void
  /** 由系统媒体控制（锁屏 / 耳机）要求彻底停止，播放已结束、无法原地继续 */
  onStoppedBySystem?: () => void
  /** 进度更新 */
  onProgress?: (progress: PlaybackProgress) => void
}

/** 进度轮询间隔 */
const PROGRESS_TICK_MS = 500

/**
 * 语速标定：小米 MiMo TTS（MPEG2 Layer III @24kHz 单声道）实测
 * 3.84 汉字/秒（浏览器解码器实测 41/82/123/164 汉字 → 10.73/23.00/31.04/41.94 秒，
 * 加权平均 3.842，单样本区间 3.57~3.96）。
 * <p>
 * 以「汉字数」而非总字符数计算：标点、换行、Markdown 符号不发声，
 * 长回答里这类字符占比可达 20%，按总字符数估算会把总时长明显高估。
 * <p>
 * 仅用于渐进式流下载完成前估算总时长。一旦服务端返回完整文件，
 * 浏览器报告的时长就是精确值，不再使用估算。
 */
const HAN_PER_SECOND = 3.84

/** 汉字区间，用于统计真正会被读出来的字符 */
const HAN_PATTERN = /[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/g

/** 单次估算的最短时长，避免极短文本算出 0 导致进度条除零 */
const MIN_ESTIMATED_DURATION = 0.5

/**
 * 等待完整文件模式的首字节上限
 * <p>
 * 服务端补齐全部分段可能需要十几秒（分段数 × 单段合成耗时）。超过此值
 * 仍无首字节，说明链路异常，解除保护让看门狗按正常逻辑重建。
 */
const COMPLETE_WAIT_TIMEOUT_MS = 45_000

/**
 * 判定卡死的阈值：播放中位置连续这么久没有前进，且缓冲区不足，
 * 认为媒体管线已经停滞，需要重建连接。
 * <p>
 * 取值权衡：太短会在弱网下频繁重建（每次重建都要重新请求），
 * 太长则用户要忍受明显的静默。6 秒是「用户能察觉但不会以为坏了」的边界。
 */
const STALL_RECOVER_MS = 6_000

/**
 * 播放中位置不前进但缓冲区充足，说明元素表面在「播放」实则没有输出，
 * 这种情况只需重新调用 play() 唤醒，代价极低，因此阈值可以更短。
 */
const SILENT_RECOVER_MS = 3_000

/**
 * 唤醒后给播放器的宽限期：这段时间内位置仍不前进就重建连接。
 * 单纯 play() 无法解除「在播但无输出」的僵死状态，宽限期不宜过长。
 */
const WAKE_GRACE_MS = 2_000

/**
 * 自动重建连接的最大次数
 * <p>
 * 移动端网络抖动、地铁进隧道、Wi-Fi 切蜂窝都可能造成几秒到几十秒的断流。
 * 若只给 3 次机会且间隔很短，总容忍时间不到 6 秒，用户在弱网下几乎必然
 * 被判定失败。因此放宽到 6 次，配合下面的指数退避覆盖约 2 分钟。
 */
const MAX_RECOVER_ATTEMPTS = 6

/**
 * 重建连接后的退避间隔（毫秒）
 * <p>
 * 递增而非固定：短间隔能快速消化瞬时抖动，长间隔给网络真正恢复留出时间。
 * 累计容忍时长约 2 分钟，超过后才向上层报错。
 */
const RESUME_RETRY_DELAYS = [500, 1_500, 4_000, 10_000, 25_000, 60_000]

/** 用于解锁音频元素的静音音频（20ms 静音 WAV），首次用户手势时播放一次 */
const SILENT_WAV =
  'data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEAgD4AAAB9AAACABAAZGF0YQAAAAA='

/**
 * 长文本朗读播放内核
 * <p>
 * 设计要点：
 * <ol>
 *   <li><b>单一常驻 audio 元素</b>。整篇文本在后端被合成为一条连续 MP3 流，
 *       浏览器把它当作普通媒体文件播放，因此锁屏、后台、系统媒体键、耳机按键
 *       全部由平台原生承担，不需要 JS 串联分片。</li>
 *   <li><b>不依赖单个结束事件</b>。旧实现只在 audio 的 ended 事件上推进，
 *       任何中途打断都会让整条链路静默死亡。这里改为持续监控「播放位置是否前进」，
 *       停滞即自动重建连接并从断点续播。</li>
 *   <li><b>断点续播</b>。首次请求是渐进式流（不可 seek），重连时服务端已有分段缓存，
 *       会返回可 Range 定位的完整文件，因此能精确跳回中断位置。</li>
 *   <li><b>诚实的状态</b>。被系统中断且自动恢复失败时，明确回调 onInterrupted，
 *       让界面显示「已暂停」而不是假装还在朗读。</li>
 * </ol>
 */
export class ProgressiveStreamPlayer {
  /** 常驻 audio 元素，全生命周期只创建一次 */
  private audio: HTMLAudioElement | null = null

  /** 当前播放凭据 */
  private ticketId: string | null = null

  /** 当前凭据对应的文本 */
  private text = ''

  /** 当前配置ID */
  private configId: number | undefined

  /** 播放元信息 */
  private metadata: PlaybackMetadata | null = null

  /** 回调集合 */
  private callbacks: PlaybackCallbacks = {}

  /** 代际计数，用于作废所有旧回调与定时器 */
  private generation = 0

  /** 是否已完成音频解锁（iOS 要求在用户手势内激活过一次） */
  private unlocked = false

  /** 期望状态：true 表示希望它一直在播 */
  private intentPlaying = false

  /** 是否已停止（区分「暂停」与「结束/取消」） */
  private stopped = false

  /** 进度轮询定时器 */
  private monitorTimer: ReturnType<typeof setInterval> | null = null

  /** 已尝试的自动重连次数 */
  private recoverAttempts = 0

  /** 本轮停滞首次尝试唤醒的时间戳；0 表示尚未唤醒 */
  private wakeAttemptedAt = 0

  /** 上次观察到位置前进的时间戳 */
  private lastAdvanceAt = 0

  /** 上次观察到的播放位置 */
  private lastCurrentTime = 0

  /**
   * 高水位标记：本次播放实际到达过的最远位置。
   * <p>
   * 恢复时必须用它而不是读取 audio.currentTime —— 加载失败或换源会把
   * currentTime 重置为 0，那时再读就只能从头重播，用户会听到内容倒退。
   */
  private highWaterTime = 0

  /**
   * 预估总时长（秒）
   * <p>
   * 渐进式流在下载完成前，浏览器报告的 duration 只等于「已缓冲到的位置」，
   * 界面若直接用它当总长，进度条会一直显示成「快播完了」，而且无法拖到
   * 还没缓冲的地方。因此按文本长度与语速估算总时长，仅在真实时长已知且
   * 更大时才采用真实值。
   */
  private estimatedDuration = 0

  /** 恢复用的定时器集合，取消时统一清理 */
  private pendingTimers = new Set<ReturnType<typeof setTimeout>>()

  /** 全局手势监听清理函数，用于「play 被拒后等下一次点击再试」 */
  private gestureCleanup: (() => void) | null = null

  /**
   * 在用户手势中同步调用，解锁音频元素。
   * <p>
   * iOS Safari 要求音频播放必须由用户手势发起。由于开始朗读需要先向后端
   * 申请播放凭据（一次异步请求），手势链会在 await 处断开。因此在点击的
   * 同步阶段先用静音音频激活元素，后续再替换真实音源即可正常播放。
   * 元素一旦被手势激活，整个页面生命周期内都保持可用。
   */
  primeFromGesture(): void {
    if (this.unlocked) return
    const audio = this.ensureAudio()
    try {
      audio.src = SILENT_WAV
      audio.volume = 0
      const p = audio.play()
      if (p && typeof p.then === 'function') {
        p.then(() => {
          this.unlocked = true
          // 静音解锁完成后恢复音量，等待真实音源
          audio.volume = 1
        }).catch(() => {
          // 解锁失败不阻断流程，真实播放时还会再尝试一次
          audio.volume = 1
        })
      } else {
        this.unlocked = true
        audio.volume = 1
      }
    } catch {
      // 忽略：真实播放阶段仍有机会成功
    }
  }

  /**
   * 开始朗读一段文本
   *
   * @param text       已清洗的纯文本
   * @param configId   TTS 配置ID
   * @param metadata   系统媒体控制所需的元信息
   * @param callbacks  播放回调
   */
  async start(
    text: string,
    configId: number | undefined,
    metadata: PlaybackMetadata,
    callbacks: PlaybackCallbacks,
  ): Promise<void> {
    this.stop({ keepElement: true })

    const generation = ++this.generation
    this.text = text
    this.configId = configId
    this.metadata = metadata
    this.callbacks = callbacks
    this.stopped = false
    this.intentPlaying = true
    this.recoverAttempts = 0
    this.rebuildInFlight = false
    this.rebuildTargetOverride = null
    this.awaitingComplete = false
    // 新一次朗读默认走渐进流（首帧最快）；只有用户拖动时才切换完整文件模式
    this.wantComplete = false
    // 按汉字数估算总时长：标点与 Markdown 符号不发声，按总字符数会高估
    const hanCount = (text.match(HAN_PATTERN) ?? []).length
    const spokenChars = hanCount > 0 ? hanCount : text.length
    this.estimatedDuration = Math.max(MIN_ESTIMATED_DURATION, spokenChars / HAN_PER_SECOND)

    const audio = this.ensureAudio()
    this.attachListeners(audio, generation)

    try {
      const ticket = await prepareTtsStream(text, configId)
      if (generation !== this.generation) {
        // 期间已被取消：及时释放刚申请的凭据
        void releaseTtsStream(ticket.ticketId).catch(() => {})
        return
      }
      this.ticketId = ticket.ticketId
    } catch (e) {
      if (generation !== this.generation) return
      this.fail(generation, e instanceof Error ? e : new Error(String(e)))
      return
    }

    this.playFrom(generation, 0)
    this.updateMediaSession()
  }

  /**
   * 暂停播放（保留位置，可继续）
   */
  pause(): void {
    this.intentPlaying = false
    this.cancelPendingTimers()
    this.audio?.pause()
  }

  /**
   * 继续播放
   */
  resume(): void {
    if (this.stopped) return
    this.intentPlaying = true
    const audio = this.audio
    if (!audio || !audio.src) return
    void this.safePlay()
  }

  /**
   * 跳转到指定位置（秒）
   * <p>
   * 若当前是渐进式流（尚不可精确定位），直接改 currentTime 会被浏览器忽略，
   * 用户拖动滑块毫无反应。因此不可定位时改为重建连接：服务端此时通常已缓存
   * 全部分段，重建后会返回支持 Range 的完整文件，再精确跳到目标位置。
   *
   * @param seconds 目标位置
   */
  seek(seconds: number): void {
    const audio = this.audio
    if (!audio || this.stopped) return
    if (!this.seekable) {
      // 渐进式流不可精确定位：改用 complete 模式重建，服务端补齐分段后
      // 返回带 Content-Length 的完整文件，浏览器即可跳到任意位置。
      //
      // 这是用户主动操作，不占用故障恢复的次数配额；否则用户来回拖动
      // 几次就会把配额耗尽，之后遇到真正的网络中断只能被迫降级。
      if (this.rebuildInFlight) {
        // 上一次重建还没落地：把最新目标位置合并进去，落地时一并生效。
        // 这样连续拖动只产生一次网络往返，且最终停在用户最后一次拖到的位置。
        this.rebuildTargetOverride = Math.max(0, seconds)
        this.wantComplete = true
        return
      }
      this.wantComplete = true
      this.doRecover(this.generation, new Error('拖动定位触发重建'), seconds, false)
      return
    }
    try {
      const duration = Number.isFinite(audio.duration) ? audio.duration : seconds
      const target = Math.max(0, Math.min(seconds, Math.max(0, duration - 0.25)))
      audio.currentTime = target
      this.lastCurrentTime = target
      this.lastAdvanceAt = Date.now()
      this.highWaterTime = target
    } catch {
      // 极端情况下浏览器拒绝写入位置，忽略
    }
  }

  /**
   * 相对跳转（锁屏/耳机的快进快退按键）
   * <p>
   * 渐进式流尚未下载完时无法定位，此时按键若静默失败，用户会以为功能坏了。
   * 因此不可定位时改为重建连接：服务端此时通常已缓存全部分段，
   * 重建后会返回可定位的完整文件，再跳到目标位置。
   *
   * @param deltaSeconds 相对秒数，正为快进、负为快退
   */
  private seekBy(deltaSeconds: number): void {
    this.seek(this.currentTime + deltaSeconds)
  }

  /**
   * 当前播放位置（秒）
   */
  get currentTime(): number {
    return this.audio?.currentTime ?? 0
  }

  /**
   * 当前已知总时长（秒）
   * <p>
   * 两种情况：
   * <ul>
   *   <li>服务端返回了完整文件（支持 Range）→ audio.duration 就是精确总长，直接用</li>
   *   <li>渐进式流 → audio.duration 只是「已缓冲到的位置」，直接使用会让进度条
   *       永远显示快播完；此时用按文本长度估算的总长</li>
   * </ul>
   * 注意不能在完整文件时也用估算值：估算基于语速标定，与真实时长可能相差
   * 一倍以上，会把精确进度变成错的。
   */
  get duration(): number {
    const d = this.audio?.duration
    const real = d != null && Number.isFinite(d) && d > 0 ? d : 0
    if (this.isRangeSeekable()) return real
    return Math.max(real, this.estimatedDuration)
  }

  /**
   * 当前是否可精确 seek（服务端已返回可定位的完整文件）
   */
  get seekable(): boolean {
    return this.isRangeSeekable()
  }

  /**
   * 判定底层媒体是否支持任意位置定位
   * <p>
   * 判定依据是浏览器报告的真实时长，不能用估算总时长——估算值永远大于
   * 渐进流的可 seek 末端，会导致这里恒为 false。
   */
  private isRangeSeekable(): boolean {
    const audio = this.audio
    if (!audio) return false
    const ranges = audio.seekable
    if (!ranges || ranges.length === 0) return false
    const end = ranges.end(ranges.length - 1)
    const real = audio.duration
    if (end <= 0 || !Number.isFinite(real) || real <= 0) return false
    // 可 seek 末端覆盖了真实总长，说明拿到的是支持 Range 的完整文件
    return real - end < 1
  }

  /**
   * 是否正在播放
   */
  get playing(): boolean {
    return this.audio ? !this.audio.paused && !this.stopped : false
  }

  /**
   * 停止并释放资源
   *
   * @param options.keepElement true 时保留 audio 元素以便下次复用（默认保留）
   */
  stop(options: { keepElement?: boolean } = {}): void {
    this.stopped = true
    this.intentPlaying = false
    this.generation += 1
    this.cancelPendingTimers()
    this.stopMonitor()
    this.clearGestureListener()

    const audio = this.audio
    if (audio) {
      try {
        audio.pause()
        // 移除音源可立即中断仍在进行的网络下载，避免后台继续消耗流量
        audio.removeAttribute('src')
        audio.load()
      } catch {
        // 忽略
      }
    }

    const ticketId = this.ticketId
    this.ticketId = null
    if (ticketId) {
      void releaseTtsStream(ticketId).catch(() => {})
    }

    this.clearMediaSession()

    if (!options.keepElement && audio) {
      audio.remove()
      this.audio = null
      // 元素已销毁：解锁标记必须一并作废。
      // 否则下次 ensureAudio 造出新元素时 primeFromGesture 会直接返回，
      // 新元素从未被手势激活，iOS 上第一次播放会被自动播放策略拒绝。
      this.unlocked = false
    }
  }

  /**
   * 彻底销毁，页面卸载时调用
   */
  dispose(): void {
    this.stop({ keepElement: false })
    this.callbacks = {}
  }

  // ────────────────────────── 内部实现 ──────────────────────────

  /**
   * 确保 audio 元素存在并已挂载到文档
   * <p>
   * 必须挂载到 DOM：部分移动端浏览器对游离的 audio 元素会限制后台播放行为。
   */
  private ensureAudio(): HTMLAudioElement {
    if (this.audio) return this.audio
    const audio = document.createElement('audio')
    audio.preload = 'auto'
    // iOS Safari 需要 playsinline 才不会把音频接管为全屏播放器
    audio.setAttribute('playsinline', 'true')
    audio.setAttribute('webkit-playsinline', 'true')
    audio.style.display = 'none'
    audio.dataset.kbookTtsPlayer = 'true'
    document.body.appendChild(audio)
    this.audio = audio
    return audio
  }

  /**
   * 绑定媒体事件监听。
   * <p>
   * 每次重新设置音源都会重建监听，避免重复绑定导致一次事件触发多次回调。
   */
  private attachListeners(audio: HTMLAudioElement, generation: number): void {
    const isCurrent = () => generation === this.generation

    audio.onended = () => {
      if (!isCurrent()) return
      // 渐进式流在下载完成前 duration 会持续增长，ended 只在真正播完后触发
      this.stopMonitor()
      this.intentPlaying = false
      this.stopped = true
      this.clearMediaSession()
      this.callbacks.onEnd?.()
    }

    audio.onerror = () => {
      if (!isCurrent()) return
      const code = audio.error?.code
      const message = audio.error?.message ?? '音频加载失败'
      // code=1 (ABORTED) 通常是主动换源导致，不算错误
      if (code === MediaError.MEDIA_ERR_ABORTED) return
      // 网络类错误（断流、DNS 抖动、切网）立即重试往往还是失败，
      // 必须退避后再重建，否则几次机会会在瞬间耗尽。
      this.scheduleRecoverWithBackoff(generation, new Error(`音频错误(${code ?? '未知'}): ${message}`))
    }

    audio.onpause = () => {
      if (!isCurrent() || this.stopped || !this.intentPlaying) return
      // 非主动暂停：来电、其它 App 抢占焦点、系统中断都会走到这里。
      // 旧实现在这里毫无作为，于是整条链路静默停住。
      this.scheduleResumeRetry(generation, 0)
    }

    audio.onwaiting = () => {
      if (!isCurrent()) return
      // 缓冲等待交给进度监控判断是否真的卡死，这里只记录时间基准
      this.lastAdvanceAt = Date.now()
    }

    audio.onloadedmetadata = () => {
      if (!isCurrent()) return
      this.awaitingComplete = false
      this.applyPendingSeek(generation)
      this.updatePositionState()
    }

    audio.onplaying = () => {
      if (!isCurrent()) return
      this.lastAdvanceAt = Date.now()
      this.lastCurrentTime = audio.currentTime
      // 不在此处清零恢复计数：播放事件可能在「播了但没声音」时照常触发，
      // 只有位置真正前进（ontimeupdate）才算恢复健康。
      this.startMonitor(generation)
      this.emitProgress()
    }

    audio.ontimeupdate = () => {
      if (!isCurrent()) return
      const t = audio.currentTime
      if (t > this.highWaterTime) this.highWaterTime = t
      if (t > this.lastCurrentTime + 0.05) {
        this.lastCurrentTime = t
        this.lastAdvanceAt = Date.now()
        // 位置确实前进，说明当前播放是健康的，重置所有恢复状态
        this.wakeAttemptedAt = 0
        this.recoverAttempts = 0
      }
    }
  }

  /** 下次元数据就绪后要跳转到的位置（重连续播用） */
  private pendingSeek: number | null = null

  /** 上次同步锁屏进度的时间戳 */
  private lastPositionSyncAt = 0

  /**
   * 本次加载是否请求「完整文件」模式
   * <p>
   * 渐进式流只能 seek 到已缓冲位置，用户拖到未缓冲区间会被浏览器静默忽略。
   * 需要精确定位时改用 complete 模式：服务端补齐所有分段后整段返回，
   * 带 Content-Length 与 Range 支持，可自由跳转。
   */
  private wantComplete = false

  /**
   * 是否正在等待「完整文件」响应
   * <p>
   * complete 模式下服务端要先补齐所有缺失分段才能返回首字节（实测长文
   * 可达 15 秒）。这段等待是预期行为而非播放停滞，看门狗必须在此期间
   * 停止判定，否则会连续重建并耗尽恢复次数，最后误降级。
   */
  private awaitingComplete = false

  /**
   * 本次等待「完整文件」的起始时刻
   * <p>
   * 用时间戳而不是定时器：定时器会被 pause() 一并清掉，
   * 使保护在「等待期间暂停再继续」后永久失效。
   */
  private awaitingCompleteStartedAt = 0

  /**
   * 是否有一次「重建连接」正在申请凭据
   * <p>
   * 并发重建会各自换源，后完成的那个覆盖先完成的，用户听到从错误位置重播。
   */
  private rebuildInFlight = false

  /**
   * 重建进行期间用户再次拖动时的最新目标位置
   * <p>
   * 合并而不是并发：连续拖动只产生一次网络往返，最终停在最后一次的定位。
   */
  private rebuildTargetOverride: number | null = null

  /**
   * 从指定位置开始播放
   *
   * @param generation 代际，用于丢弃过期结果
   * @param fromSeconds 起始位置
   */
  private playFrom(generation: number, fromSeconds: number): void {
    const audio = this.audio
    if (!audio || !this.ticketId) return
    if (generation !== this.generation) return

    this.pendingSeek = fromSeconds > 0.5 ? fromSeconds : null
    this.lastCurrentTime = fromSeconds
    this.wakeAttemptedAt = 0
    // 高水位对齐到续播点：恢复成功前的旧值已通过 recover 消费完毕，
    // 否则若本次恢复后又失败，会重复从更早的位置重试。
    this.highWaterTime = fromSeconds
    this.lastAdvanceAt = Date.now()
    // complete 模式首字节要等服务端补齐全部分段，期间看门狗需暂停判定。
    // 计时起点而非定时器：定时器会被 pause() 的 cancelPendingTimers 清掉，
    // 导致「等待完整文件时暂停再继续」之后看门狗永久失效，卡死无法自愈。
    this.awaitingComplete = this.wantComplete
    this.awaitingCompleteStartedAt = Date.now()

    audio.src = buildTtsStreamUrl(this.ticketId, this.wantComplete ? 'complete' : 'progressive')
    audio.load()

    // 部分浏览器在长时间后台后会残留异常的播放速率，导致「在播但没声音」，
    // 恢复时归一到正常速率。
    if (audio.playbackRate !== 1) {
      try { audio.playbackRate = 1 } catch { /* 忽略 */ }
    }

    // 只有「期望在播」时才起播：用户暂停后拖动进度条只应改变位置，
    // 不应该被这次重建顺手唤醒播放。
    if (this.intentPlaying) void this.safePlay()
    this.startMonitor(generation)
  }

  /**
   * 调用 play() 并处理被拒绝的情况
   */
  private async safePlay(): Promise<boolean> {
    const audio = this.audio
    if (!audio) return false
    try {
      await audio.play()
      this.unlocked = true
      return true
    } catch (e) {
      const name = e instanceof Error ? e.name : ''
      if (name === 'NotAllowedError') {
        // 自动播放策略拦截：等用户下一次交互再续播，保证「不是我悄悄停了」
        this.waitForGestureThenPlay()
        return false
      }
      if (name === 'AbortError') {
        // 换源等操作会中断上一次 play()，属正常
        return false
      }
      return false
    }
  }

  /**
   * 等待下一次用户交互后尝试续播
   */
  private waitForGestureThenPlay(): void {
    if (this.gestureCleanup) return
    const handler = () => {
      this.clearGestureListener()
      if (this.stopped || !this.intentPlaying) return
      void this.safePlay()
    }
    const events: Array<keyof DocumentEventMap> = ['pointerdown', 'keydown', 'touchstart']
    events.forEach((ev) => document.addEventListener(ev, handler, { once: true, passive: true }))
    this.gestureCleanup = () => {
      events.forEach((ev) => document.removeEventListener(ev, handler))
      this.gestureCleanup = null
    }
  }

  private clearGestureListener(): void {
    this.gestureCleanup?.()
    this.gestureCleanup = null
  }

  /**
   * 元数据就绪后应用续播位置
   */
  private applyPendingSeek(generation: number): void {
    if (generation !== this.generation) return
    const audio = this.audio
    const target = this.pendingSeek
    if (!audio || target == null) return
    try {
      const duration = audio.duration
      // 只有真实时长可信时才钳制：complete 模式下 duration 就是文件总长；
      // 渐进流下它只是已缓冲位置，用它钳制会把用户的跳转目标截断到很早的位置。
      const max = Number.isFinite(duration) && duration > 0 ? duration - 0.25 : target
      audio.currentTime = Math.min(target, Math.max(0, max))
    } catch {
      // 极端情况下浏览器拒绝写入位置，从头播放至少不会静音
    }
    this.pendingSeek = null
  }

  /**
   * 启动进度监控
   * <p>
   * 这是替代「只等 ended」的核心：只要播放中位置长期不前进，就主动修复，
   * 而不是无限等待一个可能永远不会到来的事件。
   */
  private startMonitor(generation: number): void {
    this.stopMonitor()
    this.lastAdvanceAt = Date.now()
    this.lastCurrentTime = this.audio?.currentTime ?? 0

    this.monitorTimer = setInterval(() => {
      if (generation !== this.generation) {
        this.stopMonitor()
        return
      }
      const audio = this.audio
      if (!audio) return
      this.emitProgress()

      if (audio.paused) {
        // 暂停由 onpause 处理；这里只刷新进度
        return
      }
      if (audio.ended) return

      // 等待 complete 响应期间服务端正在补齐分段，尚未开始输出音频。
      // 这段等待（长文可达十几秒）是预期行为，若按停滞处理会连续重建，
      // 把恢复次数耗尽后误判失败。每轮把基准时间前推，等价于「暂时不计时」。
      if (this.awaitingComplete) {
        // 兜底：服务端彻底无响应时不能无限等待，超时后解除保护交给看门狗。
        if (Date.now() - this.awaitingCompleteStartedAt >= COMPLETE_WAIT_TIMEOUT_MS) {
          this.awaitingComplete = false
          this.lastAdvanceAt = Date.now()
        } else {
          this.lastAdvanceAt = Date.now()
          this.wakeAttemptedAt = 0
          return
        }
      }

      const idle = Date.now() - this.lastAdvanceAt
      if (idle < SILENT_RECOVER_MS) return

      if (audio.readyState >= HTMLMediaElement.HAVE_FUTURE_DATA) {
        // 数据充足却不前进：元素状态与实际输出脱节。
        // 先用极低成本唤醒（重新 play + 轻推位置），若宽限期内仍无进展则重建连接。
        // 只容忍一次唤醒，避免用户在静默中等待。
        if (this.wakeAttemptedAt === 0) {
          this.wakeAttemptedAt = Date.now()
          this.nudgePlayback(generation)
          return
        }
        if (Date.now() - this.wakeAttemptedAt < WAKE_GRACE_MS) return
        this.wakeAttemptedAt = 0
        this.recover(generation, new Error('播放位置长时间停滞'))
        return
      }

      // 数据不足且位置不前进：连接已停滞，重建（服务端此时多半已有缓存，可直接续播）
      if (idle >= STALL_RECOVER_MS) {
        this.recover(generation, new Error('音频流停滞'))
      }
    }, PROGRESS_TICK_MS)
  }

  /**
   * 低成本唤醒：重新发起 play() 并修正可能异常的播放速率。
   * <p>
   * 移动端从后台返回、或音频焦点被短暂抢占后，元素可能处于
   * 「paused=false 但不出声」的僵死状态。单纯 play() 通常无效，
   * 重置 playbackRate 再 play() 能重新触发音频输出管线。
   */
  private nudgePlayback(generation: number): void {
    if (generation !== this.generation) return
    const audio = this.audio
    if (!audio) return
    try {
      if (audio.playbackRate !== 1) audio.playbackRate = 1
    } catch {
      // 忽略
    }
    void this.safePlay()
  }

  private stopMonitor(): void {
    if (this.monitorTimer) {
      clearInterval(this.monitorTimer)
      this.monitorTimer = null
    }
  }

  /**
   * 重建连接并从断点续播
   *
   * @param generation 代际
   * @param reason     触发恢复的原因，仅用于日志
   * @param resumeAt   指定续播位置（秒）；省略时用高水位位置
   */
  /**
   * 重建连接并从断点续播（计入故障恢复配额）
   */
  private recover(generation: number, reason: Error, resumeAt?: number): void {
    this.doRecover(generation, reason, resumeAt, true)
  }

  /**
   * 重建连接并从断点续播
   *
   * @param generation        代际
   * @param reason            触发原因，仅用于日志
   * @param resumeAt          指定续播位置（秒）；省略时用高水位位置
   * @param countsAgainstQuota 是否计入故障恢复次数配额
   */
  private doRecover(
    generation: number,
    reason: Error,
    resumeAt: number | undefined,
    countsAgainstQuota: boolean,
  ): void {
    if (generation !== this.generation || this.stopped) return

    // 同一时刻只允许一次重建。并发重建会各自换源，后完成的那个覆盖先完成的，
    // 用户听到的是从错误位置重播；同时服务端也会收到多次重复合成请求。
    if (this.rebuildInFlight) return

    // 用高水位而非 currentTime：加载失败/换源会把 currentTime 清零，
    // 直接读它会导致从 0 重播，用户听到内容倒退。
    const target = resumeAt ?? Math.max(this.highWaterTime, this.lastCurrentTime, 0)

    if (countsAgainstQuota) {
      this.recoverAttempts += 1
      if (this.recoverAttempts > MAX_RECOVER_ATTEMPTS) {
        this.fail(generation, reason)
        return
      }
    }

    console.warn(
      countsAgainstQuota
        ? `[TTS] 播放中断，第 ${this.recoverAttempts} 次自动恢复，从 ${target.toFixed(2)}s 续播：${reason.message}`
        : `[TTS] 按用户定位请求重建连接，从 ${target.toFixed(2)}s 续播：${reason.message}`,
    )

    // 重新申请凭据：旧凭据可能已过期，且首次请求已把分段写入服务端缓存，
    // 这次多半会命中缓存返回可定位的完整文件，从而精确跳回断点。
    const attempt = generation
    this.rebuildInFlight = true
    void (async () => {
      try {
        const ticket = await prepareTtsStream(this.text, this.configId)
        if (attempt !== this.generation) {
          void releaseTtsStream(ticket.ticketId).catch(() => {})
          return
        }
        if (this.ticketId) {
          const old = this.ticketId
          void releaseTtsStream(old).catch(() => {})
        }
        this.ticketId = ticket.ticketId
        this.stopMonitor()
        // 期间用户又拖了一次的话，以最后一次的目标位置为准
        const finalTarget = this.rebuildTargetOverride ?? target
        this.rebuildTargetOverride = null
        this.playFrom(attempt, finalTarget)
      } catch (e) {
        if (attempt !== this.generation || this.stopped) return
        // 重新申请凭据失败多半是网络暂时不可用（弱网、切网、进隧道）。
        // 直接判死会让用户在信号恢复后仍停在「已停止」；改为退避后重试重建。
        const reason2 = e instanceof Error ? e : new Error(String(e))
        if (countsAgainstQuota && this.recoverAttempts >= MAX_RECOVER_ATTEMPTS) {
          this.fail(attempt, reason2)
          return
        }
        const delay = RESUME_RETRY_DELAYS[Math.min(this.recoverAttempts, RESUME_RETRY_DELAYS.length - 1)]
        console.warn(`[TTS] 重建连接失败，${delay}ms 后重试：${reason2.message}`)
        const timer = setTimeout(() => {
          this.pendingTimers.delete(timer)
          if (attempt !== this.generation || this.stopped || !this.intentPlaying) return
          this.doRecover(attempt, reason2, target, countsAgainstQuota)
        }, delay)
        this.pendingTimers.add(timer)
      } finally {
        // 只清理属于本次代际的闸门：新一轮朗读可能已经启动了新的重建，
        // 无差别清除会把那次的保护一起关掉。
        if (attempt === this.generation) this.rebuildInFlight = false
      }
    })()
  }

  /**
   * 带退避的重建
   * <p>
   * 网络类错误立即重建通常仍会失败，连续失败会把重试次数瞬间耗尽，
   * 导致用户在信号刚恢复时就已收到「失败」。因此先等待一个递增间隔，
   * 再尝试重建。
   *
   * @param generation 代际
   * @param reason     错误原因
   */
  private scheduleRecoverWithBackoff(generation: number, reason: Error): void {
    if (generation !== this.generation || this.stopped || !this.intentPlaying) return
    const delay = RESUME_RETRY_DELAYS[Math.min(this.recoverAttempts, RESUME_RETRY_DELAYS.length - 1)]
    const timer = setTimeout(() => {
      this.pendingTimers.delete(timer)
      if (generation !== this.generation || this.stopped || !this.intentPlaying) return
      this.recover(generation, reason)
    }, delay)
    this.pendingTimers.add(timer)
  }

  /**
   * 被系统中断后按退避策略尝试续播
   */
  private scheduleResumeRetry(generation: number, attempt: number): void {
    if (generation !== this.generation || this.stopped || !this.intentPlaying) return
    if (attempt >= RESUME_RETRY_DELAYS.length) {
      // 自动恢复失败：明确上报，让界面显示「已暂停」，而不是假装在朗读
      this.intentPlaying = false
      this.callbacks.onInterrupted?.()
      return
    }
    const timer = setTimeout(() => {
      this.pendingTimers.delete(timer)
      if (generation !== this.generation || this.stopped || !this.intentPlaying) return
      void (async () => {
        const ok = await this.safePlay()
        if (!ok) this.scheduleResumeRetry(generation, attempt + 1)
      })()
    }, RESUME_RETRY_DELAYS[attempt])
    this.pendingTimers.add(timer)
  }

  private cancelPendingTimers(): void {
    this.pendingTimers.forEach((t) => clearTimeout(t))
    this.pendingTimers.clear()
  }

  /**
   * 不可恢复的失败
   */
  private fail(generation: number, error: Error): void {
    if (generation !== this.generation) return
    this.stopped = true
    this.intentPlaying = false
    this.stopMonitor()
    this.cancelPendingTimers()
    this.clearMediaSession()
    this.callbacks.onError?.(error)
  }

  private emitProgress(): void {
    const audio = this.audio
    if (!audio) return
    // 锁屏进度同步与界面订阅解耦：没有界面订阅者时（例如进度条未挂载），
    // 系统媒体控制条的进度仍必须继续推进，否则会停在首次加载的位置。
    const now = Date.now()
    if (now - this.lastPositionSyncAt >= 1000) {
      this.lastPositionSyncAt = now
      this.updatePositionState()
    }
    if (!this.callbacks.onProgress) return
    // 用估算总时长而非 audio.duration：后者在渐进流下只是已缓冲位置
    this.callbacks.onProgress({
      currentTime: audio.currentTime,
      duration: this.duration,
      playing: !audio.paused && !this.stopped,
      seekable: this.seekable,
    })
  }

  // ────────────────────────── 系统媒体控制 ──────────────────────────

  /**
   * 注册锁屏 / 通知栏 / 耳机按键控制
   * <p>
   * 这是「锁屏也能听、还能操作」的关键：不注册 MediaSession 时，
   * 移动端要么不在锁屏显示控制条，要么在被外部接管后无法再拿回音频焦点。
   */
  private updateMediaSession(): void {
    const session = getMediaSession()
    if (!session) return
    const meta = this.metadata
    if (!meta) return

    try {
      const artwork: MediaImage[] = meta.artworkUrl
        ? [{ src: meta.artworkUrl, sizes: '512x512', type: 'image/jpeg' }]
        : []
      session.metadata = new MediaMetadata({
        title: meta.title,
        artist: meta.artist ?? '',
        album: meta.album ?? meta.title,
        artwork,
      })
    } catch {
      // 老浏览器不支持 MediaMetadata 构造
    }

    const handlers: Array<[MediaSessionAction, MediaSessionActionHandler | null]> = [
      ['play', () => this.resume()],
      ['pause', () => this.pause()],
      ['stop', () => {
        // 系统侧要求停止：凭据与音源一并释放后「继续」按钮就失效了，
        // 必须同步通知界面退出朗读态，否则界面上还显示着一个能点的暂停键。
        this.stop()
        this.callbacks.onStoppedBySystem?.()
      }],
      ['seekbackward', (details) => this.seekBy(-(details.seekOffset ?? 15))],
      ['seekforward', (details) => this.seekBy(details.seekOffset ?? 15)],
      ['seekto', (details) => {
        if (typeof details.seekTime !== 'number') return
        this.seek(details.seekTime)
      }],
    ]
    for (const [action, handler] of handlers) {
      try {
        session.setActionHandler(action, handler)
      } catch {
        // 不支持的 action 忽略即可
      }
    }
  }

  /**
   * 同步锁屏进度条
   */
  private updatePositionState(): void {
    const session = getMediaSession()
    const audio = this.audio
    if (!session || typeof session.setPositionState !== 'function' || !audio) return
    // 同样用估算总时长：锁屏进度条若按已缓冲位置当总长，会显示成快播完
    const duration = this.duration
    if (!Number.isFinite(duration) || duration <= 0) return
    try {
      session.setPositionState({
        duration,
        playbackRate: audio.playbackRate,
        position: Math.min(audio.currentTime, duration),
      })
    } catch {
      // 参数越界等情况忽略
    }
  }

  /**
   * 清空系统媒体控制信息
   */
  private clearMediaSession(): void {
    const session = getMediaSession()
    if (!session) return
    try {
      session.metadata = null
      for (const action of ['play', 'pause', 'stop', 'seekbackward', 'seekforward', 'seekto'] as MediaSessionAction[]) {
        session.setActionHandler(action, null)
      }
    } catch {
      // 忽略
    }
  }
}

/**
 * 取得 MediaSession 对象（不支持时返回 null）
 */
function getMediaSession(): MediaSession | null {
  if (typeof navigator === 'undefined') return null
  return navigator.mediaSession ?? null
}