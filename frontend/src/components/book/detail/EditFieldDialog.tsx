import { useState, useEffect } from 'react'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { toast } from 'sonner'

interface EditFieldDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  value: string
  onSubmit: (value: string) => Promise<void>
  type?: 'input' | 'textarea'
  placeholder?: string
  required?: boolean
}

export function EditFieldDialog({
  open,
  onOpenChange,
  title,
  value,
  onSubmit,
  type = 'input',
  placeholder,
  required = false,
}: EditFieldDialogProps) {
  const [editValue, setEditValue] = useState(value)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (open) setEditValue(value)
  }, [open, value])

  const handleSubmit = async () => {
    if (required && !editValue.trim()) {
      toast.error('内容不能为空')
      return
    }
    setSubmitting(true)
    try {
      await onSubmit(editValue)
      onOpenChange(false)
    } catch (err: unknown) {
      toast.error((err as Error)?.message || '修改失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>修改{title}</DialogTitle>
        </DialogHeader>
        {type === 'textarea' ? (
          <Textarea
            value={editValue}
            onChange={(e) => setEditValue(e.target.value)}
            placeholder={placeholder}
            className="min-h-32"
            autoFocus
          />
        ) : (
          <Input
            value={editValue}
            onChange={(e) => setEditValue(e.target.value)}
            placeholder={placeholder}
            autoFocus
            onKeyDown={(e) => { if (e.key === 'Enter') handleSubmit() }}
          />
        )}
        <DialogFooter>
          <button
            onClick={() => onOpenChange(false)}
            className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground hover:bg-muted transition-colors"
          >
            取消
          </button>
          <button
            onClick={handleSubmit}
            disabled={submitting}
            className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
          >
            {submitting ? '保存中...' : '保存'}
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
