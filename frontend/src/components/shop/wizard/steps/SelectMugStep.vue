<script setup lang="ts">
import { shallowRef, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowLeftRight, RefreshCw } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { SegmentedControl, SegmentedControlItem } from '@/components/ui/segmented-control'
import { SwatchButton } from '@/components/ui/swatch-button'
import ProductCard from '@/components/shop/ProductCard.vue'
import { useWizardStore } from '@/stores/shop/wizard'
import { isMug, useCatalogStore, type MugDto, type MugVariantDto } from '@/stores/shop/catalog'
import { useArticleCategoriesStore } from '@/stores/shop/articleCategories'
import { resolveDisplayVariant } from '@/lib/changeMugSelection'
import { variantExampleImageUrl } from '@/lib/variantExampleImage'

const { t } = useI18n()
const wizard = useWizardStore()
const catalogStore = useCatalogStore()
const categoriesStore = useArticleCategoriesStore()

const activeCategoryId = shallowRef<number | null>(null)
const wasPreSelected = shallowRef(false)
const showFullGrid = shallowRef(true)

const allCategoriesValue = 'all'

/** The wizard still configures mugs only; the shirt step is its own picker. */
const filteredMugs = computed(() =>
  catalogStore.getDisplayArticles(activeCategoryId.value, null, 'MUG').filter(isMug),
)

const activeCategoryValue = computed({
  get: () =>
    activeCategoryId.value === null ? allCategoriesValue : String(activeCategoryId.value),
  set: (value: string | undefined) => {
    activeCategoryId.value =
      value === undefined || value === allCategoriesValue ? null : Number(value)
  },
})

const preSelectedMug = computed(() => {
  if (!wasPreSelected.value || !wizard.selectedMugId) return null
  return catalogStore.getMugById(wizard.selectedMugId) ?? null
})

const preSelectedVariant = computed(() => {
  if (!preSelectedMug.value) return null
  return getDisplayVariant(preSelectedMug.value)
})

function getDisplayVariant(mug: MugDto): MugVariantDto | null {
  return resolveDisplayVariant(mug, wizard.selectedMugId, wizard.selectedVariantId)
}

function onSelectMug(mug: MugDto) {
  const variant = getDisplayVariant(mug)
  if (!variant) return

  wizard.selectMug(mug.id, variant.id)
}

function onSelectVariant(mugId: number, variantId: number) {
  if (mugId === wizard.selectedMugId) {
    wizard.selectVariant(variantId)
  } else {
    wizard.selectMug(mugId, variantId)
  }
}

function variantSwatchColor(variant: MugVariantDto) {
  return `linear-gradient(135deg, ${variant.outsideColorCode} 0%, ${variant.outsideColorCode} 50%, ${variant.insideColorCode} 50%, ${variant.insideColorCode} 100%)`
}

function onChangeMug() {
  showFullGrid.value = true
}

onMounted(async () => {
  await Promise.all([catalogStore.fetchArticles(), categoriesStore.fetchCategories()])

  if (wizard.hasSelectedMug && wizard.selectedMugId !== null) {
    const mug = catalogStore.getMugById(wizard.selectedMugId)
    if (!mug) return

    activeCategoryId.value = mug.categoryId
    wasPreSelected.value = true
    showFullGrid.value = false
  }
})
</script>

