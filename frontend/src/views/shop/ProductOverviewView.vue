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
import {
  useCatalogStore,
  type ShopArticle,
  type ShopArticleType,
  type ShopArticleVariant,
} from '@/stores/shop/catalog'
import { useArticleCategoriesStore } from '@/stores/shop/articleCategories'
import { useEditorStore } from '@/stores/shop/editor'
import { Button } from '@/components/ui/button'
import ProductCard from '@/components/shop/ProductCard.vue'
import MugCategoryFilter from '@/components/shop/mugs/MugCategoryFilter.vue'
import MugGridSkeleton from '@/components/shop/mugs/MugGridSkeleton.vue'
import MugOverviewHero from '@/components/shop/mugs/MugOverviewHero.vue'
import { useToast } from '@/composables/useToast'
import { resolveDisplayVariant } from '@/lib/changeArticleSelection'

interface ProductCategoryFilterItem {
  id: number | null
  label: string
  count: number
  active: boolean
  to: RouteLocationRaw
}

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const catalogStore = useCatalogStore()
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

const ARTICLE_TYPES: ShopArticleType[] = ['MUG', 'TSHIRT']

/** The optional type narrows the grid to one article type; anything else reads as "all types". */
function parseArticleTypeParam(
  value?: LocationQueryValue | LocationQueryValue[],
): ShopArticleType | null {
  const rawValue = Array.isArray(value) ? value[0] : value
  return ARTICLE_TYPES.find((articleType) => articleType === rawValue) ?? null
}

const categoryId = computed(() => parseRouteNumberParam(route.query.category))
const subcategoryId = computed(() => parseRouteNumberParam(route.query.subcategory))
const articleType = computed(() => parseArticleTypeParam(route.query.type))
const typeQuery = computed(() => (articleType.value === null ? {} : { type: articleType.value }))

