import type { VariantProps } from 'class-variance-authority'
import { cva } from 'class-variance-authority'

export { default as Toggle } from './Toggle.vue'

export const toggleVariants = cva(
  'inline-flex cursor-pointer items-center justify-center gap-2 rounded-md text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50 data-[state=on]:bg-accent data-[state=on]:text-accent-foreground [&_svg]:pointer-events-none [&_svg]:size-4 [&_svg]:shrink-0',
  {
    variants: {
      variant: {
        default: 'hover:bg-muted hover:text-foreground',
        outline: 'border border-input bg-background shadow-sm hover:bg-accent',
        toolbar: 'text-muted-foreground hover:bg-accent hover:text-accent-foreground',
        expressive:
          'rounded-md border border-primary/20 bg-primary/5 text-primary hover:bg-primary/10 data-[state=on]:bg-primary data-[state=on]:text-primary-foreground',
      },
      size: {
        default: 'h-9 px-3',
        sm: 'h-8 px-2.5 text-xs',
        lg: 'h-10 px-4',
        icon: 'size-9',
        toolbar: 'size-8',
      },
    },
    defaultVariants: {
      variant: 'default',
      size: 'default',
    },
  },
)

export type ToggleVariants = VariantProps<typeof toggleVariants>
