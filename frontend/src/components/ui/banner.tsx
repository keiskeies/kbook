import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/lib/utils"

const bannerVariants = cva(
  "flex items-start gap-3 rounded-xl border px-4 py-3 text-sm transition-all duration-200",
  {
    variants: {
      variant: {
        info: "border-info/20 bg-info/5 text-foreground [&>svg]:text-info",
        success: "border-success/20 bg-success/5 text-foreground [&>svg]:text-success",
        warning: "border-warning/20 bg-warning/5 text-foreground [&>svg]:text-warning",
        danger: "border-danger/20 bg-danger/5 text-foreground [&>svg]:text-danger",
      },
      size: {
        sm: "py-2 text-xs",
        md: "py-3 text-sm",
        lg: "py-4 text-base",
      },
    },
    defaultVariants: {
      variant: "info",
      size: "md",
    },
  }
)

export interface BannerProps
  extends React.ComponentProps<"div">,
    VariantProps<typeof bannerVariants> {
  /** 左侧图标（可选） */
  icon?: React.ReactNode
  /** 右上角关闭按钮（可选） */
  onClose?: () => void
}

function Banner({
  className,
  variant,
  size,
  icon,
  onClose,
  children,
  ...props
}: BannerProps) {
  return (
    <div
      data-slot="banner"
      className={cn(bannerVariants({ variant, size }), className)}
      role="alert"
      {...props}
    >
      {icon && (
        <div className="flex shrink-0 items-start pt-0.5">{icon}</div>
      )}
      <div className="flex-1 min-w-0">{children}</div>
      {onClose && (
        <button
          onClick={onClose}
          className="flex h-5 w-5 shrink-0 items-center justify-center rounded-md text-muted-foreground/50 hover:text-muted-foreground hover:bg-muted transition-colors"
          aria-label="关闭"
        >
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <line x1="18" y1="6" x2="6" y2="18" />
            <line x1="6" y1="6" x2="18" y2="18" />
          </svg>
        </button>
      )}
    </div>
  )
}

export { Banner }