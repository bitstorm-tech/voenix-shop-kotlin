import { cva } from 'class-variance-authority'

export const toastVariants = cva(
  'group pointer-events-auto relative flex w-full items-center justify-between gap-4 overflow-hidden rounded-lg border p-4 shadow-lg ring-1 ring-black/5 transition-all dark:ring-white/10 data-[swipe=cancel]:translate-x-0 data-[swipe=end]:translate-x-[var(--reka-toast-swipe-end-x)] data-[swipe=move]:translate-x-[var(--reka-toast-swipe-move-x)] data-[swipe=move]:transition-none data-[state=open]:animate-in data-[state=closed]:animate-out data-[swipe=end]:animate-out data-[state=open]:fade-in-0 data-[state=open]:slide-in-from-bottom-full data-[state=closed]:fade-out-0 data-[state=closed]:slide-out-to-bottom-full',
  {
    variants: {
      variant: {
        default: 'border-border/80 bg-popover text-popover-foreground',
        success: 'border-success-border bg-success-soft text-success-foreground',
        destructive: 'border-destructive-border bg-destructive-soft text-destructive-foreground',
      },
    },
    defaultVariants: {
      variant: 'default',
    },
  },
)
