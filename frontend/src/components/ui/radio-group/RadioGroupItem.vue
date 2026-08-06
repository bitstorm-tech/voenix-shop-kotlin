<script setup lang="ts">
import type { RadioGroupItemEmits, RadioGroupItemProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import { reactiveOmit } from '@vueuse/core'
import { Circle } from 'lucide-vue-next'
import { RadioGroupIndicator, RadioGroupItem, useForwardPropsEmits } from 'reka-ui'
import { cn } from '@/lib/utils'

const props = defineProps<RadioGroupItemProps & { class?: HTMLAttributes['class'] }>()
const emits = defineEmits<RadioGroupItemEmits>()

const delegatedProps = reactiveOmit(props, 'class')

const forwarded = useForwardPropsEmits(delegatedProps, emits)
</script>

<template>
  <RadioGroupItem
    v-bind="forwarded"
    :class="
      cn(
        'inline-flex h-8 min-w-8 cursor-pointer select-none items-center justify-center gap-2 rounded-md border border-input bg-background px-2.5 text-xs font-medium text-muted-foreground shadow-sm transition-colors hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50 data-[state=checked]:border-primary data-[state=checked]:bg-primary data-[state=checked]:text-primary-foreground',
        props.class,
      )
    "
  >
    <RadioGroupIndicator as-child>
      <Circle class="size-2 fill-current text-current" />
    </RadioGroupIndicator>
    <slot />
  </RadioGroupItem>
</template>
