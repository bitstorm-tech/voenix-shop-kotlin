<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Ruler } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import SpodSizeChart from '@/components/shared/SpodSizeChart.vue'
import { SwatchButton } from '@/components/ui/swatch-button'
import { sortSizes } from '@/lib/tshirtSizes'
import { sizeChartImageUrl } from '@/lib/variantExampleImage'
import type { EditorArticle, EditorArticleVariant, EditorMugVariant } from './types'

const props = defineProps<{
  article: EditorArticle
  variant: EditorArticleVariant
}>()

const emit = defineEmits<{
  select: [variantId: number]
}>()

const { t } = useI18n()

interface ColorSwatch {
  key: number
  variantId: number
  label: string
  color: string
  selected: boolean
}

interface SizeOption {
  variantId: number
  size: string
  selected: boolean
}

/**
 * A mug swatch shows the mug from above: the outside colour as the disc, the inside colour as the
 * opening in its middle - the same picture the product card shows.
 */
function mugSwatchColor(variant: EditorMugVariant): string {
  const { outsideColorCode: outside, insideColorCode: inside } = variant
  if (outside.toLowerCase() === inside.toLowerCase()) return outside

  return `radial-gradient(circle, ${inside} 0 40%, ${outside} calc(40% + 0.5px))`
}

/**
 * A mug offers a swatch per variant; a SPOD article a swatch per colour, and clicking a colour keeps
 * the selected size whenever that colour is offered in it.
 */
const colorSwatches = computed<ColorSwatch[]>(() => {
  const article = props.article
  const current = props.variant

  if (article.type === 'MUG') {
    return article.variants.map((variant) => ({
      key: variant.id,
      variantId: variant.id,
      label: variant.name,
      color: mugSwatchColor(variant),
      selected: variant.id === current.id,
    }))
  }

  const selectedSize = current.type === 'SPOD' ? current.size : null
  const appearanceIds = [...new Set(article.variants.map((variant) => variant.appearanceId))]

  return appearanceIds.map((appearanceId) => {
    const variants = article.variants.filter((item) => item.appearanceId === appearanceId)
    const variant = variants.find((item) => item.size === selectedSize) ?? variants[0]!

    return {
      key: appearanceId,
      variantId: variant.id,
      label: variant.colorName,
      color: variant.colorHex,
      selected: current.type === 'SPOD' && current.appearanceId === appearanceId,
    }
  })
})

const activeColorName = computed(() =>
  props.variant.type === 'SPOD' ? props.variant.colorName : props.variant.name,
)

/** The sizes the selected colour is offered in, smallest first. */
const sizeOptions = computed<SizeOption[]>(() => {
  const article = props.article
  const current = props.variant
  if (article.type !== 'SPOD' || current.type !== 'SPOD') return []

  const variants = article.variants.filter((item) => item.appearanceId === current.appearanceId)
  return sortSizes(variants.map((variant) => variant.size)).map((size) => {
    const variant = variants.find((item) => item.size === size)!
    return { variantId: variant.id, size, selected: variant.id === current.id }
  })
})

const sizeChartUrl = computed(() =>
  props.article.type === 'SPOD' && props.article.sizeChartImageFilename
    ? sizeChartImageUrl(props.article.sizeChartImageFilename, 1000)
    : null,
)

const sizeMeasurements = computed(() =>
  props.article.type === 'SPOD' ? props.article.sizeMeasurements : [],
)

function select(variantId: number) {
  if (variantId !== props.variant.id) emit('select', variantId)
}
</script>

<template>
  <div class="product-variant-panel grid gap-4" data-testid="editor-product-variant-panel">
    <p class="m-0 text-sm text-muted-foreground">{{ t('editor.productVariant.hint') }}</p>

    <div v-if="colorSwatches.length > 1" class="grid gap-2">
      <p class="m-0 text-sm text-muted-foreground">
        {{ t('editor.productVariant.color') }}:
        <span class="font-semibold text-foreground">{{ activeColorName }}</span>
      </p>
      <div class="flex flex-wrap gap-1.5" data-testid="editor-variant-colors">
        <SwatchButton
          v-for="swatch in colorSwatches"
          :key="swatch.key"
          class="size-8 p-0.5"
          :color="swatch.color"
          :title="swatch.label"
          :label="swatch.label"
          :selected="swatch.selected"
          @click="select(swatch.variantId)"
        />
      </div>
    </div>

    <!-- One size is no choice, so it gets neither buttons nor a size chart. -->
    <div v-if="sizeOptions.length > 1" class="grid gap-2">
      <p class="m-0 text-sm text-muted-foreground">{{ t('editor.productVariant.size') }}</p>
      <div class="flex flex-wrap gap-2" data-testid="editor-variant-sizes">
        <Button
          v-for="option in sizeOptions"
          :key="option.variantId"
          type="button"
          variant="outline"
          size="sm"
          class="min-w-11"
          :class="{ 'border-primary text-primary': option.selected }"
          :aria-pressed="option.selected"
          @click="select(option.variantId)"
        >
          {{ option.size }}
        </Button>
      </div>

      <Dialog v-if="sizeChartUrl || sizeMeasurements.length > 0">
        <DialogTrigger as-child>
          <Button
            type="button"
            variant="link"
            size="sm"
            class="h-auto w-fit p-0"
            data-testid="editor-size-chart-trigger"
          >
            <Ruler class="h-3.5 w-3.5" />
            {{ t('editor.productVariant.sizeChart') }}
          </Button>
        </DialogTrigger>
        <DialogContent class="max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{{ t('editor.productVariant.sizeChart') }}</DialogTitle>
            <DialogDescription>
              {{
                sizeMeasurements.length > 0
                  ? t('editor.productVariant.sizeChartHint')
                  : t('editor.productVariant.sizeChartNoMeasurementsHint')
              }}
            </DialogDescription>
          </DialogHeader>
          <SpodSizeChart
            :image-url="sizeChartUrl"
            :measurements="sizeMeasurements"
            :selected-size="variant.type === 'SPOD' ? variant.size : null"
          />
        </DialogContent>
      </Dialog>
    </div>
  </div>
</template>