<template>
  <div class="wizard-step-enter pb-2">
    <h2 class="sr-only">
      {{
        wasPreSelected && !showFullGrid
          ? t('mugConfigurator.steps.selectMug.preSelectedTitle')
          : t('mugConfigurator.steps.selectMug.title')
      }}
    </h2>

    <!-- Pre-selected mug compact view -->
    <div
      v-if="
        wasPreSelected &&
        !showFullGrid &&
        preSelectedMug &&
        preSelectedVariant &&
        !catalogStore.isLoading
      "
      class="mt-6 flex flex-col overflow-hidden rounded-xl border-[1.5px] border-[oklch(0.61_0.19_35_/_0.7)] bg-[linear-gradient(175deg,oklch(0.99_0.008_50_/_0.8)_0%,oklch(0.98_0.005_40_/_0.5)_100%)] shadow-[0_0_0_1px_oklch(0.61_0.19_35_/_0.15),0_4px_12px_oklch(0.61_0.19_35_/_0.1),0_8px_24px_oklch(0.61_0.19_35_/_0.06)] motion-safe:animate-enter-lift motion-reduce:animate-none sm:mt-8 sm:flex-row"
    >
      <!-- Image -->
      <div class="relative aspect-[4/3] bg-muted/30 sm:aspect-auto sm:w-2/5">
        <div class="absolute inset-0 bg-surface-image" />
        <img
          v-if="preSelectedVariant.exampleImageFilename"
          :src="variantExampleImageUrl('MUG', preSelectedVariant.exampleImageFilename, 400)"
          :alt="preSelectedMug.name"
          class="relative z-[2] size-full object-contain p-6"
        />
        <div v-else class="relative z-[2] flex size-full items-center justify-center">
          <div
            class="size-28 rounded-full shadow-inner sm:size-32"
            :style="{
              backgroundColor: preSelectedVariant.outsideColorCode,
              boxShadow: `inset 0 -20px 30px -10px ${preSelectedVariant.insideColorCode}`,
            }"
          />
        </div>
      </div>

      <!-- Details -->
      <div class="flex flex-1 flex-col justify-center p-5 sm:p-6">
        <p class="text-xl font-bold text-[var(--price-accent)]">
          {{ catalogStore.formatPrice(preSelectedMug.price) }}
        </p>
        <h3 class="mt-1 text-lg font-semibold tracking-tight">{{ preSelectedMug.name }}</h3>
        <p class="mt-2 text-sm leading-relaxed text-muted-foreground">
          {{ preSelectedMug.descriptionLong || preSelectedMug.descriptionShort }}
        </p>

        <!-- Color variants -->
        <div v-if="preSelectedMug.variants.length > 1" class="mt-4">
          <p class="mb-1.5 text-xs text-muted-foreground">
            {{ t('mugConfigurator.steps.selectMug.colors') }}
          </p>
          <div class="flex flex-wrap gap-2">
            <SwatchButton
              v-for="variant in preSelectedMug.variants"
              :key="variant.id"
              class="size-6 p-0 transition-transform data-[state=selected]:scale-110 data-[state=unselected]:hover:scale-105"
              :color="variantSwatchColor(variant)"
              :label="variant.name"
              :selected="preSelectedVariant.id === variant.id"
              :title="variant.name"
              @click="onSelectVariant(preSelectedMug.id, variant.id)"
            />
          </div>
        </div>

        <!-- Change mug button -->
        <Button variant="outline" size="sm" class="mt-5 w-fit" @click="onChangeMug">
          <ArrowLeftRight class="h-3.5 w-3.5" />
          {{ t('mugConfigurator.steps.selectMug.changeMug') }}
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
          {{ t('mugConfigurator.steps.selectMug.allCategories') }}
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
        {{ t('mugConfigurator.steps.selectMug.error') }}
      </p>
      <Button variant="outline" size="sm" @click="catalogStore.fetchArticles()">
        <RefreshCw class="h-3.5 w-3.5" />
        {{ t('mugConfigurator.steps.selectMug.retry') }}
      </Button>
    </div>

    <!-- Empty state -->
    <div
      v-else-if="showFullGrid && filteredMugs.length === 0"
      class="mt-6 flex flex-col items-center justify-center rounded-xl border-[1.5px] border-dashed border-border bg-surface-empty p-14 text-center sm:mt-8"
    >
      <p class="text-sm text-muted-foreground">
        {{ t('mugConfigurator.steps.selectMug.empty') }}
      </p>
    </div>

    <!-- Mug cards grid -->
    <div
      v-else-if="showFullGrid"
      class="mt-6 grid grid-cols-1 gap-3 sm:mt-8 sm:gap-5 sm:grid-cols-2 md:grid-cols-3"
    >
      <ProductCard
        v-for="(mug, index) in filteredMugs"
        :key="mug.id"
        :article="mug"
        :active-variant="getDisplayVariant(mug)"
        :formatted-price="catalogStore.formatPrice(mug.price)"
        :card-index="index"
        :selected="wizard.selectedMugId === mug.id"
        as="button"
        @click="onSelectMug(mug)"
        @select-variant="onSelectVariant(mug.id, $event)"
      />
    </div>
  </div>
</template>
