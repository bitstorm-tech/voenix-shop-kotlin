<script setup lang="ts">
import { shallowRef, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { Check, Paintbrush, RefreshCw } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { SegmentedControl, SegmentedControlItem } from '@/components/ui/segmented-control'
import { SelectableCard } from '@/components/ui/selectable-card'
import { useWizardStore } from '@/stores/shop/wizard'
import { usePromptsStore, type PromptDto } from '@/stores/shop/prompts'
import { promptExampleImageUrl } from '@/lib/promptExampleImage'

const { t } = useI18n()
const wizard = useWizardStore()
const promptsStore = usePromptsStore()

const activeCategoryId = shallowRef<number | null>(null)
const activeSubcategoryId = shallowRef<number | null>(null)
const allCategoriesValue = 'all'
const allSubcategoriesValue = 'all'

const activeSubcategories = computed(() =>
  activeCategoryId.value === null
    ? []
    : promptsStore.getSubcategoriesByCategory(activeCategoryId.value),
)

const filteredPrompts = computed(() =>
  promptsStore.getPromptsByCategoryAndSubcategory(
    activeCategoryId.value,
    activeSubcategoryId.value,
  ),
)

const activeCategoryValue = computed({
  get: () =>
    activeCategoryId.value === null ? allCategoriesValue : String(activeCategoryId.value),
  set: (value: string | undefined) => {
    const nextCategoryId =
      value === undefined || value === allCategoriesValue ? null : Number(value)
    if (activeCategoryId.value !== nextCategoryId) {
      activeSubcategoryId.value = null
    }
    activeCategoryId.value = nextCategoryId
  },
})

const activeSubcategoryValue = computed({
  get: () =>
    activeSubcategoryId.value === null ? allSubcategoriesValue : String(activeSubcategoryId.value),
  set: (value: string | undefined) => {
    activeSubcategoryId.value =
      value === undefined || value === allSubcategoriesValue ? null : Number(value)
  },
})

function onSelectPrompt(prompt: PromptDto) {
  wizard.selectPrompt(prompt.id)
}

onMounted(async () => {
  await promptsStore.fetchPrompts()
})
</script>

<template>
  <div class="wizard-step-enter pb-2">
    <h2 class="sr-only">{{ t('mugConfigurator.steps.selectStyle.title') }}</h2>

    <!-- Category filters -->
    <div
      v-if="!promptsStore.isLoading && !promptsStore.error && promptsStore.categories.length > 1"
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
          class="style-pill shrink-0"
        >
          {{ t('mugConfigurator.steps.selectStyle.allCategories') }}
        </SegmentedControlItem>
        <SegmentedControlItem
          v-for="category in promptsStore.categories"
          :key="category.id"
          :value="String(category.id)"
          variant="editor"
          class="style-pill shrink-0"
        >
          {{ category.name }}
        </SegmentedControlItem>
      </SegmentedControl>
    </div>

    <div
      v-if="!promptsStore.isLoading && !promptsStore.error && activeSubcategories.length > 1"
      class="mt-3"
    >
      <SegmentedControl
        v-model="activeSubcategoryValue"
        type="single"
        variant="editor"
        class="scrollbar-hide max-w-full overflow-x-auto"
      >
        <SegmentedControlItem
          :value="allSubcategoriesValue"
          variant="editor"
          class="style-subcategory-pill shrink-0"
        >
          {{ t('mugConfigurator.steps.selectStyle.allSubcategories') }}
        </SegmentedControlItem>
        <SegmentedControlItem
          v-for="subcategory in activeSubcategories"
          :key="subcategory.id"
          :value="String(subcategory.id)"
          variant="editor"
          class="style-subcategory-pill shrink-0"
        >
          {{ subcategory.name }}
        </SegmentedControlItem>
      </SegmentedControl>
    </div>

    <!-- Loading skeleton -->
    <div
      v-if="promptsStore.isLoading"
      class="mt-6 grid grid-cols-2 gap-3 sm:mt-8 sm:grid-cols-3 sm:gap-4 md:grid-cols-4"
    >
      <div
        v-for="n in 8"
        :key="n"
        class="overflow-hidden rounded-xl border-[1.5px] border-border bg-surface-skeleton motion-safe:animate-enter-lift motion-reduce:animate-none"
      >
        <div
          class="aspect-square bg-[oklch(0.93_0.01_50_/_0.6)] motion-safe:animate-skeleton-pulse motion-reduce:animate-none dark:bg-[oklch(0.3_0.01_50_/_0.6)]"
        />
        <div
          class="space-y-2 bg-[oklch(0.93_0.01_50_/_0.6)] p-3 motion-safe:animate-skeleton-pulse motion-reduce:animate-none dark:bg-[oklch(0.3_0.01_50_/_0.6)]"
        >
          <div
            class="h-4 w-3/4 rounded bg-[oklch(0.91_0.01_50_/_0.5)] motion-safe:animate-skeleton-pulse motion-reduce:animate-none"
          />
          <div
            class="h-3 w-1/2 rounded bg-[oklch(0.91_0.01_50_/_0.5)] motion-safe:animate-skeleton-pulse motion-reduce:animate-none"
          />
        </div>
      </div>
    </div>

    <!-- Error state -->
    <div
      v-else-if="promptsStore.error"
      class="mt-6 flex flex-col items-center gap-4 rounded-xl border-[1.5px] border-dashed border-border bg-surface-empty p-10 text-center sm:mt-8"
    >
      <p class="text-sm font-medium text-destructive">
        {{ t('mugConfigurator.steps.selectStyle.error') }}
      </p>
      <Button variant="outline" size="sm" @click="promptsStore.fetchPrompts()">
        <RefreshCw class="h-3.5 w-3.5" />
        {{ t('mugConfigurator.steps.selectStyle.retry') }}
      </Button>
    </div>

    <!-- Empty state -->
    <div
      v-else-if="filteredPrompts.length === 0"
      class="mt-6 flex flex-col items-center justify-center rounded-xl border-[1.5px] border-dashed border-border bg-surface-empty p-14 text-center sm:mt-8"
    >
      <p class="text-sm text-muted-foreground">
        {{ t('mugConfigurator.steps.selectStyle.empty') }}
      </p>
    </div>

    <!-- Style cards grid -->
    <div v-else class="mt-6 grid grid-cols-2 gap-3 sm:mt-8 sm:grid-cols-3 sm:gap-4 md:grid-cols-4">
      <SelectableCard
        v-for="(prompt, index) in filteredPrompts"
        :key="prompt.id"
        class="group relative flex cursor-pointer flex-col overflow-hidden !rounded-xl border-[1.5px] border-border bg-surface-card !p-0 text-left shadow-[0_1px_3px_oklch(0_0_0_/_0.04),0_4px_16px_oklch(0_0_0_/_0.03)] transition-all duration-300 [animation-delay:calc(var(--card-index,0)*60ms)] hover:bg-surface-card motion-safe:animate-enter-lift motion-reduce:animate-none motion-reduce:transition-none data-[state=selected]:border-[var(--brand)] data-[state=selected]:bg-surface-card data-[state=selected]:shadow-[0_0_0_1px_oklch(0.61_0.19_35_/_0.15),0_4px_12px_oklch(0.61_0.19_35_/_0.1),0_8px_24px_oklch(0.61_0.19_35_/_0.06)] data-[state=selected]:hover:-translate-y-0.5 data-[state=selected]:hover:bg-surface-card data-[state=selected]:hover:shadow-[0_0_0_1px_oklch(0.61_0.19_35_/_0.2),0_6px_16px_oklch(0.61_0.19_35_/_0.12),0_12px_32px_oklch(0.61_0.19_35_/_0.08)] data-[state=unselected]:bg-surface-card data-[state=unselected]:hover:-translate-y-1 data-[state=unselected]:hover:border-[var(--surface-card-hover-border)] data-[state=unselected]:hover:bg-surface-card data-[state=unselected]:hover:shadow-[0_4px_12px_oklch(0_0_0_/_0.06),0_12px_32px_oklch(0_0_0_/_0.05)] motion-reduce:data-[state=selected]:hover:translate-y-0 motion-reduce:data-[state=unselected]:hover:translate-y-0 dark:shadow-[0_1px_3px_oklch(0_0_0_/_0.3),0_4px_16px_oklch(0_0_0_/_0.25)] dark:data-[state=unselected]:hover:shadow-[0_4px_12px_oklch(0_0_0_/_0.4),0_12px_32px_oklch(0_0_0_/_0.35)]"
        :selected="wizard.selectedPromptId === prompt.id"
        :style="{ '--card-index': index }"
        @click="onSelectPrompt(prompt)"
      >
        <!-- Noise texture overlay -->
        <div class="style-card-noise pointer-events-none absolute inset-0 z-[1]" />

        <!-- Selected badge -->
        <div
          v-if="wizard.selectedPromptId === prompt.id"
          class="absolute right-2 top-2 z-10 flex items-center gap-1 rounded-sm bg-[linear-gradient(135deg,oklch(0.61_0.19_35),oklch(0.68_0.18_45))] px-2 py-0.5 text-[11px] font-semibold text-white shadow-[0_2px_8px_oklch(0.61_0.19_35_/_0.3)] motion-safe:animate-enter-pop motion-reduce:animate-none sm:right-3 sm:top-3 sm:px-2.5 sm:py-1 sm:text-xs"
        >
          <Check class="size-3" />
          {{ t('mugConfigurator.steps.selectStyle.selected') }}
        </div>

        <!-- Image area -->
        <div class="relative aspect-square">
          <div class="absolute inset-0 bg-surface-image" />
          <img
            v-if="prompt.exampleImageFilename"
            :src="promptExampleImageUrl(prompt.exampleImageFilename, 400)"
            :alt="prompt.title"
            class="relative z-[2] size-full object-cover transition-transform duration-300 group-hover:scale-105 motion-reduce:transition-none motion-reduce:group-hover:scale-100"
          />
          <div v-else class="relative z-[2] flex size-full items-center justify-center">
            <Paintbrush
              class="size-10 text-muted-foreground/40 transition-transform duration-300 group-hover:scale-110 motion-reduce:transition-none motion-reduce:group-hover:scale-100 sm:size-12"
            />
          </div>
        </div>

        <!-- Content area -->
        <div class="relative z-[2] p-2.5 sm:p-3">
          <h3 class="text-sm font-semibold tracking-tight line-clamp-1">{{ prompt.title }}</h3>
          <div class="mt-1 flex items-center gap-2">
            <p v-if="prompt.price" class="text-sm font-bold text-[var(--price-accent)]">
              {{ promptsStore.formatPrice(prompt.price.salesTotalGross) }}
            </p>
            <span v-if="prompt.category" class="text-[11px] text-muted-foreground">
              {{ prompt.subcategory?.name ?? prompt.category.name }}
            </span>
          </div>
        </div>
      </SelectableCard>
    </div>
  </div>
</template>

<style scoped>
/* CSS exception: the encoded SVG noise texture stays local; an arbitrary Tailwind class would be less readable. */
.style-card-noise {
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='0.04'/%3E%3C/svg%3E");
  background-repeat: repeat;
  background-size: 150px 150px;
  opacity: 0.25;
}
</style>
