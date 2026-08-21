<script setup lang="ts">
import { shallowRef, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowLeftRight, RefreshCw, Ruler, Shirt } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import { SegmentedControl, SegmentedControlItem } from '@/components/ui/segmented-control'
import { SwatchButton } from '@/components/ui/swatch-button'
import ProductCard from '@/components/shop/ProductCard.vue'
import { useWizardStore } from '@/stores/shop/wizard'
import {
  isMug,
  isTshirt,
  useCatalogStore,
  type MugDto,
  type MugVariantDto,
  type ShopArticle,
  type ShopArticleVariant,
  type TshirtDto,
  type TshirtVariantDto,
} from '@/stores/shop/catalog'
import { useArticleCategoriesStore } from '@/stores/shop/articleCategories'
import { resolveDisplayVariant } from '@/lib/changeArticleSelection'
import { sizeChartImageUrl } from '@/lib/sizeChartImage'
import { variantExampleImageUrl } from '@/lib/variantExampleImage'

const { t } = useI18n()
const wizard = useWizardStore()
const catalogStore = useCatalogStore()
const categoriesStore = useArticleCategoriesStore()

const activeCategoryId = shallowRef<number | null>(null)
/**
 * The step shows either the grid or the compact panel of the selected article. The panel is what
 * carries the colour swatches, the size buttons, and the size chart, so it opens as soon as an
 * article is selected - whether the wizard arrived with a selection or the customer just picked a
 * card - and closes again only when the customer asks to change the article.
 */
const showFullGrid = shallowRef(true)

const allCategoriesValue = 'all'

/** The step offers every article type; what a variant means is decided per card, not per grid. */
const filteredArticles = computed(() =>
  catalogStore.getDisplayArticles(activeCategoryId.value, null, null),
)

const activeCategoryValue = computed({
  get: () =>
    activeCategoryId.value === null ? allCategoriesValue : String(activeCategoryId.value),
  set: (value: string | undefined) => {
    activeCategoryId.value =
      value === undefined || value === allCategoriesValue ? null : Number(value)
  },
})

const selectedArticle = computed<ShopArticle | null>(() => {
  if (showFullGrid.value || wizard.selectedArticleId === null) return null
  return catalogStore.getArticleById(wizard.selectedArticleId) ?? null
})

const selectedVariant = computed<ShopArticleVariant | null>(() => {
  const article = selectedArticle.value
  if (!article) return null
  return getDisplayVariant(article)
})

const selectedMug = computed<MugDto | null>(() => {
  const article = selectedArticle.value
  return article !== null && isMug(article) ? article : null
})

const selectedTshirt = computed<TshirtDto | null>(() => {
  const article = selectedArticle.value
  return article !== null && isTshirt(article) ? article : null
})

const selectedMugVariant = computed<MugVariantDto | null>(() => {
  const variant = selectedVariant.value
  return selectedMug.value !== null && variant !== null && 'outsideColorCode' in variant
    ? variant
    : null
})

const selectedTshirtVariant = computed<TshirtVariantDto | null>(() => {
  const variant = selectedVariant.value
  return selectedTshirt.value !== null && variant !== null && 'colorHex' in variant ? variant : null
})

/** One swatch per shirt *colour*: a shirt offers the same colour once per size. */
const tshirtColors = computed(() => {
  const article = selectedTshirt.value
  if (!article) return []

  const colors = new Map<string, { colorName: string; colorHex: string }>()
  for (const variant of article.variants) {
    if (!colors.has(variant.colorName)) {
      colors.set(variant.colorName, { colorName: variant.colorName, colorHex: variant.colorHex })
    }
  }

  return [...colors.values()]
})

/** The sizes the selected colour is offered in, in the order the catalog lists them. */
const tshirtSizes = computed<TshirtVariantDto[]>(() => {
  const article = selectedTshirt.value
  const selectedColorName = selectedTshirtVariant.value?.colorName
  if (!article || selectedColorName === undefined) return []

  return article.variants.filter((variant) => variant.colorName === selectedColorName)
})

const sizeChartUrl = computed(() => {
  const filename = selectedTshirt.value?.sizeChartImageFilename
  return filename ? sizeChartImageUrl(filename, 1000) : null
})

const selectedImageUrl = computed(() => {
  const article = selectedArticle.value
  const filename = selectedVariant.value?.exampleImageFilename
  if (!article || !filename) return null

  return variantExampleImageUrl(article.articleType, filename, 400)
})

