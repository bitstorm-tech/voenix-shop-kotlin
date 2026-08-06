import type { VariantProps } from 'class-variance-authority'
import { cva } from 'class-variance-authority'

export { default as Badge } from './Badge.vue'

export const badgeVariants = cva(
  'inline-flex rounded-sm px-3 py-1 text-xs font-semibold uppercase tracking-[0.12em]',
  {
    variants: {
      variant: {
        success: 'bg-success-soft text-success-foreground',
        warning: 'bg-warning-soft text-warning-foreground',
        muted: 'bg-muted text-muted-foreground',
      },
    },
    defaultVariants: {
      variant: 'muted',
    },
  },
)

export type BadgeVariants = VariantProps<typeof badgeVariants>
