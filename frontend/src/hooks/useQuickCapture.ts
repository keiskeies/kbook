import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'
import { useNoteComposerStore } from '@/store/noteComposer'

function isEditable(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  return target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable
}

function hasOpenOverlay(): boolean {
  // 只认 data-state="open"：Radix 关闭后节点仍会留在 DOM 里，宽匹配会把快捷键吃掉
  return !!document.querySelector(
    '[role="dialog"][data-state="open"], [role="alertdialog"][data-state="open"], [role="menu"][data-state="open"], [role="listbox"][data-state="open"]'
  )
}

/**
 * 全局快速记录：
 * - 按 N（非输入框、无弹窗时）唤起记一笔
 * - 在非输入区域粘贴长文本时，直接带着内容唤起编辑器
 */
export function useQuickCapture() {
  const openComposer = useNoteComposerStore((s) => s.openComposer)
  const { pathname } = useLocation()
  // 后台管理页是工作台，个人记录快捷键在这里会抢走按键
  const enabled = !pathname.startsWith('/admin')

  useEffect(() => {
    if (!enabled) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.defaultPrevented || e.metaKey || e.ctrlKey || e.altKey || e.isComposing) return
      if (e.key.toLowerCase() !== 'n') return
      if (isEditable(e.target) || hasOpenOverlay()) return
      e.preventDefault()
      openComposer()
    }

    const onPaste = (e: ClipboardEvent) => {
      if (isEditable(e.target) || hasOpenOverlay()) return
      const text = e.clipboardData?.getData('text/plain')?.trim()
      if (!text || text.length < 40) return
      e.preventDefault()
      openComposer(text)
    }

    window.addEventListener('keydown', onKeyDown)
    document.addEventListener('paste', onPaste)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
      document.removeEventListener('paste', onPaste)
    }
  }, [enabled, openComposer])
}
