import { useEffect, useLayoutEffect, useState, useCallback, useRef } from 'react'
import { ArrowLeft, Send, Plus, Image, Paperclip, Mic, Smile, User, Loader2, ChevronDown, Keyboard, Delete, Video } from 'lucide-react'
import { useNavigate, useParams } from 'react-router-dom'
import { getMessages, sendMessage, markAsRead, uploadChatFile, getConversation } from '@/api/chat'
import type { ChatMessageVO, ConversationVO } from '@/api/chat'
import { useChatStore } from '@/store/chat'
import { useAuthStore } from '@/store/auth'
import { formatRelativeTime } from '@/utils/time'
import { getAccessToken } from '@/utils/token-refresh'
import { toast } from 'sonner'
import { chatWebSocketService } from '@/services/chatWebSocket'
import FilePreviewModal from '@/components/common/FilePreviewModal'

const COMMON_EMOJIS = [
  '😀', '😂', '😊', '😍', '🤔', '😅', '😢', '😭',
  '😡', '🥺', '😴', '🤗', '😏', '😱', '🥳', '😎',
  '👍', '👎', '❤️', '💔', '🔥', '🎉', '👏', '🙏',
  '💪', '✨', '⭐', '🌟', '💯', '✅', '❌', '⚡',
  '📚', '📖', '☕', '🍺', '🎵', '🎶', '🌈', '☀️',
  '🌙', '🌸', '🌺', '🍀', '🌻', '🎁', '🏆', '💡',
  '😋', '😜', '🤪', '😝', '🤑', '🤠', '😇', '🤡',
  '👻', '💀', '☠️', '👽', '🤖', '🎃', '😺', '🙈',
  '🥰', '🤩', '😘', '🫡', '😈', '🗿', '💅',
]

