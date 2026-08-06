<script setup lang="ts">
import { computed, onMounted, ref, shallowRef, watch } from 'vue'
import {
  useRoute,
  useRouter,
  RouterLink,
  type LocationQueryValue,
  type RouteLocationRaw,
} from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ChevronRight, RefreshCw } from 'lucide-vue-next'
import { useMugsStore, type MugDto, type MugVariantDto } from '@/stores/shop/mugs'
import { useArticleCategoriesStore } from '@/stores/shop/articleCategories'
import { useEditorStore } from '@/stores/shop/editor'
import { Button } from '@/components/ui/button'
import MugCard from '@/components/shop/MugCard.vue'
import MugCategoryFilter from '@/components/shop/mugs/MugCategoryFilter.vue'
import MugGridSkeleton from '@/components/shop/mugs/MugGridSkeleton.vue'
import MugOverviewHero from '@/components/shop/mugs/MugOverviewHero.vue'
import { useToast } from '@/composables/useToast'
import { resolveDisplayMugVariant } from '@/lib/changeMugSelection'

interface MugCategoryFilterItem {
  id: number | null
  label: string
  count: number
  active: boolean
  to: RouteLocationRaw
}

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const mugsStore = useMugsStore()
const categoriesStore = useArticleCategoriesStore()
const editorStore = useEditorStore()
const { toast } = useToast()

function parseRouteNumberParam(value?: LocationQueryValue | LocationQueryValue[]) {
  const rawValue = Array.isArray(value) ? value[0] : value
  if (!rawValue || !/^\d+$/.test(rawValue)) {
    return null
  }

  const parsed = Number(rawValue)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
}

const categoryId = computed(() => parseRouteNumberParam(route.query.category))
const subcategoryId = computed(() => parseRouteNumberParam(route.query.subcategory))

onMounted(() => {
  void Promise.all([mugsStore.fetchMugs(), categoriesStore.fetchCategories()])
})

watch(
  () => [categoriesStore.hasFetched, route.query.category, route.query.subcategory] as const,
  ([hasFetched, categoryQuery, subcategoryQuery]) => {
    if (!hasFetched) {
      return
    }

    const hasCategoryQuery = categoryQuery !== undefined
    const hasSubcategoryQuery = subcategoryQuery !== undefined
    const parsedCategoryId = parseRouteNumberParam(categoryQuery)
    const category = categoriesStore.mugCategories.find((item) => item.id === parsedCategoryId)
    const nextQuery = { ...route.query }
    let shouldReplace = false

    if (!hasCategoryQuery || category === undefined) {
      if (hasCategoryQuery) {
        delete nextQuery.category
        shouldReplace = true
      }
      if (hasSubcategoryQuery) {
        delete nextQuery.subcategory
        shouldReplace = true
      }
    } else {
      if (Array.isArray(categoryQuery)) {
        nextQuery.category = String(category.id)
        shouldReplace = true
      }

      if (hasSubcategoryQuery) {
        const parsedSubcategoryId = parseRouteNumberParam(subcategoryQuery)
        const subcategory = category.subcategories?.find((item) => item.id === parsedSubcategoryId)
        if (subcategory === undefined) {
          delete nextQuery.subcategory
          shouldReplace = true
        } else if (Array.isArray(subcategoryQuery)) {
          nextQuery.subcategory = String(subcategory.id)
          shouldReplace = true
        }
      }
    }

    if (shouldReplace) {
      void router.replace({ query: nextQuery })
    }
  },
  { immediate: true },
)

const filteredMugs = computed(() => mugsStore.getDisplayMugs(categoryId.value, subcategoryId.value))
const hasMugs = computed(() => mugsStore.mugs.length > 0)
const isInitialLoading = computed(() => mugsStore.isLoading && !hasMugs.value)

const activeCategory = computed(() => {
  if (categoryId.value === null) {
    return null
  }
  return categoriesStore.mugCategories.find((c) => c.id === categoryId.value) ?? null
})

const categoryName = computed(() => (activeCategory.value ? activeCategory.value.name : null))

const activeSubcategory = computed(() => {
  if (subcategoryId.value === null) {
    return null
  }

  const subcategories = activeCategory.value?.subcategories ?? []

  return subcategories.find((subcategory) => subcategory.id === subcategoryId.value) ?? null
})

const subcategoryName = computed(() =>
  activeSubcategory.value ? activeSubcategory.value.name : null,
)
const activeSegmentName = computed(() => subcategoryName.value ?? categoryName.value)

const categoryFilters = computed<MugCategoryFilterItem[]>(() => [
  {
    id: null,
    label: t('mugOverview.filters.all'),
    count: mugsStore.mugs.length,
    active: categoryId.value === null,
    to: { name: 'mugs' },
  },
  ...categoriesStore.mugCategories.map((category) => ({
    id: category.id,
    label: category.name,
    count: mugsStore.getDisplayMugs(category.id).length,
    active: categoryId.value === category.id,
    to: {
      name: 'mugs',
      query: { category: category.id.toString() },
    },
  })),
])

