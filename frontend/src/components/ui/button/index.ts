import type { VariantProps } from 'class-variance-authority'
import { cva } from 'class-variance-authority'

export { default as Button } from './Button.vue'

export const buttonVariants = cva(
  'inline-flex cursor-pointer items-center justify-center gap-2 whitespace-nowrap rounded-md text-sm font-semibold transition-all duration-300 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50 motion-safe:active:scale-[0.98] [&_svg]:pointer-events-none [&_svg]:size-4 [&_svg]:shrink-0',
  {
    variants: {
      variant: {
        default:
          'bg-primary text-primary-foreground shadow-xl hover:bg-primary/90 hover:shadow-2xl motion-safe:hover:scale-105',
        destructive:
          'bg-destructive text-white shadow-xl hover:bg-destructive/90 hover:text-white hover:shadow-2xl motion-safe:hover:scale-105',
        outline:
          'border border-input bg-background shadow-sm hover:bg-accent hover:text-accent-foreground hover:shadow-md',
        secondary:
          'bg-secondary text-secondary-foreground shadow-md hover:bg-secondary/80 hover:shadow-lg motion-safe:hover:scale-105',
        ghost: 'hover:bg-accent hover:text-accent-foreground',
        toolbar:
          'rounded-md text-muted-foreground hover:bg-accent hover:text-accent-foreground data-[state=active]:bg-accent data-[state=active]:text-accent-foreground',
        pill: 'border border-border bg-background text-foreground shadow-sm hover:border-primary/40 hover:bg-primary/5 hover:text-primary',
        navigation:
          'rounded-md text-muted-foreground hover:bg-accent hover:text-accent-foreground data-[state=active]:bg-accent data-[state=active]:text-accent-foreground',
        admin:
          'rounded-md border border-input bg-background text-foreground shadow-sm hover:bg-accent hover:text-accent-foreground',
        shop: 'bg-primary text-primary-foreground shadow-xl shadow-primary/20 hover:bg-primary-hover hover:shadow-2xl hover:shadow-primary/25 motion-safe:hover:scale-105',
        icon: 'rounded-md border border-input bg-background text-muted-foreground shadow-sm hover:bg-accent hover:text-accent-foreground',
        link: 'text-primary underline-offset-4 hover:underline',
      },
      size: {
        default: 'h-9 px-4 py-2',
        xs: 'h-7 rounded-sm px-2',
        sm: 'h-8 rounded-md px-3 text-xs',
        lg: 'h-14 px-10 text-base sm:text-lg',
        icon: 'h-9 w-9',
        'icon-sm': 'size-8',
        'icon-lg': 'size-10',
        toolbar: 'size-8 rounded-md p-0',
        'toolbar-sm': 'size-7 rounded-md p-0',
        pill: 'h-8 rounded-md px-3 text-xs',
        nav: 'h-9 rounded-md px-3',
        admin: 'h-7 rounded-md px-2.5 text-xs',
        shop: 'h-12 rounded-md px-8 text-base',
        compact: 'h-7 rounded-md px-2 text-xs',
      },
    },
    defaultVariants: {
      variant: 'default',
      size: 'default',
    },
  },
)

export type ButtonVariants = VariantProps<typeof buttonVariants>