function getDisplayVariant(article: ShopArticle): ShopArticleVariant | null {
  return resolveDisplayVariant(article, wizard.selectedArticleId, wizard.selectedVariantId)
}

function onSelectArticle(article: ShopArticle) {
  const variant = getDisplayVariant(article)
  if (!variant) return

  wizard.selectArticle(article.articleType, article.id, variant.id)
  showFullGrid.value = false
}

function onSelectVariant(article: ShopArticle, variantId: number) {
  if (article.id === wizard.selectedArticleId) {
    wizard.selectVariant(variantId)
  } else {
    wizard.selectArticle(article.articleType, article.id, variantId)
  }

  showFullGrid.value = false
}

/** A colour switch keeps the selected size whenever that colour is offered in it. */
function onSelectTshirtColor(colorName: string) {
  const article = selectedTshirt.value
  if (!article) return

  const selectedSize = selectedTshirtVariant.value?.size
  const variantsOfColor = article.variants.filter((variant) => variant.colorName === colorName)
  const variant =
    variantsOfColor.find((item) => item.size === selectedSize) ?? variantsOfColor[0] ?? null
  if (!variant) return

  onSelectVariant(article, variant.id)
}

function mugSwatchColor(variant: MugVariantDto) {
  return `linear-gradient(135deg, ${variant.outsideColorCode} 0%, ${variant.outsideColorCode} 50%, ${variant.insideColorCode} 50%, ${variant.insideColorCode} 100%)`
}

function onChangeArticle() {
  showFullGrid.value = true
}

onMounted(async () => {
  await Promise.all([catalogStore.fetchArticles(), categoriesStore.fetchCategories()])

  if (wizard.hasSelectedArticle && wizard.selectedArticleId !== null) {
    const article = catalogStore.getArticleById(wizard.selectedArticleId)
    if (!article) return

    activeCategoryId.value = article.categoryId
    showFullGrid.value = false
  }
})
</script>