onMounted(() => {
  void Promise.all([catalogStore.fetchArticles(), categoriesStore.fetchCategories()])
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
    const category = categoriesStore.categories.find((item) => item.id === parsedCategoryId)
    const nextQuery = { ...route.query }
    let shouldReplace = false

    if (route.query.type !== undefined && parseArticleTypeParam(route.query.type) === null) {
      delete nextQuery.type
      shouldReplace = true
    }

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
        const subcategory = category.subcategories.find((item) => item.id === parsedSubcategoryId)
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

const filteredArticles = computed(() =>
  catalogStore.getDisplayArticles(categoryId.value, subcategoryId.value, articleType.value),
)
const hasArticles = computed(() => catalogStore.articles.length > 0)
const isInitialLoading = computed(() => catalogStore.isLoading && !hasArticles.value)

const activeCategory = computed(() => {
  if (categoryId.value === null) {
    return null
  }
  return categoriesStore.categories.find((c) => c.id === categoryId.value) ?? null
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

const categoryFilters = computed<ProductCategoryFilterItem[]>(() => [
  {
    id: null,
    label: t('productOverview.filters.all'),
    count: catalogStore.getDisplayArticles(null, null, articleType.value).length,
    active: categoryId.value === null,
    to: { name: 'products', query: { ...typeQuery.value } },
  },
  ...categoriesStore.categories.map((category) => ({
    id: category.id,
    label: category.name,
    count: catalogStore.getDisplayArticles(category.id, null, articleType.value).length,
    active: categoryId.value === category.id,
    to: {
      name: 'products',
      query: { ...typeQuery.value, category: category.id.toString() },
    },
  })),
])

const resultLabel = computed(() => {
  if (subcategoryName.value) {
    return t('productOverview.results.filteredSubcategory', {
      count: filteredArticles.value.length,
      category: categoryName.value,
      subcategory: subcategoryName.value,
    })
  }

  if (categoryName.value) {
    return t('productOverview.results.filtered', {
      count: filteredArticles.value.length,
      category: categoryName.value,
    })
  }

  return t('productOverview.results.all', { count: filteredArticles.value.length })
})

// Track selected variant per article
const selectedVariants = ref<Record<number, number>>({})
const openingArticleId = shallowRef<number | null>(null)

function getSelectedVariant(article: ShopArticle): ShopArticleVariant | null {
  return resolveDisplayVariant(article, article.id, selectedVariants.value[article.id] ?? null)
}

function selectVariant(articleId: number, variantId: number) {
  selectedVariants.value[articleId] = variantId
}

function retryArticles() {
  void catalogStore.fetchArticles()
}

async function openProductDraft(article: ShopArticle) {
  if (openingArticleId.value !== null) return

  const variant = getSelectedVariant(article)
  if (!variant) {
    toast({
      title: t('productOverview.unavailable'),
      variant: 'destructive',
    })
    return
  }

  openingArticleId.value = article.id

  try {
    const draft = editorStore.createDraftFromProduct({
      articleId: article.id,
      variantId: variant.id,
    })

    await router.push({ name: 'editor', params: { draftId: draft.id } })
  } catch (error) {
    toast({
      title: error instanceof Error ? error.message : t('editor.openDraftError'),
      variant: 'destructive',
    })
  } finally {
    openingArticleId.value = null
  }
}
</script>

<template>
  <div class="grid gap-5">
    <nav class="flex items-center gap-2 text-sm text-muted-foreground" aria-label="Breadcrumb">
      <RouterLink to="/" class="hover:text-foreground transition-colors">
        {{ t('productOverview.breadcrumb.home') }}
      </RouterLink>
      <ChevronRight class="size-4" />
      <RouterLink to="/products" class="hover:text-foreground transition-colors">
        {{ t('productOverview.breadcrumb.products') }}
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
      :visible-count="filteredArticles.length"
      :total-count="catalogStore.articles.length"
      :is-loading="isInitialLoading"
    />

    <MugCategoryFilter
      v-if="categoryFilters.length > 1 && !isInitialLoading && !catalogStore.error"
      :filters="categoryFilters"
      :result-label="resultLabel"
    />

    <MugGridSkeleton v-if="isInitialLoading" />

    <div
      v-else-if="catalogStore.error"
      class="flex min-h-64 flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-destructive-border bg-destructive-soft px-6 py-14 text-center"
    >
      <p class="font-bold text-foreground">{{ t('productOverview.error.title') }}</p>
      <p class="max-w-md text-sm leading-relaxed text-muted-foreground">
        {{ t('productOverview.error.description') }}
      </p>
      <Button variant="outline" size="sm" @click="retryArticles">
        <RefreshCw class="h-3.5 w-3.5" aria-hidden="true" />
        {{ t('productOverview.error.retry') }}
      </Button>
    </div>

    <div
      v-else-if="filteredArticles.length > 0"
      class="grid grid-cols-1 gap-3 pt-1 sm:grid-cols-2 sm:gap-5 md:grid-cols-3 lg:grid-cols-4"
    >
      <ProductCard
        v-for="(article, index) in filteredArticles"
        :key="article.id"
        :article="article"
        :active-variant="getSelectedVariant(article)"
        :formatted-price="catalogStore.formatPrice(article.price)"
        :card-index="index"
        @select-variant="selectVariant(article.id, $event)"
      >
        <template #action>
          <Button
            class="mt-4 w-full"
            variant="default"
            :disabled="openingArticleId !== null || !getSelectedVariant(article)"
            data-testid="product-open-editor"
            @click.stop="openProductDraft(article)"
          >
            {{
              getSelectedVariant(article)
                ? t('productOverview.select')
                : t('productOverview.unavailable')
            }}
          </Button>
        </template>
      </ProductCard>
    </div>

    <div
      v-else
      class="flex min-h-64 flex-col items-center justify-center gap-3 rounded-xl border border-dashed bg-background-soft px-6 py-14 text-center"
    >
      <p class="font-bold text-foreground">{{ t('productOverview.empty') }}</p>
      <p class="max-w-md text-sm leading-relaxed text-muted-foreground">
        {{ t('productOverview.emptyDescription') }}
      </p>
      <Button
        v-if="categoryId !== null || subcategoryId !== null"
        as-child
        variant="outline"
        size="sm"
      >
        <RouterLink :to="{ name: 'products', query: { ...typeQuery } }">
          {{ t('productOverview.filters.all') }}
        </RouterLink>
      </Button>
    </div>
  </div>
</template>
