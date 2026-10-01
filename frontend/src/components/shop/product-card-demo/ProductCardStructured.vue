<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { SwatchButton } from '@/components/ui/swatch-button'
import ProductPrice from '@/components/shop/ProductPrice.vue'
import type { TshirtDto } from '@/stores/shop/catalog'
import ShirtMedia from './ShirtMedia.vue'
import { useTshirtCardModel } from './useTshirtCardModel'

/**
 * Alternative C - "Strukturiert": labelled sections divided by hairlines. The swatches fill a grid
 * that spans the card width, so 18 colours become two even rows of comfortable targets, and the
 * sizes are sorted chips instead of one dotted line.
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
      class="aspect-[4/3]"
    />

    <div class="flex flex-1 flex-col">
      <div class="grid gap-2 p-5 pb-4">
        <div class="grid gap-1.5">
          <h3 class="text-lg font-bold leading-tight text-foreground">{{ article.name }}</h3>
          <ProductPrice :cents="article.price" :regular-cents="article.regularPrice" size="md" />
        </div>
        <p class="line-clamp-2 text-sm leading-relaxed text-muted-foreground">
          {{ article.descriptionShort }}
        </p>
      </div>

      <section v-if="colors.length > 1" class="grid gap-3 border-t border-border px-5 py-4">
        <div class="flex items-baseline justify-between gap-3 text-xs">
          <span class="font-semibold uppercase tracking-[0.12em] text-foreground-muted">
            {{ t('productCard.colors') }}
          </span>
          <span class="truncate font-semibold text-foreground">{{ activeVariant?.colorName }}</span>
        </div>
        <div class="grid grid-cols-[repeat(auto-fill,minmax(1.75rem,1fr))] gap-1.5">
          <SwatchButton
            v-for="color in colors"
            :key="color.name"
            class="aspect-square size-auto w-full p-0.5"
            :color="color.hex"
            :label="color.name"
            :title="color.name"
            :selected="color.selected"
            @click="selectColor(color.name)"
          />
        </div>
      </section>

      <section v-if="sizes.length > 0" class="grid gap-3 border-t border-border px-5 py-4">
        <span class="text-xs font-semibold uppercase tracking-[0.12em] text-foreground-muted">
          Größen
        </span>
        <ul class="flex flex-wrap gap-1.5">
          <li
            v-for="size in sizes"
            :key="size"
            class="min-w-9 rounded-md border border-border px-2 py-1 text-center text-xs font-semibold text-foreground"
          >
            {{ size }}
          </li>
        </ul>
      </section>

      <div class="mt-auto p-5 pt-1">
        <Button class="w-full" size="lg">{{ t('productOverview.select') }}</Button>
      </div>
    </div>
  </article>
</template>
