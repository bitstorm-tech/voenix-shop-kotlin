import type { VariantProps } from 'class-variance-authority'
import { cva } from 'class-variance-authority'

export { default as Alert } from './Alert.vue'

export const alertVariants = cva('rounded-lg border px-4 py-3 text-sm', {
  variants: {
    variant: {
      info: 'border-border bg-muted/20 text-muted-foreground',
      destructive: 'border-destructive/20 bg-destructive/8 text-destructive',
    },
  },
  defaultVariants: {
    variant: 'destructive',
  },
})

export type AlertVariants = VariantProps<typeof alertVariants>