const resultLabel = computed(() => {
  if (subcategoryName.value) {
    return t('mugOverview.results.filteredSubcategory', {
      count: filteredMugs.value.length,
      category: categoryName.value,
      subcategory: subcategoryName.value,
    })
  }

  if (categoryName.value) {
    return t('mugOverview.results.filtered', {
      count: filteredMugs.value.length,
      category: categoryName.value,
    })
  }

  return t('mugOverview.results.all', { count: filteredMugs.value.length })
})

// Track selected variant per mug
const selectedVariants = ref<Record<number, number>>({})
const openingMugId = shallowRef<number | null>(null)

function getSelectedVariant(mug: MugDto): MugVariantDto | null {
  return resolveDisplayMugVariant(mug, mug.id, selectedVariants.value[mug.id] ?? null)
}

function selectVariant(mugId: number, variantId: number) {
  selectedVariants.value[mugId] = variantId
}

function retryMugs() {
  void mugsStore.fetchMugs()
}

async function openProductDraft(mug: MugDto) {
  if (openingMugId.value !== null) return

  const variant = getSelectedVariant(mug)
  if (!variant) {
    toast({
      title: t('mugOverview.unavailable'),
      variant: 'destructive',
    })
    return
  }

  openingMugId.value = mug.id

  try {
    const draft = editorStore.createDraftFromProduct({
      articleId: mug.id,
      variantId: variant.id,
    })

    await router.push({ name: 'editor', params: { draftId: draft.id } })
  } catch (error) {
    toast({
      title: error instanceof Error ? error.message : t('editor.openDraftError'),
      variant: 'destructive',
    })
  } finally {
    openingMugId.value = null
  }
}
</script>

<template>
  <div class="grid gap-5">
    <nav class="flex items-center gap-2 text-sm text-muted-foreground" aria-label="Breadcrumb">
      <RouterLink to="/" class="hover:text-foreground transition-colors">
        {{ t('mugOverview.breadcrumb.home') }}
      </RouterLink>
      <ChevronRight class="size-4" />
      <RouterLink to="/mugs" class="hover:text-foreground transition-colors">
        {{ t('mugOverview.breadcrumb.mugs') }}
      </RouterLink>
      <template v-if="categoryName">
        <ChevronRight class="size-4" />
        <span class="text-foreground">{{ categoryName }}</span>
      </template>
      <template v-if="subcategoryName">
        <ChevronRight class="size-4" />
        <span class="text-foreground">{{ subcategoryName }}</span>
      </template>
    </nav>

    <MugOverviewHero
      :active-category-name="activeSegmentName"
      :visible-count="filteredMugs.length"
      :total-count="mugsStore.mugs.length"
      :is-loading="isInitialLoading"
    />

    <MugCategoryFilter
      v-if="categoryFilters.length > 1 && !isInitialLoading && !mugsStore.error"
      :filters="categoryFilters"
      :result-label="resultLabel"
    />

    <MugGridSkeleton v-if="isInitialLoading" />

    <div
      v-else-if="mugsStore.error"
      class="flex min-h-64 flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-destructive-border bg-destructive-soft px-6 py-14 text-center"
    >
      <p class="font-bold text-foreground">{{ t('mugOverview.error.title') }}</p>
      <p class="max-w-md text-sm leading-relaxed text-muted-foreground">
        {{ t('mugOverview.error.description') }}
      </p>
      <Button variant="outline" size="sm" @click="retryMugs">
        <RefreshCw class="h-3.5 w-3.5" aria-hidden="true" />
        {{ t('mugOverview.error.retry') }}
      </Button>
    </div>

    <div
      v-else-if="filteredMugs.length > 0"
      class="grid grid-cols-1 gap-3 pt-1 sm:grid-cols-2 sm:gap-5 md:grid-cols-3 lg:grid-cols-4"
    >
      <MugCard
        v-for="(mug, index) in filteredMugs"
        :key="mug.id"
        :mug="mug"
        :active-variant="getSelectedVariant(mug)"
        :formatted-price="mugsStore.formatPrice(mug.price)"
        :card-index="index"
        @select-variant="selectVariant(mug.id, $event)"
      >
        <template #action>
          <Button
            class="mt-4 w-full"
            variant="default"
            :disabled="openingMugId !== null || !getSelectedVariant(mug)"
            data-testid="mug-open-editor"
            @click.stop="openProductDraft(mug)"
          >
            {{ getSelectedVariant(mug) ? t('mugOverview.select') : t('mugOverview.unavailable') }}
          </Button>
        </template>
      </MugCard>
    </div>

    <div
      v-else
      class="flex min-h-64 flex-col items-center justify-center gap-3 rounded-xl border border-dashed bg-background-soft px-6 py-14 text-center"
    >
      <p class="font-bold text-foreground">{{ t('mugOverview.noMugs') }}</p>
      <p class="max-w-md text-sm leading-relaxed text-muted-foreground">
        {{ t('mugOverview.emptyDescription') }}
      </p>
      <Button
        v-if="categoryId !== null || subcategoryId !== null"
        as-child
        variant="outline"
        size="sm"
      >
        <RouterLink :to="{ name: 'mugs' }">{{ t('mugOverview.filters.all') }}</RouterLink>
      </Button>
    </div>
  </div>
</template>
