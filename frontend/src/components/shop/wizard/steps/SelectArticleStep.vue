<script setup lang="ts">
import { shallowRef, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowLeftRight, Package, RefreshCw, Ruler } from 'lucide-vue-next'
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
import ProductImageGallery from '@/components/shop/ProductImageGallery.vue'
import ProductPrice from '@/components/shop/ProductPrice.vue'
import { useWizardStore } from '@/stores/shop/wizard'
import {
  appearanceOf,
  isMug,
  isSpodArticle,
  useCatalogStore,
  variantPhotoFilenames,
  type MugDto,
  type MugVariantDto,
  type ShopArticle,
  type ShopArticleVariant,
  type SpodArticleDto,
  type SpodVariantDto,
} from '@/stores/shop/catalog'
import { useArticleCategoriesStore } from '@/stores/shop/articleCategories'
import { sanitizeDescriptionHtml } from '@/lib/articleDescription'
import { resolveDisplayVariant } from '@/lib/changeArticleSelection'
import { sizeChartImageUrl, variantExampleImageUrl } from '@/lib/variantExampleImage'

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

const selectedSpodArticle = computed<SpodArticleDto | null>(() => {
  const article = selectedArticle.value
  return article !== null && isSpodArticle(article) ? article : null
})

// The selected variant always comes from the selected article's own variant list, a correlation
// between two pieces of wizard state that TypeScript cannot see - so the article's type decides
// which variant it is.
const selectedMugVariant = computed<MugVariantDto | null>(() =>
  selectedMug.value !== null ? (selectedVariant.value as MugVariantDto | null) : null,
)

const selectedSpodVariant = computed<SpodVariantDto | null>(() =>
  selectedSpodArticle.value !== null ? (selectedVariant.value as SpodVariantDto | null) : null,
)

const selectedAppearance = computed(() => {
  const article = selectedSpodArticle.value
  const variant = selectedSpodVariant.value
  return article && variant ? appearanceOf(article, variant) : null
})

/** The sizes the selected colour is offered in, in the order the catalog lists them. */
const spodSizes = computed<SpodVariantDto[]>(() => {
  const article = selectedSpodArticle.value
  const appearanceId = selectedSpodVariant.value?.appearanceId
  if (!article || appearanceId === undefined) return []

  return article.variants.filter((variant) => variant.appearanceId === appearanceId)
})

/** A SPOD description is partner HTML; a mug's is the plain text the backoffice typed. */
const selectedSpodDescriptionHtml = computed(() => {
  const article = selectedSpodArticle.value
  if (!article) return null

  return sanitizeDescriptionHtml(article.descriptionLong || article.descriptionShort) || null
})

const sizeChartUrl = computed(() => {
  const filename = selectedSpodArticle.value?.sizeChartImageFilename
  return filename ? sizeChartImageUrl(filename, 1000) : null
})

/**
 * The pictures the compact view leafs through, like the card it enlarges: a mug variant's own photo,
 * or every listing image of the selected colour.
 */
const selectedImageUrls = computed(() => {
  const article = selectedArticle.value
  const variant = selectedVariant.value
  if (!article || !variant) return []

  return variantPhotoFilenames(article, variant).map((filename) =>
    variantExampleImageUrl(article.articleType, filename, 400),
  )
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
function onSelectSpodAppearance(appearanceId: number) {
  const article = selectedSpodArticle.value
  if (!article) return

  const selectedSize = selectedSpodVariant.value?.size
  const variantsOfColor = article.variants.filter(
    (variant) => variant.appearanceId === appearanceId,
  )
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
      class="mt-6 flex flex-col overflow-hidden rounded-2xl border-[1.5px] border-[oklch(0.61_0.19_35_/_0.7)] bg-surface-card shadow-[0_0_0_1px_oklch(0.61_0.19_35_/_0.15),0_4px_12px_oklch(0.61_0.19_35_/_0.1),0_8px_24px_oklch(0.61_0.19_35_/_0.06)] motion-safe:animate-enter-lift motion-reduce:animate-none sm:mt-8 sm:flex-row"
    >
      <!-- Image -->
      <div class="relative aspect-[4/3] bg-muted/30 sm:aspect-auto sm:w-2/5">
        <div class="absolute inset-0 bg-surface-image" />
        <ProductImageGallery
          v-if="selectedImageUrls.length > 0"
          :images="selectedImageUrls"
          :alt="selectedArticle.name"
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
          <Package
            class="size-28 sm:size-32"
            :style="{ color: selectedAppearance?.colorHex ?? undefined }"
            aria-hidden="true"
          />
        </div>
      </div>

      <!-- Details -->
      <div class="flex flex-1 flex-col justify-center p-5 sm:p-6">
        <ProductPrice
          :cents="selectedArticle.price"
          :regular-cents="selectedArticle.regularPrice"
          size="lg"
        />
        <h3 class="mt-1 text-lg font-bold leading-tight text-foreground">
          {{ selectedArticle.name }}
        </h3>
        <!-- eslint-disable-next-line vue/no-v-html -- sanitized to a few text tags -->
        <div
          v-if="selectedSpodDescriptionHtml"
          class="mt-2 text-sm leading-relaxed text-muted-foreground [&_li]:mt-0.5 [&_ol]:mt-2 [&_ol]:list-decimal [&_ol]:pl-5 [&_p+p]:mt-2 [&_strong]:font-semibold [&_ul]:mt-2 [&_ul]:list-disc [&_ul]:pl-5"
          data-testid="wizard-article-description"
          v-html="selectedSpodDescriptionHtml"
        />
        <p v-else class="mt-2 text-sm leading-relaxed text-muted-foreground">
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

        <!-- SPOD colours -->
        <div v-if="selectedSpodArticle && selectedSpodArticle.appearances.length > 1" class="mt-4">
          <p class="mb-1.5 text-xs text-muted-foreground">
            {{ t('configurator.steps.selectArticle.colors') }}
          </p>
          <div class="flex flex-wrap gap-2" data-testid="wizard-spod-colors">
            <SwatchButton
              v-for="appearance in selectedSpodArticle.appearances"
              :key="appearance.id"
              class="size-6 p-0 transition-transform data-[state=selected]:scale-110 data-[state=unselected]:hover:scale-105"
              :color="appearance.colorHex"
              :label="appearance.colorName"
              :selected="selectedSpodVariant?.appearanceId === appearance.id"
              :title="appearance.colorName"
              @click="onSelectSpodAppearance(appearance.id)"
            />
          </div>
        </div>

        <!-- SPOD sizes: one size is no choice, so it gets neither buttons nor a size chart -->
        <div v-if="selectedSpodArticle && spodSizes.length > 1" class="mt-4">
          <p class="mb-1.5 text-xs text-muted-foreground">
            {{ t('configurator.steps.selectArticle.sizes') }}
          </p>
          <div class="flex flex-wrap gap-2" data-testid="wizard-spod-sizes">
            <Button
              v-for="variant in spodSizes"
              :key="variant.id"
              type="button"
              variant="outline"
              size="sm"
              class="min-w-11"
              :class="{ 'border-primary text-primary': selectedVariant.id === variant.id }"
              :aria-pressed="selectedVariant.id === variant.id"
              @click="onSelectVariant(selectedSpodArticle, variant.id)"
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
        :price-cents="article.price"
        :regular-price-cents="article.regularPrice"
        :card-index="index"
        :selected="wizard.selectedArticleId === article.id"
        as="button"
        @click="onSelectArticle(article)"
        @select-variant="onSelectVariant(article, $event)"
      />
    </div>
  </div>
</template>
