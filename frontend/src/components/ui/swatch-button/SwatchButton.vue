<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { computed } from 'vue'
import { cn } from '@/lib/utils'

const props = withDefaults(
  defineProps<{
    class?: HTMLAttributes['class']
    color: string
    disabled?: boolean
    label?: string
    selected?: boolean
  }>(),
  {
    selected: false,
  },
)

const accessibleLabel = computed(() => props.label ?? `Select color ${props.color}`)
</script>

<template>
  <button
    type="button"
    :aria-label="accessibleLabel"
    :aria-pressed="selected"
    :data-state="selected ? 'selected' : 'unselected'"
    :disabled="disabled"
    :class="
      cn(
        'inline-flex size-9 items-center justify-center rounded-full border border-border bg-background p-1 shadow-sm transition-all hover:border-primary focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50 data-[state=selected]:border-primary data-[state=selected]:ring-2 data-[state=selected]:ring-primary/30',
        props.class,
      )
    "
  >
    <span
      class="size-full rounded-full border border-black/10"
      :style="{ background: color }"
      aria-hidden="true"
    />
    <slot />
  </button>
</template>
