import { computed } from 'vue'
import { useRoute, useRouter, type LocationQuery, type LocationQueryValue } from 'vue-router'
import type { AdminArticleCategoryDto } from '@/stores/admin/articleCategories'
import type { AdminArticleListItemDto } from '@/stores/admin/articles'
import type { AdminArticleSubcategoryDto } from '@/stores/admin/articleSubcategories'

export const WITHOUT_CATEGORY = 'none'
export const WITHOUT_SUBCATEGORY = 'none'

export const ARTICLE_STATUS_FILTERS = ['all', 'active', 'inactive'] as const
export type ArticleStatusFilter = (typeof ARTICLE_STATUS_FILTERS)[number]
export type ArticleCategoryFilter = number | typeof WITHOUT_CATEGORY | null
export type ArticleSubcategoryFilter = number | typeof WITHOUT_SUBCATEGORY | null

export interface AdminArticleListFilterCriteria {
  categoryId: ArticleCategoryFilter
  subcategoryId: ArticleSubcategoryFilter
  status: ArticleStatusFilter
  name: string
}

export interface UseAdminArticleListFiltersOptions<
  TArticle extends AdminArticleListItemDto = AdminArticleListItemDto,
> {
  articles: () => readonly Readonly<TArticle>[]
  categories: () => readonly Readonly<AdminArticleCategoryDto>[]
  subcategories: () => readonly Readonly<AdminArticleSubcategoryDto>[]
}

function singleQueryValue(
  value: LocationQueryValue | LocationQueryValue[] | undefined,
): string | null {
  const raw = Array.isArray(value) ? value[0] : value
  return typeof raw === 'string' && raw !== '' ? raw : null
}

function parseEntityId(value: string | null): number | null {
  if (value === null || !/^\d+$/.test(value)) {
    return null
  }

  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

export function useAdminArticleListFilters<
  TArticle extends AdminArticleListItemDto = AdminArticleListItemDto,
>(options: UseAdminArticleListFiltersOptions<TArticle>) {
  const route = useRoute()
  const router = useRouter()

  const categoryId = computed<ArticleCategoryFilter>(() => {
    const raw = singleQueryValue(route.query.category)
    if (raw === null) {
      return null
    }
    if (raw === WITHOUT_CATEGORY) {
      return WITHOUT_CATEGORY
    }

    const id = parseEntityId(raw)
    if (id === null) {
      return null
    }

    // Ids referencing nothing are only rejected once the reference list is
    // loaded; before that the filter stays applied so a shared link does not
    // flash the unfiltered list while references are still loading.
    const categories = options.categories()
    return categories.length === 0 || categories.some((category) => category.id === id) ? id : null
  })

  const subcategoryOptions = computed(() => {
    if (typeof categoryId.value !== 'number') {
      return []
    }

    return options
      .subcategories()
      .filter((subcategory) => subcategory.categoryId === categoryId.value)
  })

  const subcategoryId = computed<ArticleSubcategoryFilter>(() => {
    if (typeof categoryId.value !== 'number') {
      return null
    }

    const raw = singleQueryValue(route.query.subcategory)
    if (raw === null) {
      return null
    }
    if (raw === WITHOUT_SUBCATEGORY) {
      return WITHOUT_SUBCATEGORY
    }

    const id = parseEntityId(raw)
    if (id === null) {
      return null
    }

    const subcategories = options.subcategories()
    return subcategories.length === 0 ||
      subcategoryOptions.value.some((subcategory) => subcategory.id === id)
      ? id
      : null
  })

  const status = computed<ArticleStatusFilter>(() => {
    const raw = singleQueryValue(route.query.status)
    return raw !== null &&
      raw !== 'all' &&
      ARTICLE_STATUS_FILTERS.includes(raw as ArticleStatusFilter)
      ? (raw as ArticleStatusFilter)
      : 'all'
  })

  const name = computed(() => {
    const raw = singleQueryValue(route.query.name)
    return raw !== null && raw.trim() !== '' ? raw : ''
  })

  const criteria = computed<AdminArticleListFilterCriteria>(() => ({
    categoryId: categoryId.value,
    subcategoryId: subcategoryId.value,
    status: status.value,
    name: name.value,
  }))

  const hasActiveFilters = computed(
    () =>
      categoryId.value !== null ||
      subcategoryId.value !== null ||
      status.value !== 'all' ||
      name.value !== '',
  )

  const filteredArticles = computed(() => {
    const nameQuery = name.value.trim().toLowerCase()

    return options.articles().filter((article) => {
      if (categoryId.value === WITHOUT_CATEGORY && article.categoryId !== null) {
        return false
      }
      if (typeof categoryId.value === 'number' && article.categoryId !== categoryId.value) {
        return false
      }
      if (subcategoryId.value === WITHOUT_SUBCATEGORY && article.subcategoryId !== null) {
        return false
      }
      if (
        typeof subcategoryId.value === 'number' &&
        article.subcategoryId !== subcategoryId.value
      ) {
        return false
      }
      if (status.value !== 'all' && (status.value === 'active') !== article.active) {
        return false
      }
      if (nameQuery !== '' && !article.name.toLowerCase().includes(nameQuery)) {
        return false
      }
      return true
    })
  })

  function updateQuery(
    patch: Partial<Record<'category' | 'subcategory' | 'status' | 'name', string | null>>,
  ) {
    const query: LocationQuery = { ...route.query }
    for (const [key, value] of Object.entries(patch)) {
      if (value === null) {
        delete query[key]
      } else {
        query[key] = value
      }
    }
    void router.replace({ query })
  }

  function setCategoryId(value: ArticleCategoryFilter) {
    if (value === categoryId.value) {
      return
    }
    updateQuery({
      category:
        value === null ? null : value === WITHOUT_CATEGORY ? WITHOUT_CATEGORY : String(value),
      subcategory: null,
    })
  }

  function setSubcategoryId(value: ArticleSubcategoryFilter) {
    updateQuery({
      subcategory:
        value === null ? null : value === WITHOUT_SUBCATEGORY ? WITHOUT_SUBCATEGORY : String(value),
    })
  }

  function setStatus(value: ArticleStatusFilter) {
    updateQuery({ status: value === 'all' ? null : value })
  }

  function setName(value: string) {
    updateQuery({ name: value.trim() === '' ? null : value })
  }

  function resetFilters() {
    updateQuery({ category: null, subcategory: null, status: null, name: null })
  }

  return {
    criteria,
    subcategoryOptions,
    filteredArticles,
    hasActiveFilters,
    setCategoryId,
    setSubcategoryId,
    setStatus,
    setName,
    resetFilters,
  }
}

export type AdminArticleListFiltersController = ReturnType<typeof useAdminArticleListFilters>
