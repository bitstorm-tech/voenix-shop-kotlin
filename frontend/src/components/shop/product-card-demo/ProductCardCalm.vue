<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { SwatchButton } from '@/components/ui/swatch-button'
import ProductPrice from '@/components/shop/ProductPrice.vue'
import type { TshirtDto } from '@/stores/shop/catalog'
import ShirtMedia from './ShirtMedia.vue'
import { useTshirtCardModel } from './useTshirtCardModel'

/**
 * Alternative A - "Ruhig": no description, five large swatches with a "+n" to unfold the rest,
 * the active colour named, and the sizes reduced to their range.
 */
const props = defineProps<{ article: TshirtDto }>()

const { t } = useI18n()
const { activeVariant, activeColor, colors, sizeRange, imageUrl, selectColor } = useTshirtCardModel(
  () => props.article,
)

const VISIBLE_SWATCHES = 5
const expanded = shallowRef(false)
const visibleColors = computed(() =>
  expanded.value ? colors.value : colors.value.slice(0, VISIBLE_SWATCHES),
)
const hiddenCount = computed(() => colors.value.length - VISIBLE_SWATCHES)
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

    <div class="flex flex-1 flex-col gap-5 p-5">
      <div class="grid gap-1.5">
        <h3 class="text-lg font-bold leading-tight text-foreground">{{ article.name }}</h3>
        <ProductPrice :cents="article.price" :regular-cents="article.regularPrice" size="md" />
      </div>

      <div v-if="colors.length > 1" class="grid gap-2.5">
        <p class="text-sm text-muted-foreground">
          Farbe:
          <span class="font-semibold text-foreground">{{ activeVariant?.colorName }}</span>
        </p>
        <div class="flex flex-wrap items-center gap-1.5">
          <SwatchButton
            v-for="color in visibleColors"
            :key="color.name"
            class="size-8 p-0.5"
            :color="color.hex"
            :label="color.name"
            :title="color.name"
            :selected="color.selected"
            @click="selectColor(color.name)"
          />
          <Button
            v-if="hiddenCount > 0"
            variant="pill"
            size="xs"
            class="h-8 rounded-full px-3"
            :aria-expanded="expanded"
            @click="expanded = !expanded"
          >
            {{ expanded ? 'Weniger' : `+${hiddenCount}` }}
          </Button>
        </div>
      </div>

      <p v-if="sizeRange" class="text-sm text-muted-foreground">
        Größen <span class="font-semibold text-foreground">{{ sizeRange }}</span>
      </p>

      <Button class="mt-auto w-full" size="lg">{{ t('productOverview.select') }}</Button>
    </div>
  </article>
</template>
