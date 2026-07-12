import * as React from "react"
import { Loader2Icon } from "lucide-react"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/lib/utils"

// ─── Spinner ─────────────────────────────────────────────────────────────

const spinnerVariants = cva(
  "animate-spin text-muted-foreground",
  {
    variants: {
      size: {
        sm: "h-4 w-4",
        md: "h-6 w-6",
        lg: "h-8 w-8",
        xl: "h-12 w-12",
      },
    },
    defaultVariants: {
      size: "md",
    },
  }
)

interface SpinnerProps extends VariantProps<typeof spinnerVariants> {
  className?: string
}

function Spinner({ size, className }: SpinnerProps) {
  return (
    <Loader2Icon
      className={cn(spinnerVariants({ size }), className)}
      role="status"
      aria-label="加载中"
    />
  )
}

// ─── Skeleton ────────────────────────────────────────────────────────────

interface SkeletonProps extends React.ComponentProps<"div"> {
  /** 行数（仅对 text 模式生效） */
  lines?: number
  /** 每行宽度，数组长度 = 行数 */
  widths?: string[]
}

function Skeleton({ className, ...props }: SkeletonProps) {
  return (
    <div
      data-slot="skeleton"
      className={cn("animate-pulse rounded-lg bg-muted", className)}
      {...props}
    />
  )
}

function TextSkeleton({ lines = 3, className, widths, ...props }: SkeletonProps & { lines?: number; widths?: string[] }) {
  return (
    <div data-slot="text-skeleton" className={cn("flex flex-col gap-2", className)} {...props}>
      {Array.from({ length: lines }).map((_, i) => (
        <Skeleton
          key={i}
          className="h-3"
          style={{ width: widths?.[i] ?? (i === lines - 1 ? "60%" : "100%") }}
        />
      ))}
    </div>
  )
}

// ─── Loading Overlay ─────────────────────────────────────────────────────

interface LoadingOverlayProps {
  loading: boolean
  children?: React.ReactNode
  spinnerSize?: "sm" | "md" | "lg" | "xl"
  text?: string
}

function LoadingOverlay({ loading, children, spinnerSize, text }: LoadingOverlayProps) {
  if (!loading) return <>{children}</>

  return (
    <div className="relative">
      {children && (
        <div className="pointer-events-none select-none opacity-30 blur-[1px]">
          {children}
        </div>
      )}
      <div className="absolute inset-0 flex flex-col items-center justify-center gap-2">
        <Spinner size={spinnerSize || "lg"} className="text-primary" />
        {text && (
          <p className="text-sm text-muted-foreground animate-pulse">{text}</p>
        )}
      </div>
    </div>
  )
}

// ─── Page Loader ─────────────────────────────────────────────────────────

function PageLoader({ text }: { text?: string }) {
  return (
    <div className="flex h-full min-h-[200px] flex-col items-center justify-center gap-3">
      <Spinner size="lg" className="text-primary" />
      {text && (
        <p className="text-sm text-muted-foreground">{text}</p>
      )}
    </div>
  )
}

export {
  Spinner,
  Skeleton,
  TextSkeleton,
  LoadingOverlay,
  PageLoader,
}