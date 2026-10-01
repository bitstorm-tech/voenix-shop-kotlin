<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { Check } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { SwatchButton } from '@/components/ui/swatch-button'
import ProductPrice from '@/components/shop/ProductPrice.vue'
import { sizeRange } from '@/lib/tshirtSizes'
import { variantExampleImageUrl } from '@/lib/variantExampleImage'
import {
  isMug,
  isTshirt,
  type MugVariantDto,
  type ShopArticle,
  type ShopArticleVariant,
  type TshirtVariantDto,
} from '@/stores/shop/catalog'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    article: ShopArticle
    activeVariant: ShopArticleVariant | null
    priceCents: number
    regularPriceCents?: number | null
    cardIndex?: number
    selected?: boolean
    as?: 'button' | 'article'
  }>(),
  {
    regularPriceCents: null,
    cardIndex: 0,
    selected: false,
    as: 'article',
  },
)

const emit = defineEmits<{
  click: []
  'select-variant': [variantId: number]
}>()

/**
 * One card for both article types. The discriminator decides what a variant means: a mug variant
 * is an outside and an inside colour, a shirt variant is one colour in one size - which is why a
 * shirt shows a swatch per *colour* and its sizes as a range, instead of a swatch per variant.
 *
 * The card stays calm however many colours an article has: the price sits on the picture as a tag,
 * the first few swatches are shown at a size a thumb can hit, and a "+n" button unfolds the rest.
 *
 * The active variant always comes from this article's own variant list, a correlation between two
 * props that TypeScript cannot see - so the article's type decides which variant it is.
 */
const mugVariant = computed<MugVariantDto | null>(() =>
  isMug(props.article) ? (props.activeVariant as MugVariantDto | null) : null,
)

const tshirtVariant = computed<TshirtVariantDto | null>(() =>
  isTshirt(props.article) ? (props.activeVariant as TshirtVariantDto | null) : null,
)

const outsideColor = computed(
  () => mugVariant.value?.outsideColorCode ?? tshirtVariant.value?.colorHex ?? '#cccccc',
)

const insideColor = computed(
  () => mugVariant.value?.insideColorCode ?? tshirtVariant.value?.colorHex ?? '#ffffff',
)

const exampleImageUrl = computed(() =>
  props.activeVariant?.exampleImageFilename
    ? variantExampleImageUrl(
        props.article.articleType,
        props.activeVariant.exampleImageFilename,
        400,
      )
    : null,
)

interface ColorSwatch {
  variantId: number
  label: string
  color: string
  selected: boolean
}

/**
 * A mug swatch shows the mug from above: the outside colour as the disc, the inside colour as the
 * opening in its middle. A mug that is one colour inside and out is a plain disc.
 */
function mugSwatchColor(variant: MugVariantDto): string {
  const { outsideColorCode: outside, insideColorCode: inside } = variant
  if (outside.toLowerCase() === inside.toLowerCase()) return outside

  return `radial-gradient(circle, ${inside} 0 40%, ${outside} calc(40% + 0.5px))`
}

const colorSwatches = computed<ColorSwatch[]>(() => {
  const article = props.article

  if (isMug(article)) {
    return article.variants.map((variant) => ({
      variantId: variant.id,
      label: variant.name,
      color: mugSwatchColor(variant),
      selected: props.activeVariant?.id === variant.id,
    }))
  }

  const variantsByColor = new Map<string, TshirtVariantDto[]>()

  for (const variant of article.variants) {
    const variants = variantsByColor.get(variant.colorName)
    if (variants === undefined) {
      variantsByColor.set(variant.colorName, [variant])
    } else {
      variants.push(variant)
    }
  }

  // Clicking a colour keeps the selected size when that colour is offered in it.
  const selectedSize = tshirtVariant.value?.size

  return [...variantsByColor.values()].map((variants) => {
    const first = variants[0]!
    const variant = variants.find((item) => item.size === selectedSize) ?? first

    return {
      variantId: variant.id,
      label: first.colorName,
      color: first.colorHex,
      selected: tshirtVariant.value?.colorName === first.colorName,
    }
  })
})

/** How many swatches the folded card shows before the "+n" button. */
const FOLDED_SWATCH_COUNT = 5

const swatchesExpanded = shallowRef(false)

const hiddenSwatchCount = computed(() =>
  Math.max(colorSwatches.value.length - FOLDED_SWATCH_COUNT, 0),
)

/**
 * The swatches on screen. A folded card always shows the selected colour: when it lies beyond the
 * folded range it takes the last visible place, so the selection never disappears behind "+n".
 */