export default function ChatRoomPage() {
  const { conversationId } = useParams<{ conversationId: string }>()
  const navigate = useNavigate()
  const { userInfo } = useAuthStore()
  const { markMessagesAsRead, messages: storeMessages } = useChatStore()
  
  const [inputValue, setInputValue] = useState('')
  const [viewportHeight, setViewportHeight] = useState(window.innerHeight)
  const [loading, setLoading] = useState(true)
  const [sending, setSending] = useState(false)
  const [conversation, setConversation] = useState<ConversationVO | null>(null)
  const [canSendMore, setCanSendMore] = useState(true)
  const [page, setPage] = useState(1)
  const [hasMore, setHasMore] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [showEmojiPicker, setShowEmojiPicker] = useState(false)
  const [showNewMessageBadge, setShowNewMessageBadge] = useState(false)
  const [previewFile, setPreviewFile] = useState<{ url: string; name: string; type: string } | null>(null)
  const [isVoiceMode, setIsVoiceMode] = useState(false)
  const [isRecording, setIsRecording] = useState(false)
  const [, setRecordingDuration] = useState(0)
  const [playingMessageId, setPlayingMessageId] = useState<number | null>(null)
  const [playingRemainingTime, setPlayingRemainingTime] = useState<number>(0)
  const [cancelRecording, setCancelRecording] = useState(false)
  const [showFileMenu, setShowFileMenu] = useState(false)
  
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const imageInputRef = useRef<HTMLInputElement>(null)
  const videoInputRef = useRef<HTMLInputElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const scrollContainerRef = useRef<HTMLDivElement>(null)
  const initialScrollDone = useRef(false)
  const userScrolledUp = useRef(false)
  const mediaRecorderRef = useRef<MediaRecorder | null>(null)
  const audioChunksRef = useRef<Blob[]>([])
  const recordingTimerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const recordingDurationRef = useRef(0)
  const preloadedStreamRef = useRef<MediaStream | null>(null)
  const recordButtonRef = useRef<HTMLButtonElement>(null)
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const playTimerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)

  const id = Number(conversationId)
  const messages = storeMessages[id] || []

  // 移动端视口高度适配 + 锁定 body 滚动
  useEffect(() => {
    const prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const onResize = () => setViewportHeight(window.innerHeight)
    window.addEventListener('resize', onResize)
    return () => {
      document.body.style.overflow = prevOverflow
      window.removeEventListener('resize', onResize)
      if (preloadedStreamRef.current) {
        preloadedStreamRef.current.getTracks().forEach(t => t.stop())
        preloadedStreamRef.current = null
      }
    }
  }, [])

  // 发送后重置 textarea 高度
  useEffect(() => {
    if (!inputValue && inputRef.current) {
      inputRef.current.style.height = 'auto'
    }
  }, [inputValue])

  // 旋转180度后，检测是否在顶部（视觉上是在底部）
  const isAtTop = useCallback(() => {
    const container = scrollContainerRef.current
    if (!container) return false
    return container.scrollTop <= 10
  }, [])

  // 旋转180度后，滚动到顶部（视觉上是滚动到底部）
  const scrollToTop = useCallback(() => {
    if (scrollContainerRef.current) {
      scrollContainerRef.current.scrollTop = 0
    }
  }, [])

  const handleNewMessageBadgeClick = useCallback(() => {
    scrollToTop()
    setShowNewMessageBadge(false)
    userScrolledUp.current = false
  }, [scrollToTop])

  const loadMessages = useCallback(async (pageNum: number = 1, append = false) => {
    try {
      if (append) {
        setLoadingMore(true)
      }
      
      const res: any = await getMessages(id, pageNum)
      const data = res?.list || []
      const { setMessages, appendMessages: storeAppend } = useChatStore.getState()
      
      if (append) {
        storeAppend(id, data) // 注意这里不反转，因为UI会旋转180度
        setHasMore(data.length >= 20)
      } else {
        setMessages(id, data)
        setHasMore(data.length >= 20)
      }
    } catch (err: any) {
      toast.error(err.message || '加载消息失败')
    } finally {
      if (append) {
        setLoadingMore(false)
      }
    }
  }, [id])

  const loadConversationInfo = useCallback(async () => {
    try {
      const res: any = await getConversation(id)
      if (res) {
        setConversation(res)
      }
    } catch { /* ignore */ }
  }, [id])

  useEffect(() => {
    setLoading(true)
    initialScrollDone.current = false
    userScrolledUp.current = false
    setShowNewMessageBadge(false)
    Promise.all([loadMessages(1), loadConversationInfo()]).finally(() => {
      setLoading(false)
    })
  }, [id, loadConversationInfo, loadMessages])

  // 记录前一个消息数量，用于判断是否有新消息
  const previousMessagesLength = useRef(0)

  // 首次加载完成后立即滚动到顶部（paint 之前），视觉上就是在底部
  useLayoutEffect(() => {
    if (!loading && messages.length > 0 && !initialScrollDone.current) {
      scrollToTop()
      initialScrollDone.current = true
    }
  }, [loading, messages.length, scrollToTop])

  // 监听消息变化，处理新消息时的滚动和浮窗
  useEffect(() => {
    const currentLength = messages.length
    const isNewMessage = currentLength > previousMessagesLength.current
    previousMessagesLength.current = currentLength

    // 只有在不是加载历史消息并且有新消息时才处理
    if (isNewMessage && !loadingMore) {
      // 判断新消息是不是自己发的（通过ID判断逻辑比较复杂，暂时简化处理）
      // 这里依赖WebSocket来区分自己和对方的消息
    }
  }, [messages.length, loadingMore])

  // 新消息来自 WebSocket 时才触发浮窗，不响应历史加载
  useEffect(() => {
    chatWebSocketService.setOnMessage((message) => {
      if (message.senderId === userInfo?.id) return
      if (isAtTop()) {
        // 视觉上在底部，新消息来了直接滚动上去（视觉上就是滚动到底）
        setTimeout(() => {
          scrollToTop()
          setShowNewMessageBadge(false)
        }, 50)
      } else {
        setShowNewMessageBadge(true)
      }
    })

    chatWebSocketService.connect()

    return () => {
      chatWebSocketService.setOnMessage(null)
      chatWebSocketService.disconnect()
    }
  }, [isAtTop, scrollToTop, userInfo?.id])

  useEffect(() => {
    if (!id || isNaN(id)) return
    markAsRead(id).then(() => {})
    markMessagesAsRead(id)
  }, [id, markMessagesAsRead])

  const handleSend = async () => {
    if (!inputValue.trim() || sending || !conversation) return

    setSending(true)
    try {
      await sendMessage(conversation.otherUserId, inputValue.trim(), 'TEXT')
      setInputValue('')
      await loadMessages(1)
      setTimeout(scrollToTop, 100)
    } catch (err: any) {
      toast.error(err.message || '发送失败')
      if (err.message?.includes('只能发送一条消息')) {
        setCanSendMore(false)
      }
    } finally {
      setSending(false)
    }
  }

  const handleEmojiSelect = (emoji: string) => {
    setInputValue(prev => prev + emoji)
  }

  const handleFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file || !conversation) return

    if (file.size > 50 * 1024 * 1024) {
      toast.error('文件大小不能超过50MB')
      e.target.value = ''
      return
    }

    setSending(true)
    setShowFileMenu(false)
    try {
      const uploadData: any = await uploadChatFile(file)

      const isImage = file.type.startsWith('image/')
      const msgType = isImage ? 'IMAGE' : 'FILE'
      
      await sendMessage(
        conversation.otherUserId,
        '',
        msgType,
        file.name,
        file.size,
        uploadData?.url
      )
      
      await loadMessages(1)
      setTimeout(scrollToTop, 100)
    } catch (err: any) {
      toast.error(err.message || '发送失败')
      if (err.message?.includes('只能发送一条消息')) {
        setCanSendMore(false)
      }
    } finally {
      setSending(false)
      e.target.value = ''
    }
  }

  const encodeWav = (samples: Float32Array, sampleRate: number): Blob => {
    const buffer = new ArrayBuffer(44 + samples.length * 2)
    const view = new DataView(buffer)

    const writeString = (offset: number, str: string) => {
      for (let i = 0; i < str.length; i++) view.setUint8(offset + i, str.charCodeAt(i))
    }

    writeString(0, 'RIFF')
    view.setUint32(4, 36 + samples.length * 2, true)
    writeString(8, 'WAVE')
    writeString(12, 'fmt ')
    view.setUint32(16, 16, true)
    view.setUint16(20, 1, true)
    view.setUint16(22, 1, true)
    view.setUint32(24, sampleRate, true)
    view.setUint32(28, sampleRate * 2, true)
    view.setUint16(32, 2, true)
    view.setUint16(34, 16, true)
    writeString(36, 'data')
    view.setUint32(40, samples.length * 2, true)

    let offset = 44
    for (let i = 0; i < samples.length; i++, offset += 2) {
      const s = Math.max(-1, Math.min(1, samples[i]))
      view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7FFF, true)
    }

    return new Blob([buffer], { type: 'audio/wav' })
  }

  const convertToWav = async (audioBlob: Blob): Promise<Blob> => {
    const arrayBuffer = await audioBlob.arrayBuffer()
    const audioContext = new AudioContext()
    const audioBuffer = await audioContext.decodeAudioData(arrayBuffer)
    const samples = audioBuffer.getChannelData(0)
    const wavBlob = encodeWav(samples, audioBuffer.sampleRate)
    audioContext.close()
    return wavBlob
  }

  const startRecording = async () => {
    try {
      let stream = preloadedStreamRef.current
      if (stream) {
        preloadedStreamRef.current = null
      } else {
        stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      }
      const mediaRecorder = new MediaRecorder(stream)
      mediaRecorderRef.current = mediaRecorder
      audioChunksRef.current = []

      mediaRecorder.ondataavailable = (e) => {
        if (e.data.size > 0) audioChunksRef.current.push(e.data)
      }

      mediaRecorder.onstop = async () => {
        stream.getTracks().forEach(track => track.stop())
        if (cancelRecording || audioChunksRef.current.length === 0) return
        
        const duration = recordingDurationRef.current
        if (duration < 1) {
          toast.info('录音时间太短，请重新录制')
          return
        }

        const audioBlob = new Blob(audioChunksRef.current, { type: 'audio/webm' })
        const wavBlob = await convertToWav(audioBlob)
        const audioFile = new File([wavBlob], 'voice.wav', { type: 'audio/wav' })

        try {
          setSending(true)
          const uploadData: any = await uploadChatFile(audioFile)
          await sendMessage(
            conversation!.otherUserId,
            '',
            'VOICE',
            '语音消息',
            audioFile.size,
            uploadData?.url,
            duration
          )
          await loadMessages(1)
          setTimeout(scrollToTop, 100)
        } catch (err: any) {
          toast.error(err.message || '发送失败')
        } finally {
          setSending(false)
        }
      }

      mediaRecorder.start()
      setIsRecording(true)
      setCancelRecording(false)
      setRecordingDuration(0)
      recordingDurationRef.current = 0

      recordingTimerRef.current = setInterval(() => {
        setRecordingDuration(prev => {
          if (prev >= 59) {
            stopRecording()
            return prev
          }
          recordingDurationRef.current = prev + 1
          return prev + 1
        })
      }, 1000)
    } catch (err) {
      toast.error('无法访问麦克风')
    }
  }

  const stopRecording = () => {
    if (mediaRecorderRef.current && mediaRecorderRef.current.state !== 'inactive') {
      mediaRecorderRef.current.stop()
    }
    if (recordingTimerRef.current) {
      clearInterval(recordingTimerRef.current)
      recordingTimerRef.current = null
    }
    setIsRecording(false)
    setRecordingDuration(0)
  }

  const handleRecordStart = (e: React.MouseEvent | React.TouchEvent) => {
    e.preventDefault()
    startRecording()
  }

  const handleRecordEnd = (e: React.MouseEvent | React.TouchEvent) => {
    e.preventDefault()
    if (cancelRecording) {
      stopRecording()
      setCancelRecording(false)
    } else {
      stopRecording()
    }
  }

  const handleRecordMove = useCallback((e: React.MouseEvent | React.TouchEvent) => {
    if (!isRecording || !recordButtonRef.current) return
    const rect = recordButtonRef.current.getBoundingClientRect()
    const clientY = 'touches' in e ? e.touches[0].clientY : e.clientY
    const diff = rect.top - clientY
    setCancelRecording(diff > 50)
  }, [isRecording])

  const toggleVoiceMode = async () => {
    const nextVoiceMode = !isVoiceMode
    setIsVoiceMode(nextVoiceMode)
    setShowEmojiPicker(false)
    inputRef.current?.blur()
    if (nextVoiceMode && !preloadedStreamRef.current) {
      try {
        preloadedStreamRef.current = await navigator.mediaDevices.getUserMedia({ audio: true })
      } catch {
        toast.error('无法访问麦克风')
        setIsVoiceMode(false)
      }
    }
    if (!nextVoiceMode && preloadedStreamRef.current) {
      preloadedStreamRef.current.getTracks().forEach(t => t.stop())
      preloadedStreamRef.current = null
    }
  }

  const toggleEmojiPicker = () => {
    setShowEmojiPicker(!showEmojiPicker)
    setShowFileMenu(false)
    if (!showEmojiPicker) {
      setIsVoiceMode(false)
    }
  }

  const handlePlayVoice = (msg: ChatMessageVO) => {
    if (playingMessageId === msg.id) {
      audioRef.current?.pause()
      setPlayingMessageId(null)
      if (playTimerRef.current) {
        clearInterval(playTimerRef.current)
        playTimerRef.current = null
      }
      return
    }

    if (audioRef.current) {
      audioRef.current.pause()
    }
    if (playTimerRef.current) {
      clearInterval(playTimerRef.current)
      playTimerRef.current = null
    }

    const audio = new Audio(`${msg.fileUrl}?token=${getAccessToken() || ''}`)
    audioRef.current = audio
    setPlayingMessageId(msg.id)
    setPlayingRemainingTime(msg.voiceDuration || 0)

    // 倒计时
    playTimerRef.current = setInterval(() => {
      setPlayingRemainingTime(prev => {
        if (prev <= 1) {
          if (playTimerRef.current) {
            clearInterval(playTimerRef.current)
            playTimerRef.current = null
          }
          return 0
        }
        return prev - 1
      })
    }, 1000)

    audio.onended = () => {
      setPlayingMessageId(null)
      setPlayingRemainingTime(0)
      if (playTimerRef.current) {
        clearInterval(playTimerRef.current)
        playTimerRef.current = null
      }
    }
    audio.onerror = () => {
      toast.error('播放失败')
      setPlayingMessageId(null)
      setPlayingRemainingTime(0)
      if (playTimerRef.current) {
        clearInterval(playTimerRef.current)
        playTimerRef.current = null
      }
    }
    audio.play()
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  const handleScroll = useCallback(() => {
    const container = scrollContainerRef.current
    if (!container) return
    
    // 旋转180度后，检测是否滚到底部加载历史消息（视觉上是滚到顶部）
    const { scrollTop, scrollHeight, clientHeight } = container
    if (scrollHeight - scrollTop - clientHeight < 200 && hasMore && !loadingMore) {
      setPage(prev => {
        const nextPage = prev + 1
        loadMessages(nextPage, true)
        return nextPage
      })
    }

    // 检测是否在顶部（视觉上是在底部）
    if (isAtTop()) {
      userScrolledUp.current = false
      setShowNewMessageBadge(false)
    } else {
      userScrolledUp.current = true
    }
  }, [hasMore, loadingMore, loadMessages, page, isAtTop])

  const isMine = (msg: ChatMessageVO) => {
    return msg.senderId === userInfo?.id
  }

  const formatFileSize = (bytes?: number | null) => {
    if (!bytes) return '0B'
    if (bytes < 1024) return bytes + 'B'
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + 'KB'
    return (bytes / (1024 * 1024)).toFixed(1) + 'MB'
  }

  const getFileTypeConfig = (fileName: string) => {
    const ext = fileName.toLowerCase().split('.').pop() || ''
    switch (ext) {
      case 'pdf': return { type: 'PDF', color: 'bg-red-600', foldColor: 'bg-red-800' }
      case 'doc': case 'docx': return { type: 'DOC', color: 'bg-blue-600', foldColor: 'bg-blue-800' }
      case 'xls': case 'xlsx': return { type: 'XLS', color: 'bg-emerald-600', foldColor: 'bg-emerald-800' }
      case 'ppt': case 'pptx': return { type: 'PPT', color: 'bg-amber-600', foldColor: 'bg-amber-800' }
      case 'txt': return { type: 'TXT', color: 'bg-slate-600', foldColor: 'bg-slate-800' }
      case 'md': return { type: 'MD', color: 'bg-violet-600', foldColor: 'bg-violet-800' }
      default: return { type: 'FILE', color: 'bg-zinc-600', foldColor: 'bg-zinc-800' }
    }
  }

  const FileTypeIcon = ({ type, color, foldColor }: { type: string; color: string; foldColor: string }) => (
    <div className={`relative w-11 h-14 rounded-lg ${color} flex flex-col items-center justify-center shrink-0`}>
      <div className={`absolute top-0 right-0 w-3 h-3 overflow-hidden rounded-tr-lg`}>
        <div className={`absolute top-0 right-0 w-0 h-0 border-t-[12px] ${foldColor} border-l-[12px] border-l-transparent`} />
      </div>
      <span className="text-[10px] font-bold text-white mt-1">{type}</span>
    </div>
  )

  const BroadcastIcon = ({ isPlaying, isMine }: { isPlaying: boolean; isMine: boolean }) => {
    const colorClass = isMine ? 'text-primary-foreground' : 'text-primary'
    return (
      <div className={`relative w-5 h-5 flex items-center justify-center ${colorClass}`}>
        {/* 中心圆点 */}
        <div className="absolute w-1.5 h-1.5 rounded-full bg-current" />
        
        {/* 静态圆环 */}
        <div className="absolute w-3 h-3 border border-current rounded-full opacity-70" />
        <div className="absolute w-5 h-5 border border-current rounded-full opacity-50" />
        
        {/* 播放时的动态波纹 */}
        {isPlaying && (
          <>
            <div className="absolute w-5 h-5 border-2 border-current rounded-full animate-ping" style={{ animationDuration: '1s' }} />
            <div className="absolute w-4 h-4 border-2 border-current rounded-full animate-ping" style={{ animationDuration: '1.2s', animationDelay: '0.2s' }} />
          </>
        )}
      </div>
    )
  }

  return (
    <div ref={containerRef} className="bg-background page-enter flex flex-col overflow-hidden overscroll-none relative" style={{ height: viewportHeight }}>
      {/* Header - 固定在顶部 */}
      <header className="flex-shrink-0 z-10 flex items-center gap-3 border-b border-border/50 bg-background/80 px-4 py-3 backdrop-blur-xl">
        <button onClick={() => navigate(-1)} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted">
          <ArrowLeft className="h-5 w-5" />
        </button>
        {conversation ? (
          <>
            <button
              onClick={() => navigate(`/user/${conversation.otherUserId}`)}
              className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary/10 overflow-hidden"
            >
              {conversation.otherUserAvatar ? (
                <img src={conversation.otherUserAvatar} alt="" className="h-full w-full object-cover" />
              ) : (
                <span className="text-sm font-bold text-primary">{conversation.otherUserNickname?.[0] || 'U'}</span>
              )}
            </button>
            <div className="flex-1 min-w-0">
              <h1 className="text-base font-bold truncate">{conversation.otherUserNickname}</h1>
            </div>
          </>
        ) : (
          <h1 className="text-base font-bold">加载中...</h1>
        )}
      </header>

      {/* Message List - 可滚动区域（旋转180度） */}
      <div
        ref={scrollContainerRef}
        onScroll={handleScroll}
        className="flex-1 min-h-0 overflow-y-auto overscroll-contain px-4 py-4"
        style={{
          scrollBehavior: 'auto',
          transform: 'rotate(180deg)',
        }}
      >
        {loading ? (
          <div className="flex justify-center py-12" style={{ transform: 'rotate(180deg)' }}>
            <div className="h-6 w-6 animate-spin rounded-full border-3 border-primary border-t-transparent" />
          </div>
        ) : messages.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-16 text-muted-foreground" style={{ transform: 'rotate(180deg)' }}>
            <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-muted mb-3">
              <User className="h-6 w-6 text-muted-foreground/50" />
            </div>
            <p className="text-sm">开始与对方聊天吧</p>
          </div>
        ) : (
          <div className="space-y-3">
            {/* 加载更多指示器 */}
            {loadingMore && (
              <div className="flex justify-center py-3" style={{ transform: 'rotate(180deg)' }}>
                <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
              </div>
            )}
            {/* 消息列表 */}
            {messages.map(msg => (
              <div
                key={msg.id}
                className={`flex ${isMine(msg) ? 'justify-end' : 'justify-start'}`}
                style={{ transform: 'rotate(180deg)' }}
              >
                <div className={`flex gap-2 ${isMine(msg) ? 'flex-row-reverse' : ''} max-w-[85%]`}>
                  {conversation && (
                    <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full overflow-hidden">
                      {isMine(msg) ? (
                        userInfo?.avatar ? (
                          <img src={userInfo.avatar} alt="" className="h-full w-full object-cover" />
                        ) : (
                          <div className="h-full w-full bg-primary/10 flex items-center justify-center">
                            <span className="text-xs font-bold text-primary">{userInfo?.nickname?.[0] || 'U'}</span>
                          </div>
                        )
                      ) : (
                        conversation.otherUserAvatar ? (
                          <img src={conversation.otherUserAvatar} alt="" className="h-full w-full object-cover" />
                        ) : (
                          <div className="h-full w-full bg-primary/10 flex items-center justify-center">
                            <span className="text-xs font-bold text-primary">{conversation.otherUserNickname?.[0] || 'U'}</span>
                          </div>
                        )
                      )}
                    </div>
                  )}
                  <div className={`flex flex-col flex-1 min-w-0 ${isMine(msg) ? 'items-end' : 'items-start'}`}>
                    {msg.messageType === 'FILE' && msg.fileUrl ? (
                      <button
                        onClick={() => setPreviewFile({ url: msg.fileUrl!, name: msg.fileName || 'file', type: 'FILE' })}
                        className={`flex items-center gap-3 px-3 py-2.5 rounded-2xl hover:opacity-90 transition-opacity w-full ${
                          isMine(msg) ? 'bg-primary text-primary-foreground' : 'bg-card border border-border/50'
                        }`}
                      >
                        <FileTypeIcon {...getFileTypeConfig(msg.fileName || '')} />
                        <div className="flex-1 min-w-0 text-left overflow-hidden">
                          <p className="text-sm font-medium break-all line-clamp-2">{msg.fileName}</p>
                          <p className={`text-xs mt-0.5 ${isMine(msg) ? 'text-primary-foreground/60' : 'text-muted-foreground'}`}>
                            {formatFileSize(msg.fileSize)}
                          </p>
                        </div>
                      </button>
                    ) : (
                      <div className={`rounded-2xl ${
                        msg.messageType === 'IMAGE' && msg.fileUrl
                          ? 'overflow-hidden'
                          : `px-3 py-2 ${isMine(msg)
                              ? 'bg-primary text-primary-foreground rounded-br-md'
                              : 'bg-card border border-border/50 rounded-bl-md'}`
                      }`}>
                      {msg.messageType === 'TEXT' && (
                        <p className="text-sm whitespace-pre-wrap break-words">{msg.content}</p>
                      )}
                      {msg.messageType === 'IMAGE' && msg.fileUrl && (
                        <img
                          src={`${msg.fileUrl}?token=${getAccessToken() || ''}`}
                          alt=""
                          className="max-w-[200px] max-h-[200px] rounded-lg object-contain cursor-pointer"
                          onClick={() => setPreviewFile({ url: msg.fileUrl!, name: msg.fileName || 'image', type: 'IMAGE' })}
                        />
                      )}
                      {msg.messageType === 'VOICE' && (
                        <button
                          onClick={() => handlePlayVoice(msg)}
                          className={`flex items-center justify-between px-3 py-2 rounded-2xl ${
                            isMine(msg) 
                              ? 'bg-primary text-primary-foreground rounded-br-md' 
                              : 'bg-card border border-border/50 rounded-bl-md'
                          }`}
                          style={{ width: 'calc((85vw - 40px) / 2)' }}
                        >
                          <BroadcastIcon isPlaying={playingMessageId === msg.id} isMine={isMine(msg)} />
                          <span className="text-xs font-medium">
                            {playingMessageId === msg.id 
                              ? `${Math.floor(playingRemainingTime / 60)}:${String(playingRemainingTime % 60).padStart(2, '0')}`
                              : msg.voiceDuration 
                                ? `${Math.floor(msg.voiceDuration / 60)}:${String(msg.voiceDuration % 60).padStart(2, '0')}`
                                : '0:00'
                            }
                          </span>
                        </button>
                      )}
                      </div>
                    )}
                    <span className="text-[10px] text-muted-foreground mt-0.5">
                      {formatRelativeTime(msg.createdAt)}
                    </span>
                  </div>
                </div>
              </div>
            ))}
            <div ref={messagesEndRef} />
          </div>
        )}
      </div>

      {/* 新消息提示浮窗 */}
      {showNewMessageBadge && (
        <button
          onClick={handleNewMessageBadgeClick}
          className="absolute bottom-24 right-4 bg-primary text-primary-foreground shadow-lg rounded-full px-4 py-2 flex items-center gap-2 z-20 hover:bg-primary/90 transition-colors animate-bounce"
        >
          <span className="text-sm">新消息</span>
          <ChevronDown className="h-4 w-4" />
        </button>
      )}

      {/* Input Area */}
      <div className="flex-shrink-0 border-t border-border/50 bg-background px-4 py-3 relative">
        <div className="flex items-center gap-2">
          <button
            onClick={toggleVoiceMode}
            className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted transition-colors"
          >
            {isVoiceMode ? <Keyboard className="h-5 w-5" /> : <Mic className="h-5 w-5" />}
          </button>

          {isVoiceMode ? (
            <button
              ref={recordButtonRef}
              onMouseDown={handleRecordStart}
              onMouseUp={handleRecordEnd}
              onMouseMove={handleRecordMove}
              onMouseLeave={() => { if (isRecording) setCancelRecording(true) }}
              onTouchStart={handleRecordStart}
              onTouchEnd={handleRecordEnd}
              onTouchMove={handleRecordMove}
              onTouchCancel={() => { if (isRecording) setCancelRecording(true) }}
              className={`flex-1 h-10 rounded-xl text-sm font-medium transition-all select-none touch-none ${
                isRecording
                  ? cancelRecording
                    ? 'bg-red-500/20 text-red-500 border border-red-500/30'
                    : 'bg-primary/20 text-primary border border-primary/30'
                  : 'bg-muted text-muted-foreground border border-border/50'
              }`}
            >
              {isRecording ? (cancelRecording ? '松开取消' : '松开发送') : '按住说话'}
            </button>
          ) : (
            <textarea
              ref={inputRef as any}
              value={inputValue}
              onChange={(e) => {
                setInputValue(e.target.value)
                e.target.style.height = 'auto'
                e.target.style.height = Math.min(e.target.scrollHeight, 120) + 'px'
              }}
              onKeyDown={handleKeyDown}
              onFocus={() => {
                setShowEmojiPicker(false)
                setShowFileMenu(false)
                setIsVoiceMode(false)
              }}
              placeholder="输入消息..."
              disabled={!canSendMore}
              rows={1}
              className="flex-1 rounded-xl border bg-background px-4 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 transition-shadow disabled:opacity-50 resize-none overflow-y-auto min-h-[40px] max-h-[120px]"
            />
          )}

          <button
            onClick={toggleEmojiPicker}
            className={`flex h-9 w-9 items-center justify-center rounded-xl transition-colors ${showEmojiPicker ? 'bg-muted' : 'hover:bg-muted'}`}
          >
            {showEmojiPicker ? <Keyboard className="h-5 w-5" /> : <Smile className="h-5 w-5" />}
          </button>

          {inputValue.trim() && !isVoiceMode ? (
            <button
              onClick={handleSend}
              disabled={sending || !canSendMore}
              className={`flex h-9 w-9 items-center justify-center rounded-xl transition-all ${
                !sending && canSendMore
                  ? 'bg-primary text-primary-foreground shadow-md shadow-primary/20'
                  : 'bg-muted text-muted-foreground'
              }`}
            >
              {sending ? (
                <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
              ) : (
                <Send className="h-4 w-4" />
              )}
            </button>
          ) : (
            <div className="relative">
              <button
                onClick={() => setShowFileMenu(!showFileMenu)}
                className={`flex h-9 w-9 items-center justify-center rounded-xl transition-colors ${showFileMenu ? 'bg-muted' : 'hover:bg-muted'}`}
              >
                <Plus className="h-5 w-5" />
              </button>
              {showFileMenu && (
                <div className="absolute bottom-full right-0 mb-2 bg-card rounded-xl p-2 shadow-lg border border-border/50 flex gap-2">
                  <button
                    onClick={() => { imageInputRef.current?.click(); setShowFileMenu(false) }}
                    className="flex flex-col items-center gap-1.5 px-3 py-2 hover:bg-muted rounded-lg"
                  >
                    <Image className="h-5 w-5" />
                    <span className="text-[10px]">图片</span>
                  </button>
                  <button
                    onClick={() => { videoInputRef.current?.click(); setShowFileMenu(false) }}
                    className="flex flex-col items-center gap-1.5 px-3 py-2 hover:bg-muted rounded-lg"
                  >
                    <Video className="h-5 w-5" />
                    <span className="text-[10px]">视频</span>
                  </button>
                  <button
                    onClick={() => { fileInputRef.current?.click(); setShowFileMenu(false) }}
                    className="flex flex-col items-center gap-1.5 px-3 py-2 hover:bg-muted rounded-lg"
                  >
                    <Paperclip className="h-5 w-5" />
                    <span className="text-[10px]">文件</span>
                  </button>
                </div>
              )}
            </div>
          )}
        </div>
        <input ref={imageInputRef} type="file" className="hidden" onChange={handleFileSelect} accept="image/*" />
        <input ref={videoInputRef} type="file" className="hidden" onChange={handleFileSelect} accept="video/*" />
        <input ref={fileInputRef} type="file" className="hidden" onChange={handleFileSelect} accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.md" />
      </div>


      {/* Emoji Picker - Below Input Area */}
      {showEmojiPicker && (
        <div className="flex-shrink-0 border-t border-border/50 bg-background relative">
          <div className="px-4 py-3 grid grid-cols-8 gap-1 max-h-48 overflow-y-auto overscroll-y-contain">
            {COMMON_EMOJIS.map((emoji, i) => (
              <button
                key={i}
                onClick={() => handleEmojiSelect(emoji)}
                className="flex h-9 w-9 items-center justify-center text-xl hover:bg-muted rounded-lg active:scale-90 transition-transform"
              >
                {emoji}
              </button>
            ))}
          </div>
          <button
            onClick={() => {
              setInputValue(prev => Array.from(prev).slice(0, -1).join(''))
            }}
            className="absolute bottom-4 right-4 flex h-8 w-8 items-center justify-center rounded-lg bg-muted hover:bg-muted/80 transition-colors"
          >
            <Delete className="h-4 w-4" />
          </button>
        </div>
      )}

      <FilePreviewModal
        open={!!previewFile}
        onOpenChange={(open) => { if (!open) setPreviewFile(null) }}
        fileUrl={previewFile?.url || ''}
        fileName={previewFile?.name || ''}
        fileType={previewFile?.type || ''}
      />
    </div>
  )
}
