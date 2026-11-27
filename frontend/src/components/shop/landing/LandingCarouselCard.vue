<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { RouterLink, type RouteLocationRaw } from 'vue-router'
import { ImageOff } from 'lucide-vue-next'

const props = defineProps<{
  to: RouteLocationRaw
  title: string
  meta?: string
  imageUrl?: string
  imageLoading?: 'eager' | 'lazy'
  noImageLabel: string
}>()

const hasImageError = shallowRef(false)

const shouldShowImage = computed(() => props.imageUrl !== undefined && !hasImageError.value)

watch(
  () => props.imageUrl,
  () => {
    hasImageError.value = false
  },
)

function handleImageError() {
  hasImageError.value = true
}
</script>

<template>
  <RouterLink
    :to="to"
    class="landing-carousel-card group flex min-w-0 flex-1 flex-col overflow-hidden rounded-lg border bg-card text-left text-inherit no-underline shadow-sm transition-all duration-200 hover:-translate-y-0.5 hover:border-[var(--surface-card-hover-border)] hover:shadow-[var(--shadow-elevated-hover)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 motion-reduce:transition-none motion-reduce:hover:translate-y-0"
  >
    <div
      class="landing-carousel-card__media relative aspect-square overflow-hidden bg-background-soft"
    >
      <img
        v-if="shouldShowImage"
        :src="imageUrl"
        :alt="title"
        class="landing-carousel-card__image block size-full object-cover transition-transform duration-200 group-hover:scale-[1.035] motion-reduce:transition-none motion-reduce:group-hover:scale-100"
        width="400"
        height="400"
        :loading="imageLoading ?? 'lazy'"
        decoding="async"
        @error="handleImageError"
      />
      <div
        v-else
        class="landing-carousel-card__fallback flex min-h-44 size-full flex-col items-center justify-center gap-2 p-4 text-center text-xs font-bold text-foreground-muted"
      >
        <ImageOff
          class="landing-carousel-card__fallback-icon size-8 text-foreground-faint"
          aria-hidden="true"
        />
        <span>{{ noImageLabel }}</span>
      </div>
    </div>

    <div class="landing-carousel-card__body grid min-h-[4.5rem] gap-1 p-3">
      <h3
        class="landing-carousel-card__title line-clamp-2 text-[0.92rem] font-extrabold leading-tight text-foreground"
      >
        {{ title }}
      </h3>
      <p
        v-if="meta"
        class="landing-carousel-card__meta truncate text-xs font-semibold text-foreground-muted"
      >
        {{ meta }}
      </p>
    </div>
  </RouterLink>
</template>
