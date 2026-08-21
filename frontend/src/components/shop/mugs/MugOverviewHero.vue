<script setup lang="ts">
import MugHeroImage from '@/assets/landing/mug2.png'
import { Palette, PackageCheck } from 'lucide-vue-next'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Badge } from '@/components/ui/badge'

const props = defineProps<{
  activeCategoryName: string | null
  visibleCount: number
  totalCount: number
  isLoading: boolean
}>()

const { t } = useI18n()

const title = computed(() =>
  props.activeCategoryName
    ? t('productOverview.hero.categoryTitle', { category: props.activeCategoryName })
    : t('productOverview.hero.title'),
)

const countLabel = computed(() => {
  if (props.isLoading) {
    return t('productOverview.hero.loading')
  }

  return t('productOverview.hero.count', {
    count: props.visibleCount,
    total: props.totalCount,
  })
})
</script>

<template>
  <section
    class="relative grid min-h-60 grid-cols-1 items-center gap-5 overflow-hidden border-b bg-gradient-to-br from-background via-background to-background-warm md:grid-cols-[minmax(0,1fr)_minmax(220px,0.44fr)] md:gap-12"
  >
    <div class="max-w-3xl pb-0 pt-6 md:py-9">
      <Badge variant="muted" class="mb-3 border border-primary/25 bg-primary/10 text-primary">
        {{ activeCategoryName ?? t('productOverview.hero.kicker') }}
      </Badge>
      <h1
        class="max-w-[11ch] text-4xl font-black leading-none tracking-normal text-foreground md:text-6xl"
      >
        {{ title }}
      </h1>
      <p class="mt-3 max-w-xl text-base leading-relaxed text-foreground-muted md:text-lg">
        {{ t('productOverview.hero.subtitle') }}
      </p>

      <div class="mt-4 flex flex-wrap gap-2" aria-live="polite">
        <span
          class="inline-flex min-h-9 items-center gap-2 rounded-md border bg-surface-glass px-3 py-1.5 text-sm font-semibold text-foreground-soft shadow-sm"
        >
          <Palette class="size-4 text-primary" aria-hidden="true" />
          {{ t('productOverview.hero.designReady') }}
        </span>
        <span
          class="inline-flex min-h-9 items-center gap-2 rounded-md border bg-surface-glass px-3 py-1.5 text-sm font-semibold text-foreground-soft shadow-sm"
        >
          <PackageCheck class="size-4 text-primary" aria-hidden="true" />
          {{ countLabel }}
        </span>
      </div>
    </div>

    <div
      class="relative flex min-h-[11.5rem] items-end justify-end md:block md:min-h-56"
      aria-hidden="true"
    >
      <img
        :src="MugHeroImage"
        alt=""
        class="relative -right-4 w-[min(10.75rem,50vw)] rotate-[-5deg] drop-shadow-xl md:absolute md:bottom-[-3.75rem] md:right-[clamp(-2.5rem,-4vw,-1rem)] md:w-[min(22rem,116%)]"
        width="553"
        height="630"
        loading="eager"
        decoding="async"
      />
      <div
        class="absolute bottom-6 left-0 rounded-md border border-primary/40 bg-primary px-3 py-2 text-sm font-extrabold text-primary-foreground shadow-lg shadow-primary/25 md:bottom-5 md:left-auto md:right-4"
      >
        {{ t('productOverview.hero.price') }}
      </div>
    </div>
  </section>
</template>
