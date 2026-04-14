import { useEffect, useRef, useCallback, useState } from 'react'
import { useReaderStore } from '@/store/reader'
import { useProgressStore } from '@/store/progress'
import type { EpubChapter } from '@/types/reader'
import { getBook } from '@/api/book'
import type { Book } from '@/types/book'
import ePub from 'epubjs'

interface UseEpubReaderOptions {
  bookId: number
  initialPosition?: string | null
  /** 系统是否处于暗夜模式 */
  isSystemDark?: boolean
  /** iframe 内容区域点击回调（用于弹出工具栏） */
  onContentClick?: (position: { x: number; y: number }) => void
}

/**
 * EPUB 阅读器 Hook
 * 使用 epubjs 库解析 EPUB，支持章节导航和进度上报
 */
export function useEpubReader({ bookId, initialPosition, isSystemDark, onContentClick }: UseEpubReaderOptions) {
  const [book, setBook] = useState<Book | null>(null)
  const [chapters, setChapters] = useState<EpubChapter[]>([])
  const [currentChapterId, setCurrentChapterId] = useState('')
  const [currentChapterIndex, setCurrentChapterIndex] = useState(0)
  const [progress, setProgress] = useState(0)
  const [loading, setLoading] = useState(true)
  const [epubReady, setEpubReady] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const renditionRef = useRef<any>(null)
  const epubBookRef = useRef<any>(null)
  const lastReportRef = useRef(0)
  const { settings } = useReaderStore()
  const { reportProgress } = useProgressStore()
  const chaptersRef = useRef<EpubChapter[]>([])
  const containerRef = useRef<HTMLDivElement>(null)
  const parsedChaptersRef = useRef<EpubChapter[]>([])
  const initialPositionRef = useRef<string | null>(initialPosition)
  const locationsReadyRef = useRef(false)
  const onContentClickRef = useRef(onContentClick)

  // 更新 chapters ref
  useEffect(() => { chaptersRef.current = chapters }, [chapters])
  // 保持 initialPosition ref 最新
  useEffect(() => { initialPositionRef.current = initialPosition }, [initialPosition])
  // 保持 onContentClick ref 最新
  useEffect(() => { onContentClickRef.current = onContentClick }, [onContentClick])

  // 应用主题
  const applyTheme = useCallback((rendition: any, s: any) => {
    const isDark = isSystemDark ?? window.matchMedia('(prefers-color-scheme: dark)').matches
    const effectiveThemeKey = isDark ? 'DARK' : s.themeKey
    const themeColors = getThemeColors(effectiveThemeKey)
    
    // 使用用户选择的字体（已在 FONT_OPTIONS 中配置好移动端兼容的字体栈）
    const fontFamily = s.fontFamily
    
    console.log('Applying font:', fontFamily)
    
    // 方式1：使用 epubjs themes.override 方法（最可靠）
    try {
      if (rendition?.themes) {
        // 覆盖所有元素的字体
        rendition.themes.override('font-family', fontFamily, true)
        rendition.themes.override('font-size', `${s.fontSize}px`, true)
        rendition.themes.override('line-height', String(s.lineHeight), true)
        rendition.themes.override('color', themeColors.fg, true)
        rendition.themes.override('background', themeColors.bg, true)
        console.log('Themes override applied successfully')
      }
    } catch (e) {
      console.warn('Failed to use themes.override:', e)
    }
    
    // 方式2：直接向所有 iframe 注入 style 标签
    try {
      // epubjs 可能有多个 iframe（每个章节一个）
      const iframes = rendition?.manager?.views?.all() || []
      
      if (iframes.length === 0) {
        // 如果没有 views，尝试从 container 中查找
        const container = rendition?.manager?.container
        if (container) {
          const foundIframes = container.querySelectorAll('iframe')
          if (foundIframes.length > 0) {
            Array.from(foundIframes).forEach((iframe: any) => {
              injectStylesToIframe(iframe, fontFamily, s, themeColors)
            })
          }
        }
      } else {
        // 遍历所有 view 的 iframe
        iframes.forEach((view: any) => {
          const iframe = view?.iframe || view
          if (iframe) {
            injectStylesToIframe(iframe, fontFamily, s, themeColors)
          }
        })
      }
    } catch (e) {
      console.warn('Failed to inject styles to iframes:', e)
    }
    
    // 方式3：使用 register + select 作为备用
    try {
      rendition?.themes?.register('custom-theme', {
        '*': {
          'font-family': `${fontFamily} !important`,
          'font-size': `${s.fontSize}px !important`,
          'line-height': `${s.lineHeight} !important`,
        },
      })
      rendition?.themes?.select('custom-theme')
      console.log('Custom theme registered and selected')
    } catch (e) {
      console.warn('Failed to register custom theme:', e)
    }
  }, [isSystemDark])
  
  // 辅助函数：向单个 iframe 注入样式
  const injectStylesToIframe = (iframe: any, fontFamily: string, s: any, themeColors: any) => {
    try {
      const iframeDoc = iframe.contentDocument || iframe.contentWindow?.document
      if (!iframeDoc) {
        console.warn('Cannot access iframe document')
        return
      }
      
      // 移除旧的自定义样式
      const oldStyle = iframeDoc.getElementById('custom-reader-styles')
      if (oldStyle) {
        oldStyle.remove()
      }
      
      // 构建 CSS
      const cssRules = `
        * {
          font-family: ${fontFamily} !important;
        }
        html, body {
          font-family: ${fontFamily} !important;
          font-size: ${s.fontSize}px !important;
          line-height: ${s.lineHeight} !important;
          color: ${themeColors.fg} !important;
          background: ${themeColors.bg} !important;
        }
      `
      
      // 注入新的样式
      const styleEl = iframeDoc.createElement('style')
      styleEl.id = 'custom-reader-styles'
      styleEl.textContent = cssRules
      
      if (iframeDoc.head) {
        if (iframeDoc.head.firstChild) {
          iframeDoc.head.insertBefore(styleEl, iframeDoc.head.firstChild)
        } else {
          iframeDoc.head.appendChild(styleEl)
        }
        console.log('Styles injected to iframe successfully')
      }
    } catch (e) {
      console.warn('Failed to inject styles to iframe:', e)
    }
  }

  // 安全销毁 rendition
  const safeDestroyRendition = useCallback(() => {
    if (renditionRef.current) {
      try {
        renditionRef.current.destroy?.()
      } catch (e) {
        console.warn('epubjs rendition destroy failed:', e)
      }
      renditionRef.current = null
    }
  }, [])

  // 安全销毁 epubBook
  const safeDestroyBook = useCallback(() => {
    if (epubBookRef.current) {
      try {
        epubBookRef.current.destroy?.()
      } catch (e) {
        console.warn('epubjs book destroy failed:', e)
      }
      epubBookRef.current = null
    }
  }, [])

  // 第一阶段：加载 EPUB 数据
  useEffect(() => {
    let cancelled = false

    async function load() {
      try {
        const bookRes = await getBook(bookId)
        if (cancelled) return
        setBook(bookRes as unknown as Book)

        // 下载 EPUB 文件
        const token = localStorage.getItem(import.meta.env.VITE_TOKEN_KEY || 'kbook_token')
        const headers: Record<string, string> = {}
        if (token) headers['Authorization'] = `Bearer ${token}`
        const response = await fetch(`/api/books/${bookId}/file`, { headers })
        if (!response.ok) throw new Error('EPUB 文件下载失败')
        const arrayBuffer = await response.arrayBuffer()
        if (cancelled) return

        const epubBook = ePub(arrayBuffer)
        epubBookRef.current = epubBook

        await epubBook.ready
        if (cancelled) return

        // 解析目录
        const toc = epubBook.navigation?.toc || []
        const parsedChapters: EpubChapter[] = toc.map((item: any, index: number) => ({
          id: item.id || `chapter-${index}`,
          href: item.href,
          title: item.label?.trim() || `第${index + 1}章`,
          level: 0,
          parent: null,
          order: index,
        }))

        if (parsedChapters.length === 0) {
          const spineItems = epubBook.spine?.spineItems || []
          parsedChapters.push(
            ...spineItems.map((item: any, i: number) => ({
              id: item.id || `chapter-${i}`,
              href: item.href,
              title: (bookRes as unknown as Book).title || `第${i + 1}章`,
              level: 0,
              parent: null,
              order: i,
            }))
          )
        }

        parsedChaptersRef.current = parsedChapters
        setChapters(parsedChapters)

        // 先让用户看到内容，locations 异步生成
        setLoading(false)

        // 标记 epubBook 已就绪，触发第二阶段渲染
        setEpubReady(true)

        // 异步生成 locations（进度计算依赖此）
        locationsReadyRef.current = false
        epubBook.locations.generate(1600).then(() => {
          locationsReadyRef.current = true
        }).catch(() => {
          // locations 生成失败不影响阅读
        })
      } catch (e: any) {
        if (!cancelled) {
          console.error('EPUB 加载失败:', e)
          setError(e.message || 'EPUB 加载失败')
          setLoading(false)
        }
      }
    }

    load()

    return () => {
      cancelled = true
      safeDestroyRendition()
      safeDestroyBook()
      setEpubReady(false)
    }
  }, [bookId, safeDestroyRendition, safeDestroyBook])

  // 第二阶段：容器挂载后渲染 EPUB
  // 注意：依赖 epubReady（epubBook 已加载）而非 loading
  // 主题变更由下方单独的 useEffect 处理，避免重新创建 rendition
  useEffect(() => {
    const epubBook = epubBookRef.current
    if (!epubBook || !epubReady) return

    async function render() {
      try {
        if (renditionRef.current) return

        // 等待容器 DOM 就绪
        let container = containerRef.current
        if (!container) {
          // containerRef 可能还未挂载，短暂等待
          await new Promise<void>((resolve) => {
            const check = () => {
              if (containerRef.current) {
                resolve()
              } else {
                requestAnimationFrame(check)
              }
            }
            check()
          })
          container = containerRef.current
        }
        if (!container) return

        const rendition = epubBook.renderTo(container, {
          width: '100%',
          height: '100%',
          spread: 'none',
          flow: 'scrolled-doc',
          manager: 'continuous',
        })
        renditionRef.current = rendition

        // 设置基础样式 - 添加左右内边距
        rendition.themes.default({
          'body': {
            'padding-left': '16px !important',
            'padding-right': '16px !important',
            'margin': '0 !important',
          },
        })

        // 应用用户主题
        applyTheme(rendition, settings)
        
        // 注册 content 钩子，确保每次内容加载都应用字体（解决移动端问题）
        rendition.hooks.content.register((contents: any) => {
          const doc = contents.document
          if (!doc) return
          
          // 移除旧样式
          const oldStyle = doc.getElementById('custom-reader-styles')
          if (oldStyle) oldStyle.remove()
          
          // 注入新样式
          const isDark = isSystemDark ?? window.matchMedia('(prefers-color-scheme: dark)').matches
          const effectiveThemeKey = isDark ? 'DARK' : settings.themeKey
          const themeColors = getThemeColors(effectiveThemeKey)
          const fontFamily = settings.fontFamily
          
          const styleEl = doc.createElement('style')
          styleEl.id = 'custom-reader-styles'
          styleEl.textContent = `
            * {
              font-family: ${fontFamily} !important;
            }
            html, body {
              font-family: ${fontFamily} !important;
              font-size: ${settings.fontSize}px !important;
              line-height: ${settings.lineHeight} !important;
              color: ${themeColors.fg} !important;
              background: ${themeColors.bg} !important;
            }
            body {
              padding-left: 16px !important;
              padding-right: 16px !important;
              margin: 0 !important;
            }
          `
          
          if (doc.head) {
            doc.head.appendChild(styleEl)
          }
        })

        // 显示初始位置或第一页
        const startPos = initialPositionRef.current || undefined
        await rendition.display(startPos)

        // 监听位置变化
        rendition.on('relocated', (location: any) => {
          if (!location?.start) return

          const cfi = location.start.cfi
          setCurrentChapterId(cfi)

          // 章节定位
          const href = location.start.href
          const chs = parsedChaptersRef.current
          const idx = chs.findIndex((ch: EpubChapter) =>
            ch.href === href || href.startsWith(ch.href.split('#')[0])
          )
          if (idx >= 0) {
            setCurrentChapterIndex(idx)
          }

          // 进度计算
          if (locationsReadyRef.current) {
            const pct = epubBook.locations.percentageFromCfi(cfi)
            if (!isNaN(pct) && pct >= 0) {
              setProgress(pct)

              const now = Date.now()
              if (now - lastReportRef.current > 3000) {
                lastReportRef.current = now
                reportProgress(bookId, pct, cfi)
              }
            }
          } else {
            // locations 未就绪时，使用章节级粗略进度
            const totalChapters = parsedChaptersRef.current.length
            if (totalChapters > 0) {
              const idx = parsedChaptersRef.current.findIndex((ch: EpubChapter) =>
                ch.href === href || href.startsWith(ch.href.split('#')[0])
              )
              if (idx >= 0) {
                const pct = idx / totalChapters
                setProgress(pct)
              }
            }
          }
        })

        // 通过 MutationObserver 监听 iframe 插入 + rendition.getContents() 绑定事件
        // MutationObserver 检测 iframe DOM 变化，rendition.getContents() 安全获取文档引用
        const iframeObserver = new MutationObserver(() => {
          tryBindIframeEvents()
        })

        const tryBindIframeEvents = () => {
          const contents = rendition.getContents()
          if (!contents || contents.length === 0) return
          for (const content of contents) {
            const doc = content?.document
            const win = content?.window || doc?.defaultView
            if (!doc || !win || (doc as any).__kbookBound) continue
            ;(doc as any).__kbookBound = true

            // 阻止上下文菜单
            doc.addEventListener('contextmenu', (e: Event) => e.preventDefault())

            // 触摸点击检测（区分滑动和点击）
            let touchStartY = 0
            let touchStartX = 0
            let touchStartTime = 0
            let touchHandled = false

            doc.addEventListener('touchstart', (e: TouchEvent) => {
              touchStartY = e.touches[0].clientY
              touchStartX = e.touches[0].clientX
              touchStartTime = Date.now()
              touchHandled = false
            }, { passive: true })

            doc.addEventListener('touchend', (e: TouchEvent) => {
              const touch = e.changedTouches[0]
              if (!touch) return
              const dy = Math.abs(touch.clientY - touchStartY)
              const dx = Math.abs(touch.clientX - touchStartX)
              const dt = Date.now() - touchStartTime
              const clickHandler = onContentClickRef.current
              
              console.log('EPUB touchend:', { dx, dy, dt, hasHandler: !!clickHandler })
              
              // 放宽点击判定条件：移动距离 < 50px，时间 < 600ms
              if (dx < 50 && dy < 50 && dt < 600 && clickHandler) {
                console.log('EPUB click detected, calling handler')
                touchHandled = true
                clickHandler({
                  y: touch.clientY / window.innerHeight,
                  x: touch.clientX / window.innerWidth,
                })
              }
            }, { passive: true })

            // 桌面端点击（移动端由 touchend 处理，避免重复）
            doc.addEventListener('click', (e: MouseEvent) => {
              if (touchHandled) {
                touchHandled = false
                return
              }
              const clickHandler = onContentClickRef.current
              if (clickHandler) {
                clickHandler({
                  y: e.clientY / window.innerHeight,
                  x: e.clientX / window.innerWidth,
                })
              }
            })
          }
        }

        // MutationObserver 检测容器 DOM 变化（iframe 插入/替换时重新尝试绑定）
        iframeObserver.observe(container, { childList: true, subtree: true })
        // 立即尝试绑定（iframe 可能已经存在）
        tryBindIframeEvents()
        // 同时监听 rendition 事件作为备用
        rendition.on('rendered', tryBindIframeEvents)
        rendition.on('started', tryBindIframeEvents)

        // 在容器层级添加备用触摸事件监听（防止 iframe 内部事件未触发）
        let containerTouchStartY = 0
        let containerTouchStartX = 0
        let containerTouchStartTime = 0

        const handleContainerTouchStart = (e: TouchEvent) => {
          containerTouchStartY = e.touches[0].clientY
          containerTouchStartX = e.touches[0].clientX
          containerTouchStartTime = Date.now()
        }

        const handleContainerTouchEnd = (e: TouchEvent) => {
          const touch = e.changedTouches[0]
          if (!touch) return
          const dy = Math.abs(touch.clientY - containerTouchStartY)
          const dx = Math.abs(touch.clientX - containerTouchStartX)
          const dt = Date.now() - containerTouchStartTime
          const clickHandler = onContentClickRef.current

          console.log('Container touchend:', { dx, dy, dt, hasHandler: !!clickHandler })

          // 如果移动距离很小且时间很短，视为点击
          if (dx < 50 && dy < 50 && dt < 600 && clickHandler) {
            console.log('Container click detected')
            clickHandler({
              y: touch.clientY / window.innerHeight,
              x: touch.clientX / window.innerWidth,
            })
          }
        }

        container.addEventListener('touchstart', handleContainerTouchStart, { passive: true })
        container.addEventListener('touchend', handleContainerTouchEnd, { passive: true })
      } catch (e: any) {
        console.error('EPUB 渲染失败:', e)
        setError(e.message || 'EPUB 渲染失败')
      }
    }

    render()
    return () => {
      // MutationObserver 会在 rendition 销毁时自动断开（因为容器会被清空）
      // 不需要显式断开
    }
  }, [bookId, epubReady]) // eslint-disable-line react-hooks/exhaustive-deps

  // 主题/设置变更时仅更新样式，不重建 rendition
  useEffect(() => {
    if (renditionRef.current) {
      applyTheme(renditionRef.current, settings)
    }
  }, [settings, applyTheme])

  // 翻页
  const goPage = useCallback((direction: 'next' | 'prev') => {
    const rendition = renditionRef.current
    if (!rendition) return
    if (direction === 'next') {
      rendition.next()
    } else {
      rendition.prev()
    }
  }, [])

  // 跳转章节
  const goToChapter = useCallback((chapterIndex: number) => {
    const chapter = chapters[chapterIndex]
    if (!chapter) return
    renditionRef.current?.display(chapter.href)
    setCurrentChapterIndex(chapterIndex)
  }, [chapters])

  // 切后台时上报
  useEffect(() => {
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'hidden') {
        reportProgress(bookId, progress, currentChapterId)
      }
    }
    document.addEventListener('visibilitychange', handleVisibilityChange)
    return () => document.removeEventListener('visibilitychange', handleVisibilityChange)
  }, [bookId, progress, currentChapterId, reportProgress])

  return {
    book, chapters, currentChapterIndex, currentChapterId, progress,
    loading, error, containerRef, goPage, goToChapter, renditionRef, epubBookRef,
  }
}

function getThemeColors(themeKey: string) {
  const themes: Record<string, { bg: string; fg: string }> = {
    LIGHT: { bg: '#ffffff', fg: '#333333' },
    SEPIA: { bg: '#f5efdc', fg: '#5b4636' },
    GREEN: { bg: '#cce8cf', fg: '#2d4a2e' },
    DARK: { bg: '#1a1a1a', fg: '#cccccc' },
  }
  return themes[themeKey] || themes.LIGHT
}
