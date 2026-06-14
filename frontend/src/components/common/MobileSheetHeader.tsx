import { X } from 'lucide-react'

interface MobileSheetHeaderProps {
  /** Optional icon node — rendered inside a h-9 w-9 rounded-xl bg-primary/10 container */
  icon?: React.ReactNode
  /** Main title text */
  title: string
  /** Optional subtitle / description */
  description?: string
  /** Optional action buttons rendered on the right side (before close button) */
  actions?: React.ReactNode
  /** Close handler — if provided, renders a close button; omit if Sheet's default close is used */
  onClose?: () => void
  /** Additional className for the header container */
  className?: string
}

/**
 * Unified header for mobile bottom sheets.
 *
 * Design spec:
 * - Container: flex items-center gap-3, px-4 py-3, border-b border-border/20
 * - Icon container: h-9 w-9 rounded-xl bg-primary/10 (when icon provided)
 * - Icon size: h-5 w-5 text-primary
 * - Title: text-base font-bold truncate
 * - Description: text-xs text-muted-foreground truncate
 * - Close button: h-8 w-8 rounded-lg, X icon h-4 w-4
 * - Action buttons: h-8 w-8 rounded-lg each
 */
export default function MobileSheetHeader({
  icon,
  title,
  description,
  actions,
  onClose,
  className,
}: MobileSheetHeaderProps) {
  return (
    <div className={`shrink-0 flex items-center gap-3 border-b border-border/20 px-4 py-3 ${className ?? ''}`}>
      {icon && (
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-primary/10">
          {icon}
        </div>
      )}
      <div className="min-w-0 flex-1">
        <h3 className="text-base font-bold truncate">{title}</h3>
        {description && (
          <p className="text-xs text-muted-foreground truncate">{description}</p>
        )}
      </div>
      {actions && <div className="flex items-center gap-0.5">{actions}</div>}
      {onClose && (
        <button
          onClick={onClose}
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground"
          title="关闭"
        >
          <X className="h-4 w-4" />
        </button>
      )}
    </div>
  )
}