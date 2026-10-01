<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { cn } from '@/lib/utils'

/**
 * The picture area of a demo card: the variant photo when there is one, otherwise a shirt
 * silhouette in the active colour, so the fixture data still shows what a colour click changes.
 */
const props = defineProps<{
  imageUrl: string | null
  color: string
  alt: string
  class?: HTMLAttributes['class']
}>()
</script>

<template>
  <div
    :class="cn('relative overflow-hidden bg-[var(--surface-image-bg)]', props.class)"
    :style="{
      backgroundImage: `radial-gradient(circle at 50% 70%, color-mix(in oklch, ${color} 22%, transparent) 0%, transparent 60%)`,
    }"
  >
    <img
      v-if="imageUrl"
      :src="imageUrl"
      :alt="alt"
      class="absolute inset-0 size-full object-contain p-6 drop-shadow-[0_0.9rem_1.1rem_oklch(0_0_0_/_0.18)] transition-transform duration-300 group-hover:scale-105 motion-reduce:transition-none"
    />
    <svg
      v-else
      viewBox="0 0 100 100"
      class="absolute inset-0 m-auto size-[70%] drop-shadow-[0_0.9rem_1.1rem_oklch(0_0_0_/_0.18)] transition-transform duration-300 group-hover:scale-105 motion-reduce:transition-none"
      role="img"
      :aria-label="alt"
    >
      <path
        d="M36 12 L20 18 L5 34 L16 45 L25 38 L25 90 L75 90 L75 38 L84 45 L95 34 L80 18 L64 12 C61 18 56 21 50 21 C44 21 39 18 36 12 Z"
        :fill="color"
        stroke="oklch(0 0 0 / 0.14)"
        stroke-width="0.8"
        stroke-linejoin="round"
        class="transition-[fill] duration-300"
      />
    </svg>
    <slot />
  </div>
</template>
