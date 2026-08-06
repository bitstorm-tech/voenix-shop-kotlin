import { computed } from 'vue'
import { useRoute, useRouter, type LocationQuery, type LocationQueryValue } from 'vue-router'
import type {
  AdminPromptCategoryDto,
  AdminPromptSubcategoryListItemDto,
} from '@/stores/admin/promptCategories'
import type { AdminPromptListItemDto } from '@/stores/admin/prompts'

export const WITHOUT_SUBCATEGORY = 'none'

export const PROMPT_STATUS_FILTERS = ['all', 'active', 'inactive', 'archived'] as const
export type PromptStatusFilter = (typeof PROMPT_STATUS_FILTERS)[number]
export type PromptStatus = Exclude<PromptStatusFilter, 'all'>
export type PromptSubcategoryFilter = number | typeof WITHOUT_SUBCATEGORY | null

export interface AdminPromptListFilterCriteria {
  categoryId: number | null
  subcategoryId: PromptSubcategoryFilter
  status: PromptStatusFilter
  title: string
}

export interface UseAdminPromptListFiltersOptions {
  prompts: () => readonly Readonly<AdminPromptListItemDto>[]
  categories: () => readonly Readonly<AdminPromptCategoryDto>[]
  subcategories: () => readonly Readonly<AdminPromptSubcategoryListItemDto>[]
}

export function derivePromptStatus(
  prompt: Pick<AdminPromptListItemDto, 'active' | 'archived'>,
): PromptStatus {
  if (prompt.archived) {
    return 'archived'
  }
  return prompt.active ? 'active' : 'inactive'
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

export function useAdminPromptListFilters(options: UseAdminPromptListFiltersOptions) {
  const route = useRoute()
  const router = useRouter()

  const categoryId = computed(() => {
    const id = parseEntityId(singleQueryValue(route.query.category))
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
    if (categoryId.value === null) {
      return []
    }

    return options
      .subcategories()
      .filter((subcategory) => subcategory.promptCategory.id === categoryId.value)
  })

  const subcategoryId = computed<PromptSubcategoryFilter>(() => {
    if (categoryId.value === null) {
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

  const status = computed<PromptStatusFilter>(() => {
    const raw = singleQueryValue(route.query.status)
    return raw !== null &&
      raw !== 'all' &&
      PROMPT_STATUS_FILTERS.includes(raw as PromptStatusFilter)
      ? (raw as PromptStatusFilter)
      : 'all'
  })

  const title = computed(() => {
    const raw = singleQueryValue(route.query.title)
    return raw !== null && raw.trim() !== '' ? raw : ''
  })

  const criteria = computed<AdminPromptListFilterCriteria>(() => ({
    categoryId: categoryId.value,
    subcategoryId: subcategoryId.value,
    status: status.value,
    title: title.value,
  }))

  const hasActiveFilters = computed(
    () =>
      categoryId.value !== null ||
      subcategoryId.value !== null ||
      status.value !== 'all' ||
      title.value !== '',
  )

  const filteredPrompts = computed(() => {
    const titleQuery = title.value.trim().toLowerCase()

    return options.prompts().filter((prompt) => {
      if (categoryId.value !== null && prompt.category.id !== categoryId.value) {
        return false
      }
      if (subcategoryId.value === WITHOUT_SUBCATEGORY && prompt.subcategory != null) {
        return false
      }
      if (
        typeof subcategoryId.value === 'number' &&
        prompt.subcategory?.id !== subcategoryId.value
      ) {
        return false
      }
      if (status.value !== 'all' && derivePromptStatus(prompt) !== status.value) {
        return false
      }
      if (titleQuery !== '' && !prompt.title.toLowerCase().includes(titleQuery)) {
        return false
      }
      return true
    })
  })

  function updateQuery(
    patch: Partial<Record<'category' | 'subcategory' | 'status' | 'title', string | null>>,
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

  function setCategoryId(value: number | null) {
    if (value === categoryId.value) {
      return
    }
    updateQuery({ category: value === null ? null : String(value), subcategory: null })
  }

  function setSubcategoryId(value: PromptSubcategoryFilter) {
    updateQuery({
      subcategory:
        value === null ? null : value === WITHOUT_SUBCATEGORY ? WITHOUT_SUBCATEGORY : String(value),
    })
  }

  function setStatus(value: PromptStatusFilter) {
    updateQuery({ status: value === 'all' ? null : value })
  }

  function setTitle(value: string) {
    updateQuery({ title: value.trim() === '' ? null : value })
  }

  function resetFilters() {
    updateQuery({ category: null, subcategory: null, status: null, title: null })
  }

  return {
    criteria,
    subcategoryOptions,
    filteredPrompts,
    hasActiveFilters,
    setCategoryId,
    setSubcategoryId,
    setStatus,
    setTitle,
    resetFilters,
  }
}

export type AdminPromptListFiltersController = ReturnType<typeof useAdminPromptListFilters>
