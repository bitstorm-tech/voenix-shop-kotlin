<script setup lang="ts">
import type { PrimitiveProps } from 'reka-ui'
import type { HTMLAttributes } from 'vue'
import { Primitive } from 'reka-ui'
import { cn } from '@/lib/utils'

interface Props extends PrimitiveProps {
  class?: HTMLAttributes['class']
  disabled?: boolean
  selected?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  as: 'button',
  selected: false,
})
</script>

<template>
  <Primitive
    :as="as"
    :as-child="asChild"
    :aria-disabled="disabled || undefined"
    :aria-pressed="as === 'button' ? selected : undefined"
    :data-state="selected ? 'selected' : 'unselected'"
    :disabled="as === 'button' ? disabled : undefined"
    :class="
      cn(
        'group relative flex w-full select-none rounded-lg border border-border bg-background p-4 text-left text-foreground shadow-sm transition-colors hover:border-primary/50 hover:bg-primary/5 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50 data-[state=selected]:border-primary data-[state=selected]:bg-primary/5 data-[state=selected]:shadow-md',
        props.class,
      )
    "
  >
    <slot :selected="selected" :disabled="disabled" />
  </Primitive>
</template>
