<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { cn } from '@/lib/utils'

const props = withDefaults(
  defineProps<{
    alt?: string
    class?: HTMLAttributes['class']
    disabled?: boolean
    selected?: boolean
    src?: string
  }>(),
  {
    alt: '',
    selected: false,
  },
)
</script>

<template>
  <button
    type="button"
    :aria-pressed="selected"
    :data-state="selected ? 'selected' : 'unselected'"
    :disabled="disabled"
    :class="
      cn(
        'relative inline-flex aspect-square w-16 overflow-hidden rounded-lg border border-border bg-muted shadow-sm transition-all hover:border-primary focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50 data-[state=selected]:border-primary data-[state=selected]:ring-2 data-[state=selected]:ring-primary/30',
        props.class,
      )
    "
  >
    <img v-if="src" :src="src" :alt="alt" class="size-full object-cover" />
    <span v-else class="grid size-full place-content-center text-muted-foreground">
      <slot />
    </span>
    <slot v-if="src" name="overlay" />
  </button>
</template>
