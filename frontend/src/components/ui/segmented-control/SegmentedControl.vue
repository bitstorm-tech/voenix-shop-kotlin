<script setup lang="ts">
import type { ToggleGroupRootEmits, ToggleGroupRootProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import { cva, type VariantProps } from 'class-variance-authority'
import { reactiveOmit } from '@vueuse/core'
import { ToggleGroupRoot, useForwardPropsEmits } from 'reka-ui'
import { cn } from '@/lib/utils'

const segmentedControlVariants = cva('inline-flex items-center text-muted-foreground', {
  variants: {
    variant: {
      default: 'rounded-lg bg-muted p-1',
      editor:
        'rounded-lg border border-[oklch(0.92_0.01_0_/_0.6)] bg-[oklch(0.95_0.005_0_/_0.8)] p-1 shadow-[0_1px_3px_oklch(0_0_0_/_0.04)] dark:border-border dark:bg-[oklch(0.22_0.005_0_/_0.8)]',
    },
  },
  defaultVariants: {
    variant: 'default',
  },
})

type SegmentedControlVariants = VariantProps<typeof segmentedControlVariants>

interface Props extends ToggleGroupRootProps {
  class?: HTMLAttributes['class']
  variant?: SegmentedControlVariants['variant']
}

const props = defineProps<Props>()
const emits = defineEmits<ToggleGroupRootEmits>()

const delegatedProps = reactiveOmit(props, 'class', 'variant')
const forwarded = useForwardPropsEmits(delegatedProps, emits)
</script>

<template>
  <ToggleGroupRoot
    v-bind="forwarded"
    :class="cn(segmentedControlVariants({ variant: props.variant }), props.class)"
  >
    <slot />
  </ToggleGroupRoot>
</template>
