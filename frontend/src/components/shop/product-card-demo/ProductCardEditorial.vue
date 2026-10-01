<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowRight } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { formatPrice } from '@/lib/formatPrice'
import type { TshirtDto } from '@/stores/shop/catalog'
import ShirtMedia from './ShirtMedia.vue'
import { useTshirtCardModel } from './useTshirtCardModel'

/**
 * Alternative D - "Editorial": the picture carries the card. Colours are only summarised as an
 * overlapping stack - choosing one happens on the next step - so the card reads like a shop
 * window: picture, name, one meta line, call to action.
 */
const props = defineProps<{ article: TshirtDto }>()

const { t } = useI18n()
const { activeColor, colors, sizeRange, imageUrl } = useTshirtCardModel(() => props.article)

const STACKED_SWATCHES = 5
const stackedColors = computed(() => colors.value.slice(0, STACKED_SWATCHES))
const isDiscounted = computed(
  () => props.article.regularPrice !== null && props.article.regularPrice > props.article.price,
)
</script>

<template>
  <article
    class="group flex flex-col gap-4 rounded-2xl border border-transparent p-2 pb-4 transition-all duration-300 hover:border-border hover:bg-surface-card"
  >
    <ShirtMedia
      :image-url="imageUrl"
      :color="activeColor"
      :alt="article.name"
      class="aspect-[4/5] rounded-xl"
    >
      <div
        class="absolute bottom-3 right-3 z-10 flex items-baseline gap-2 rounded-full bg-foreground px-3.5 py-1.5 text-sm font-bold text-background shadow-lg"
      >
        <span v-if="isDiscounted" class="text-xs font-medium line-through opacity-60">
          {{ formatPrice(article.regularPrice as number) }}
        </span>
        {{ formatPrice(article.price) }}
      </div>
    </ShirtMedia>

    <div class="grid gap-1.5 px-2">
      <h3 class="text-xl font-bold leading-tight text-foreground">{{ article.name }}</h3>
      <p class="line-clamp-1 text-sm text-muted-foreground">{{ article.descriptionShort }}</p>
    </div>

    <div class="flex items-center justify-between gap-3 px-2 text-sm text-muted-foreground">
      <span v-if="colors.length > 1" class="inline-flex items-center gap-2.5">
        <span class="flex" aria-hidden="true">
          <span
            v-for="color in stackedColors"
            :key="color.name"
            class="-ml-1.5 size-5 rounded-full border border-black/10 ring-2 ring-[var(--background)] first:ml-0"
            :style="{ background: color.hex }"
          />
        </span>
        {{ colors.length }} {{ t('productCard.colors') }}
      </span>
      <span v-if="sizeRange" class="font-semibold text-foreground">{{ sizeRange }}</span>
    </div>

    <div class="px-2">
      <Button class="w-full justify-between" size="lg">
        {{ t('productOverview.select') }}
        <ArrowRight class="transition-transform duration-300 group-hover:translate-x-1" />
      </Button>
    </div>
  </article>
</template>
