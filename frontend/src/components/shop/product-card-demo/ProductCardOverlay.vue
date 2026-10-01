<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { SwatchButton } from '@/components/ui/swatch-button'
import ProductPrice from '@/components/shop/ProductPrice.vue'
import type { TshirtDto } from '@/stores/shop/catalog'
import ShirtMedia from './ShirtMedia.vue'
import { useTshirtCardModel } from './useTshirtCardModel'

/**
 * Alternative B - "Overlay": the colour picker moves onto the picture as a glass bar that scrolls
 * sideways, the price sits on the image as a tag, and the content only keeps text and one meta line.
 */
const props = defineProps<{ article: TshirtDto }>()

const { t } = useI18n()
const { activeVariant, activeColor, colors, sizeRange, imageUrl, selectColor } = useTshirtCardModel(
  () => props.article,
)
</script>

<template>
  <article
    class="group flex flex-col overflow-hidden rounded-2xl border border-border bg-surface-card shadow-[0_1px_3px_oklch(0_0_0_/_0.04),0_4px_16px_oklch(0_0_0_/_0.03)] transition-all duration-300 hover:-translate-y-1 hover:border-[var(--surface-card-hover-border)] motion-reduce:hover:translate-y-0"
  >
    <ShirtMedia :image-url="imageUrl" :color="activeColor" :alt="article.name" class="aspect-[4/5]">
      <div
        class="absolute left-3 top-3 z-10 rounded-xl bg-background/85 px-3 py-1.5 shadow-sm backdrop-blur-md"
      >
        <ProductPrice
          :cents="article.price"
          :regular-cents="article.regularPrice"
          size="sm"
          :show-saving="false"
        />
      </div>

      <div
        v-if="colors.length > 1"
        class="absolute inset-x-3 bottom-3 z-10 rounded-full border border-white/15 bg-background/70 shadow-lg backdrop-blur-md"
      >
        <div
          class="scrollbar-hide flex gap-1.5 overflow-x-auto px-2 py-1.5 [mask-image:linear-gradient(90deg,transparent,black_12px,black_calc(100%-12px),transparent)] [overscroll-behavior-x:contain]"
        >
          <SwatchButton
            v-for="color in colors"
            :key="color.name"
            class="size-7 shrink-0 p-0.5"
            :color="color.hex"
            :label="color.name"
            :title="color.name"
            :selected="color.selected"
            @click="selectColor(color.name)"
          />
        </div>
      </div>
    </ShirtMedia>

    <div class="flex flex-1 flex-col gap-3 p-5">
      <h3 class="text-lg font-bold leading-tight text-foreground">{{ article.name }}</h3>
      <p class="line-clamp-2 text-sm leading-relaxed text-muted-foreground">
        {{ article.descriptionShort }}
      </p>
      <p class="flex flex-wrap items-center gap-x-2 text-xs font-semibold text-foreground-muted">
        <span>{{ activeVariant?.colorName }}</span>
        <span aria-hidden="true">·</span>
        <span>{{ colors.length }} {{ t('productCard.colors') }}</span>
        <template v-if="sizeRange">
          <span aria-hidden="true">·</span>
          <span>{{ sizeRange }}</span>
        </template>
      </p>
      <Button class="mt-auto w-full" size="lg">{{ t('productOverview.select') }}</Button>
    </div>
  </article>
</template>
