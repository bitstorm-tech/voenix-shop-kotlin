<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { SwatchButton } from '@/components/ui/swatch-button'
import ProductPrice from '@/components/shop/ProductPrice.vue'
import type { TshirtDto } from '@/stores/shop/catalog'
import ShirtMedia from './ShirtMedia.vue'
import { useTshirtCardModel } from './useTshirtCardModel'

/**
 * Alternative E - "Farbleiste": the colours become one full-width bar of capsules that share the
 * card width, so every colour is a tall target however many there are. The selected capsule
 * grows; its name and the sorted size list sit below the bar.
 */
const props = defineProps<{ article: TshirtDto }>()

const { t } = useI18n()
const { activeVariant, activeColor, colors, sizes, imageUrl, selectColor } = useTshirtCardModel(
  () => props.article,
)
</script>

<template>
  <article
    class="group flex flex-col overflow-hidden rounded-2xl border border-border bg-surface-card shadow-[0_1px_3px_oklch(0_0_0_/_0.04),0_4px_16px_oklch(0_0_0_/_0.03)] transition-all duration-300 hover:-translate-y-1 hover:border-[var(--surface-card-hover-border)] motion-reduce:hover:translate-y-0"
  >
    <ShirtMedia
      :image-url="imageUrl"
      :color="activeColor"
      :alt="article.name"
      class="aspect-square"
    />

    <div class="flex flex-1 flex-col gap-4 p-5">
      <div class="grid gap-2">
        <div class="grid gap-1.5">
          <h3 class="text-lg font-bold leading-tight text-foreground">{{ article.name }}</h3>
          <ProductPrice :cents="article.price" :regular-cents="article.regularPrice" size="md" />
        </div>
        <p class="line-clamp-2 text-sm leading-relaxed text-muted-foreground">
          {{ article.descriptionShort }}
        </p>
      </div>

      <div v-if="colors.length > 1" class="grid gap-2">
        <div class="flex h-9 items-center gap-1">
          <SwatchButton
            v-for="color in colors"
            :key="color.name"
            class="h-6 w-auto min-w-0 flex-1 rounded-full border-0 p-0 shadow-none ring-offset-2 ring-offset-[var(--surface-card)] transition-[height] duration-200 hover:h-8 data-[state=selected]:h-9 data-[state=selected]:ring-2 data-[state=selected]:ring-primary"
            :color="color.hex"
            :label="color.name"
            :title="color.name"
            :selected="color.selected"
            @click="selectColor(color.name)"
          />
        </div>
        <div class="flex items-baseline justify-between gap-3 text-xs">
          <span class="font-semibold text-foreground">{{ activeVariant?.colorName }}</span>
          <span class="text-muted-foreground"
            >{{ colors.length }} {{ t('productCard.colors') }}</span
          >
        </div>
      </div>

      <p
        v-if="sizes.length > 0"
        class="flex flex-wrap gap-x-3 gap-y-1 text-xs font-semibold tracking-wide text-foreground-muted"
      >
        <span v-for="size in sizes" :key="size">{{ size }}</span>
      </p>

      <Button class="mt-auto w-full" size="lg">{{ t('productOverview.select') }}</Button>
    </div>
  </article>
</template>