const visibleSwatches = computed<ColorSwatch[]>(() => {
  const swatches = colorSwatches.value
  if (swatchesExpanded.value || hiddenSwatchCount.value === 0) return swatches

  const folded = swatches.slice(0, FOLDED_SWATCH_COUNT)
  const selected = swatches.find((swatch) => swatch.selected)
  if (selected && !folded.includes(selected)) folded[folded.length - 1] = selected
  return folded
})

/** The name of the active colour: a shirt's colour, or the name of a mug variant. */
const activeColorName = computed(
  () => tshirtVariant.value?.colorName ?? mugVariant.value?.name ?? null,
)

/** The sizes a shirt is offered in, as a range from the smallest to the largest. */
const sizeHint = computed(() => {
  const article = props.article
  if (!isTshirt(article)) return null

  return sizeRange([...new Set(article.variants.map((variant) => variant.size))])
})
</script>

<template>
  <component
    :is="props.as === 'button' ? 'article' : props.as"
    :role="props.as === 'button' ? 'button' : undefined"
    :tabindex="props.as === 'button' ? 0 : undefined"
    :aria-pressed="props.as === 'button' ? selected : undefined"
    class="product-card group relative flex flex-col overflow-hidden rounded-2xl border-[1.5px] border-border bg-surface-card text-left shadow-[0_1px_3px_oklch(0_0_0_/_0.04),0_4px_16px_oklch(0_0_0_/_0.03)] transition-all duration-300 [animation-delay:calc(var(--card-index,0)*60ms)] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[3px] focus-visible:outline-[oklch(0.61_0.19_35_/_0.82)] motion-safe:animate-enter-lift motion-reduce:animate-none motion-reduce:transition-none dark:shadow-[0_1px_3px_oklch(0_0_0_/_0.3),0_4px_16px_oklch(0_0_0_/_0.25)]"
    :class="[
      selected
        ? 'border-[oklch(0.61_0.19_35_/_0.7)] shadow-[0_0_0_1px_oklch(0.61_0.19_35_/_0.15),0_4px_12px_oklch(0.61_0.19_35_/_0.1),0_8px_24px_oklch(0.61_0.19_35_/_0.06)] hover:-translate-y-0.5 hover:shadow-[0_0_0_1px_oklch(0.61_0.19_35_/_0.2),0_6px_16px_oklch(0.61_0.19_35_/_0.12),0_12px_32px_oklch(0.61_0.19_35_/_0.08)] motion-reduce:hover:translate-y-0 dark:shadow-[0_0_0_1px_oklch(0.61_0.19_35_/_0.15),0_4px_12px_oklch(0.61_0.19_35_/_0.1),0_8px_24px_oklch(0.61_0.19_35_/_0.06)] dark:hover:shadow-[0_0_0_1px_oklch(0.61_0.19_35_/_0.2),0_6px_16px_oklch(0.61_0.19_35_/_0.12),0_12px_32px_oklch(0.61_0.19_35_/_0.08)]'
        : 'hover:-translate-y-1 hover:border-[var(--surface-card-hover-border)] hover:shadow-[0_7px_18px_oklch(0_0_0_/_0.07),0_18px_42px_oklch(0_0_0_/_0.08)] motion-reduce:hover:translate-y-0 dark:hover:shadow-[0_4px_12px_oklch(0_0_0_/_0.4),0_12px_32px_oklch(0_0_0_/_0.35)]',
      props.as === 'button' ? 'cursor-pointer' : 'cursor-default',
    ]"
    :style="{
      '--card-index': cardIndex,
      '--product-outside-color': outsideColor,
      '--product-inside-color': insideColor,
    }"
    @click="emit('click')"
    @keydown.enter.self.prevent="emit('click')"
    @keydown.space.self.prevent="emit('click')"
  >
    <div
      class="product-card-noise pointer-events-none absolute inset-0 z-[1] bg-repeat bg-[length:150px_150px] opacity-[0.25]"
    />

    <div
      v-if="selected"
      class="absolute right-3 top-3 z-10 flex items-center gap-1 rounded-sm bg-[linear-gradient(135deg,oklch(0.61_0.19_35),oklch(0.68_0.18_45))] px-2.5 py-1 text-xs font-semibold text-white shadow-[0_2px_8px_oklch(0.61_0.19_35_/_0.3)] motion-safe:animate-enter-pop motion-reduce:animate-none"
    >
      <Check class="size-3" />
      {{ t('productCard.selected') }}
    </div>

    <div class="product-card__media relative aspect-square w-full shrink-0 overflow-hidden">
      <div class="product-card-image-bg absolute inset-0" />
      <div
        class="product-card__halo absolute inset-x-10 bottom-5 top-8 z-[1] rounded-full opacity-[0.58] blur-[16px]"
      />
      <img
        v-if="exampleImageUrl"
        :src="exampleImageUrl"
        :alt="article.name"
        class="absolute inset-0 z-[2] size-full object-contain p-6 drop-shadow-[0_0.95rem_1.15rem_oklch(0_0_0_/_0.16)] transition-transform duration-300 group-hover:scale-105 motion-reduce:transition-none"
      />
      <div v-else class="absolute inset-0 z-[2] flex size-full items-center justify-center">
        <div
          class="size-[5.4rem] rounded-full shadow-inner transition-transform duration-300 group-hover:scale-110 motion-reduce:transition-none"
          :style="{
            backgroundColor: outsideColor,
            boxShadow: `inset 0 -20px 30px -10px ${insideColor}`,
          }"
        />
      </div>

      <div
        class="absolute left-3 top-3 z-10 rounded-xl bg-background/85 px-3 py-1.5 shadow-sm backdrop-blur-md"
      >
        <ProductPrice
          :cents="priceCents"
          :regular-cents="regularPriceCents"
          size="sm"
          :show-saving="false"
        />
      </div>
    </div>

    <div class="product-card__content relative z-[2] flex min-w-0 flex-1 flex-col gap-5 p-5">
      <div class="grid gap-1.5">
        <h3 class="text-lg font-bold leading-tight text-foreground">{{ article.name }}</h3>
        <p class="line-clamp-2 text-sm leading-relaxed text-muted-foreground">
          {{ article.descriptionShort }}
        </p>
      </div>

      <div v-if="colorSwatches.length > 1" class="grid gap-2.5">
        <p class="text-sm text-muted-foreground">
          {{ t('productCard.color') }}:
          <span class="font-semibold text-foreground">{{ activeColorName }}</span>
        </p>
        <div class="flex flex-wrap items-center gap-1.5">
          <SwatchButton
            v-for="swatch in visibleSwatches"
            :key="swatch.variantId"
            class="size-8 p-0.5"
            :color="swatch.color"
            :title="swatch.label"
            :label="swatch.label"
            :selected="swatch.selected"
            @click.stop="emit('select-variant', swatch.variantId)"
          />
          <Button
            v-if="hiddenSwatchCount > 0"
            variant="pill"
            size="xs"
            class="h-8 rounded-full px-3"
            :aria-expanded="swatchesExpanded"
            :aria-label="
              swatchesExpanded
                ? t('productCard.showFewerColors')
                : t('productCard.showAllColors', { count: colorSwatches.length })
            "
            data-testid="product-card-color-toggle"
            @click.stop="swatchesExpanded = !swatchesExpanded"
          >
            {{ swatchesExpanded ? t('productCard.fewerColors') : `+${hiddenSwatchCount}` }}
          </Button>
        </div>
      </div>

      <p v-if="sizeHint" class="text-sm text-muted-foreground" data-testid="product-card-sizes">
        {{ t('productCard.sizes') }}
        <span class="font-semibold text-foreground">{{ sizeHint }}</span>
      </p>

      <slot name="action" />
    </div>
  </component>
