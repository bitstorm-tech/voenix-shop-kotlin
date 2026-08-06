<script setup lang="ts">
import type { ToggleGroupItemProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import { cva, type VariantProps } from 'class-variance-authority'
import { reactiveOmit } from '@vueuse/core'
import { ToggleGroupItem, useForwardProps } from 'reka-ui'
import { cn } from '@/lib/utils'

const segmentedControlItemVariants = cva(
  'inline-flex items-center justify-center font-medium focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50',
  {
    variants: {
      variant: {
        default:
          'min-h-7 min-w-7 gap-2 rounded-md px-3 py-1 text-sm transition-colors data-[state=on]:bg-background data-[state=on]:text-foreground data-[state=on]:shadow-sm',
        editor:
          'gap-1.5 whitespace-nowrap rounded-md border-0 px-3.5 py-1.5 text-[13px] transition-[background,box-shadow,color] duration-200 motion-reduce:transition-none data-[state=off]:bg-transparent data-[state=off]:text-muted-foreground data-[state=off]:hover:bg-[oklch(0.92_0.005_0_/_0.6)] data-[state=off]:hover:text-foreground data-[state=on]:bg-brand-gradient data-[state=on]:text-white data-[state=on]:shadow-[0_1px_4px_oklch(0.61_0.19_35_/_0.3),0_2px_8px_oklch(0.61_0.19_35_/_0.15)] dark:data-[state=off]:hover:bg-[oklch(0.3_0.005_0_/_0.6)]',
      },
    },
    defaultVariants: {
      variant: 'default',
    },
  },
)

type SegmentedControlItemVariants = VariantProps<typeof segmentedControlItemVariants>

interface Props extends ToggleGroupItemProps {
  class?: HTMLAttributes['class']
  variant?: SegmentedControlItemVariants['variant']
}

const props = defineProps<Props>()

const delegatedProps = reactiveOmit(props, 'class', 'variant')
const forwardedProps = useForwardProps(delegatedProps)
</script>

<template>
  <ToggleGroupItem
    v-bind="forwardedProps"
    :class="cn(segmentedControlItemVariants({ variant: props.variant }), props.class)"
  >
    <slot />
  </ToggleGroupItem>
</template>
