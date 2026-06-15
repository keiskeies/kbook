import { useState } from 'react'
import { MessageCircle } from 'lucide-react'
import AiChatSheet from '@/components/ai/AiChatSheet'

export function AiFab() {
  const [open, setOpen] = useState(false)

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="fixed z-40 flex h-14 w-14 items-center justify-center rounded-full bg-gradient-to-br from-primary to-primary/80 text-primary-foreground shadow-lg shadow-primary/30 hover:scale-105 active:scale-95 transition-transform md:bottom-6 md:right-6 bottom-24 right-4"
      >
        <MessageCircle className="h-6 w-6" strokeWidth={2.2} />
      </button>
      <AiChatSheet open={open} onOpenChange={setOpen} />
    </>
  )
}
