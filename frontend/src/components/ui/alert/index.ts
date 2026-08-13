import type { VariantProps } from 'class-variance-authority'
import { cva } from 'class-variance-authority'

export { default as Alert } from './Alert.vue'

export const alertVariants = cva('rounded-lg border px-4 py-3 text-sm', {
  variants: {
    variant: {
      info: 'border-border bg-muted/20 text-muted-foreground',
      // For the outcome that is neither a success nor a failure — something was written, and the
      // reader still has to act on it.
      warning: 'border-warning-border bg-warning-soft text-warning-foreground',
      destructive: 'border-destructive/20 bg-destructive/8 text-destructive',
    },
  },
  defaultVariants: {
    variant: 'destructive',
  },
})

export type AlertVariants = VariantProps<typeof alertVariants>
