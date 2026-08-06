<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ImageOff } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import type { PromptDto } from '@/stores/shop/prompts'

const props = defineProps<{
  prompt: PromptDto
  imageUrl?: string
}>()

const { t } = useI18n()
const hasImageError = shallowRef(false)

const shouldShowImage = computed(() => props.imageUrl !== undefined && !hasImageError.value)

const wizardRoute = computed(() => ({
  name: 'wizard',
  query: {
    promptId: String(props.prompt.id),
  },
}))

const categoryLabel = computed(() => {
  const parts = [props.prompt.category?.name, props.prompt.subcategory?.name].filter(Boolean)
  return parts.join(' / ')
})

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
    :to="wizardRoute"
    class="landing-style-card group flex min-w-0 flex-col overflow-hidden rounded-lg border bg-card text-left text-inherit no-underline shadow-sm transition-all duration-200 hover:-translate-y-0.5 hover:border-[var(--surface-card-hover-border)] hover:shadow-[var(--shadow-elevated-hover)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 motion-reduce:transition-none motion-reduce:hover:translate-y-0"
    :aria-label="t('landing.styles.openStyle', { title: prompt.title })"
  >
    <div
      class="landing-style-card__media relative aspect-square overflow-hidden bg-background-soft"
    >
      <img
        v-if="shouldShowImage"
        :src="imageUrl"
        :alt="prompt.title"
        class="landing-style-card__image block size-full object-cover transition-transform duration-200 group-hover:scale-[1.035] motion-reduce:transition-none motion-reduce:group-hover:scale-100"
        width="400"
        height="400"
        loading="lazy"
        decoding="async"
        @error="handleImageError"
      />
      <div
        v-else
        class="landing-style-card__fallback flex min-h-44 size-full flex-col items-center justify-center gap-2 p-4 text-center text-xs font-bold text-foreground-muted"
      >
        <ImageOff
          class="landing-style-card__fallback-icon size-8 text-foreground-faint"
          aria-hidden="true"
        />
        <span>{{ t('landing.styles.noImage') }}</span>
      </div>
    </div>

    <div class="landing-style-card__body grid min-h-[4.5rem] gap-1 p-3">
      <h3
        class="landing-style-card__title line-clamp-2 text-[0.92rem] font-extrabold leading-tight text-foreground"
      >
        {{ prompt.title }}
      </h3>
      <p
        v-if="categoryLabel"
        class="landing-style-card__meta truncate text-xs font-semibold text-foreground-muted"
      >
        {{ categoryLabel }}
      </p>
    </div>
  </RouterLink>
</template>