<template>
  <div class="wizard-step-enter pb-2">
    <h2 class="sr-only">
      {{
        !showFullGrid
          ? t('configurator.steps.selectArticle.preSelectedTitle')
          : t('configurator.steps.selectArticle.title')
      }}
    </h2>

    <!-- Selected article compact view -->
    <div
      v-if="!showFullGrid && selectedArticle && selectedVariant && !catalogStore.isLoading"
      data-testid="wizard-selected-article"
      class="mt-6 flex flex-col overflow-hidden rounded-xl border-[1.5px] border-[oklch(0.61_0.19_35_/_0.7)] bg-[linear-gradient(175deg,oklch(0.99_0.008_50_/_0.8)_0%,oklch(0.98_0.005_40_/_0.5)_100%)] shadow-[0_0_0_1px_oklch(0.61_0.19_35_/_0.15),0_4px_12px_oklch(0.61_0.19_35_/_0.1),0_8px_24px_oklch(0.61_0.19_35_/_0.06)] motion-safe:animate-enter-lift motion-reduce:animate-none sm:mt-8 sm:flex-row"
    >
      <!-- Image -->
      <div class="relative aspect-[4/3] bg-muted/30 sm:aspect-auto sm:w-2/5">
        <div class="absolute inset-0 bg-surface-image" />
        <img
          v-if="selectedImageUrl"
          :src="selectedImageUrl"
          :alt="selectedArticle.name"
          class="relative z-[2] size-full object-contain p-6"
        />
        <div
          v-else-if="selectedMugVariant"
          class="relative z-[2] flex size-full items-center justify-center"
        >
          <div
            class="size-28 rounded-full shadow-inner sm:size-32"
            :style="{
              backgroundColor: selectedMugVariant.outsideColorCode,
              boxShadow: `inset 0 -20px 30px -10px ${selectedMugVariant.insideColorCode}`,
            }"
          />
        </div>
        <div v-else class="relative z-[2] flex size-full items-center justify-center">
          <Shirt
            class="size-28 sm:size-32"
            :style="{ color: selectedTshirtVariant?.colorHex ?? undefined }"
            aria-hidden="true"
          />
        </div>
      </div>

      <!-- Details -->
      <div class="flex flex-1 flex-col justify-center p-5 sm:p-6">
        <p class="text-xl font-bold text-[var(--price-accent)]">
          {{ catalogStore.formatPrice(selectedArticle.price) }}
        </p>
        <h3 class="mt-1 text-lg font-semibold tracking-tight">{{ selectedArticle.name }}</h3>
        <p class="mt-2 text-sm leading-relaxed text-muted-foreground">
          {{ selectedArticle.descriptionLong || selectedArticle.descriptionShort }}
        </p>

        <!-- Mug color variants -->
        <div v-if="selectedMug && selectedMug.variants.length > 1" class="mt-4">
          <p class="mb-1.5 text-xs text-muted-foreground">
            {{ t('configurator.steps.selectArticle.colors') }}
          </p>
          <div class="flex flex-wrap gap-2">
            <SwatchButton
              v-for="variant in selectedMug.variants"
              :key="variant.id"
              class="size-6 p-0 transition-transform data-[state=selected]:scale-110 data-[state=unselected]:hover:scale-105"
              :color="mugSwatchColor(variant)"
              :label="variant.name"
              :selected="selectedVariant.id === variant.id"
              :title="variant.name"
              @click="onSelectVariant(selectedMug, variant.id)"
            />
          </div>
        </div>

        <!-- Shirt color variants -->
        <div v-if="selectedTshirt && tshirtColors.length > 1" class="mt-4">
          <p class="mb-1.5 text-xs text-muted-foreground">
            {{ t('configurator.steps.selectArticle.colors') }}
          </p>
          <div class="flex flex-wrap gap-2" data-testid="wizard-tshirt-colors">
            <SwatchButton
              v-for="color in tshirtColors"
              :key="color.colorName"
              class="size-6 p-0 transition-transform data-[state=selected]:scale-110 data-[state=unselected]:hover:scale-105"
              :color="color.colorHex"
              :label="color.colorName"
              :selected="selectedTshirtVariant?.colorName === color.colorName"
              :title="color.colorName"
              @click="onSelectTshirtColor(color.colorName)"
            />
          </div>
        </div>

        <!-- Shirt sizes -->
        <div v-if="selectedTshirt && tshirtSizes.length > 0" class="mt-4">
          <p class="mb-1.5 text-xs text-muted-foreground">
            {{ t('configurator.steps.selectArticle.sizes') }}
          </p>
          <div class="flex flex-wrap gap-2" data-testid="wizard-tshirt-sizes">
            <Button
              v-for="variant in tshirtSizes"
              :key="variant.id"
              type="button"
              variant="outline"
              size="sm"
              class="min-w-11"
              :class="{ 'border-primary text-primary': selectedVariant.id === variant.id }"
              :aria-pressed="selectedVariant.id === variant.id"
              @click="onSelectVariant(selectedTshirt, variant.id)"
            >
              {{ variant.size }}
            </Button>
          </div>

          <Dialog v-if="sizeChartUrl">
            <DialogTrigger as-child>
              <Button
                type="button"
                variant="link"
                size="sm"
                class="mt-2 h-auto w-fit p-0"
                data-testid="wizard-size-chart-trigger"
              >
                <Ruler class="h-3.5 w-3.5" />
                {{ t('configurator.steps.selectArticle.sizeChart') }}
              </Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader>
                <DialogTitle>
                  {{ t('configurator.steps.selectArticle.sizeChart') }}
                </DialogTitle>
                <DialogDescription>
                  {{ t('configurator.steps.selectArticle.sizeChartHint') }}
                </DialogDescription>
              </DialogHeader>
              <img
                :src="sizeChartUrl"
                :alt="t('configurator.steps.selectArticle.sizeChart')"
                class="w-full object-contain"
                data-testid="wizard-size-chart-image"
              />
            </DialogContent>
          </Dialog>
        </div>

        <!-- Change article button -->
        <Button variant="outline" size="sm" class="mt-5 w-fit" @click="onChangeArticle">
          <ArrowLeftRight class="h-3.5 w-3.5" />
          {{ t('configurator.steps.selectArticle.changeArticle') }}
        </Button>
      </div>
    </div>

    <!-- Category filter pills -->
    <div
      v-if="
        showFullGrid &&
        !catalogStore.isLoading &&
        !catalogStore.error &&
        categoriesStore.categories.length > 0
      "
      class="mt-6 sm:mt-8"
    >
      <SegmentedControl
        v-model="activeCategoryValue"
        type="single"
        variant="editor"
        class="scrollbar-hide max-w-full overflow-x-auto"
      >
        <SegmentedControlItem
          :value="allCategoriesValue"
          variant="editor"
          class="mug-pill shrink-0"
        >
          {{ t('configurator.steps.selectArticle.allCategories') }}
        </SegmentedControlItem>
        <SegmentedControlItem
          v-for="category in categoriesStore.categories"
          :key="category.id"
          :value="String(category.id)"
          variant="editor"
          class="mug-pill shrink-0"
        >
          {{ category.name }}
        </SegmentedControlItem>
      </SegmentedControl>
    </div>

    <!-- Loading skeleton -->
    <div
      v-if="catalogStore.isLoading"
      class="mt-6 grid grid-cols-1 gap-3 sm:mt-8 sm:gap-5 sm:grid-cols-2 md:grid-cols-3"
    >
      <div
        v-for="n in 6"
        :key="n"
        class="flex flex-row overflow-hidden rounded-xl border-[1.5px] border-border bg-surface-skeleton motion-safe:animate-enter-lift motion-reduce:animate-none sm:flex-col"
      >
        <div
          class="w-[120px] shrink-0 bg-[oklch(0.93_0.01_50_/_0.6)] motion-safe:animate-skeleton-pulse motion-reduce:animate-none dark:bg-[oklch(0.3_0.01_50_/_0.6)] sm:aspect-[4/3] sm:w-auto"
        />
        <div
          class="space-y-2 bg-[oklch(0.93_0.01_50_/_0.6)] p-3 motion-safe:animate-skeleton-pulse motion-reduce:animate-none dark:bg-[oklch(0.3_0.01_50_/_0.6)] sm:space-y-3 sm:p-4"
        >
          <div
            class="h-5 w-16 rounded bg-[oklch(0.91_0.01_50_/_0.5)] motion-safe:animate-skeleton-pulse motion-reduce:animate-none dark:bg-[oklch(0.28_0.01_50_/_0.5)]"
          />
          <div
            class="h-4 w-3/4 rounded bg-[oklch(0.91_0.01_50_/_0.5)] motion-safe:animate-skeleton-pulse motion-reduce:animate-none dark:bg-[oklch(0.28_0.01_50_/_0.5)]"
          />
          <div
            class="h-3 w-full rounded bg-[oklch(0.91_0.01_50_/_0.5)] motion-safe:animate-skeleton-pulse motion-reduce:animate-none dark:bg-[oklch(0.28_0.01_50_/_0.5)]"
          />
          <div class="flex gap-2">
            <div
              v-for="d in 4"
              :key="d"
              class="size-4 rounded-full bg-[oklch(0.89_0.015_50_/_0.5)] motion-safe:animate-skeleton-pulse motion-reduce:animate-none dark:bg-[oklch(0.32_0.015_50_/_0.5)] sm:size-5"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- Error state -->
    <div
      v-else-if="catalogStore.error"
      class="mt-6 flex flex-col items-center gap-4 rounded-xl border-[1.5px] border-dashed border-border bg-surface-empty p-10 text-center sm:mt-8"
    >
      <p class="text-sm font-medium text-destructive">
        {{ t('configurator.steps.selectArticle.error') }}
      </p>
      <Button variant="outline" size="sm" @click="catalogStore.fetchArticles()">
        <RefreshCw class="h-3.5 w-3.5" />
        {{ t('configurator.steps.selectArticle.retry') }}
      </Button>
    </div>

    <!-- Empty state -->
    <div
      v-else-if="showFullGrid && filteredArticles.length === 0"
      class="mt-6 flex flex-col items-center justify-center rounded-xl border-[1.5px] border-dashed border-border bg-surface-empty p-14 text-center sm:mt-8"
    >
      <p class="text-sm text-muted-foreground">
        {{ t('configurator.steps.selectArticle.empty') }}
      </p>
    </div>

    <!-- Article cards grid -->
    <div
      v-else-if="showFullGrid"
      class="mt-6 grid grid-cols-1 gap-3 sm:mt-8 sm:gap-5 sm:grid-cols-2 md:grid-cols-3"
    >
      <ProductCard
        v-for="(article, index) in filteredArticles"
        :key="article.id"
        :article="article"
        :active-variant="getDisplayVariant(article)"
        :formatted-price="catalogStore.formatPrice(article.price)"
        :card-index="index"
        :selected="wizard.selectedArticleId === article.id"
        as="button"
        @click="onSelectArticle(article)"
        @select-variant="onSelectVariant(article, $event)"
      />
    </div>
  </div>
</template>