</template>

<style scoped>
/* CSS exceptions: texture data URL, color-mixed media effects, pseudo shadow, and slot alignment. */
.product-card-noise {
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='0.04'/%3E%3C/svg%3E");
}

.product-card-image-bg {
  background:
    radial-gradient(
      circle at 50% 78%,
      color-mix(in oklch, var(--product-outside-color) 24%, transparent) 0%,
      transparent 48%
    ),
    linear-gradient(180deg, oklch(1 0 0 / 0.08), transparent 35%), var(--surface-image-bg);
}

.product-card__media::after {
  content: '';
  position: absolute;
  left: 14%;
  right: 14%;
  bottom: 0.9rem;
  z-index: 1;
  height: 1.25rem;
  border-radius: 999px;
  background: oklch(0 0 0 / 0.14);
  filter: blur(12px);
  opacity: 0.62;
}

.product-card__halo {
  background:
    radial-gradient(
      circle at 44% 42%,
      color-mix(in oklch, var(--product-inside-color) 32%, white 20%) 0%,
      transparent 56%
    ),
    radial-gradient(
      circle at 58% 62%,
      color-mix(in oklch, var(--product-outside-color) 42%, transparent) 0%,
      transparent 64%
    );
}

.product-card__content :slotted(*) {
  margin-top: auto;
}
</style>
