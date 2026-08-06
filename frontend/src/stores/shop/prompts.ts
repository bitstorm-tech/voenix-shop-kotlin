import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { formatPrice } from '@/lib/formatPrice'

export interface PromptCategoryDto {
  id: number
  name: string
  position: number
}

export interface PromptSubcategoryDto {
  id: number
  name: string
  position: number
}

export interface PromptPriceDto {
  salesTotalNet: number
  salesTotalGross: number
  salesTotalTax: number
  salesVatRatePercent: number
}

export interface PromptDto {
  id: number
  position: number
  title: string
  category: PromptCategoryDto
  subcategory?: PromptSubcategoryDto
  exampleImageFilename?: string
  llm?: string | null
  price?: PromptPriceDto
}

function comparePromptsByGlobalDisplayOrder(a: PromptDto, b: PromptDto): number {
  return a.position - b.position || a.id - b.id
}

function comparePromptsByNestedDisplayOrder(a: PromptDto, b: PromptDto): number {
  const aSubcategoryMissing = a.subcategory ? 0 : 1
  const bSubcategoryMissing = b.subcategory ? 0 : 1
  const aSubcategoryPosition = a.subcategory?.position ?? Number.MAX_SAFE_INTEGER
  const bSubcategoryPosition = b.subcategory?.position ?? Number.MAX_SAFE_INTEGER
  const aSubcategoryId = a.subcategory?.id ?? Number.MAX_SAFE_INTEGER
  const bSubcategoryId = b.subcategory?.id ?? Number.MAX_SAFE_INTEGER

  return (
    a.category.position - b.category.position ||
    a.category.id - b.category.id ||
    aSubcategoryMissing - bSubcategoryMissing ||
    aSubcategoryPosition - bSubcategoryPosition ||
    aSubcategoryId - bSubcategoryId ||
    a.title.localeCompare(b.title) ||
    a.id - b.id
  )
}

export const usePromptsStore = defineStore('prompts', () => {
  const prompts = ref<PromptDto[]>([])
  const isLoading = shallowRef(false)
  const error = shallowRef<string | null>(null)
  const hasFetched = shallowRef(false)

  const STALE_MS = 5 * 60 * 1000
  let lastFetchedAt = 0

  async function fetchPrompts() {
    if (isLoading.value) return

    const now = Date.now()
    const isStale = now - lastFetchedAt > STALE_MS
    const isFirstLoad = !hasFetched.value

    if (hasFetched.value && !isStale) return

    if (isFirstLoad) {
      isLoading.value = true
      error.value = null
    }

    try {
      const response = await fetch('/api/prompts')

      if (!response.ok) {
        if (isFirstLoad) error.value = `HTTP error ${response.status}`
        return
      }

      const data: { items: PromptDto[] } = await response.json()
      prompts.value = data.items
      hasFetched.value = true
      lastFetchedAt = now
      error.value = null
    } catch (err) {
      if (isFirstLoad) error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      if (isFirstLoad) isLoading.value = false
    }
  }

  const categories = computed(() => {
    const seen = new Map<number, PromptCategoryDto>()
    for (const prompt of prompts.value) {
      if (prompt.category && !seen.has(prompt.category.id)) {
        seen.set(prompt.category.id, prompt.category)
      }
    }
    return Array.from(seen.values()).sort((a, b) => a.position - b.position || a.id - b.id)
  })

  const sortedPrompts = computed(() => [...prompts.value].sort(comparePromptsByGlobalDisplayOrder))

  const subcategoriesByCategoryId = computed(() => {
    const groups = new Map<number, Map<number, PromptSubcategoryDto>>()
    for (const prompt of prompts.value) {
      if (!prompt.subcategory) {
        continue
      }

      const categorySubcategories =
        groups.get(prompt.category.id) ?? new Map<number, PromptSubcategoryDto>()
      if (!categorySubcategories.has(prompt.subcategory.id)) {
        categorySubcategories.set(prompt.subcategory.id, prompt.subcategory)
      }
      groups.set(prompt.category.id, categorySubcategories)
    }

    return groups
  })

  function getPromptsByCategory(categoryId: number | null): PromptDto[] {
    if (categoryId === null) {
      return [...sortedPrompts.value]
    }
    return prompts.value
      .filter((prompt) => prompt.category?.id === categoryId)
      .sort(comparePromptsByNestedDisplayOrder)
  }

  function getSubcategoriesByCategory(categoryId: number): PromptSubcategoryDto[] {
    return Array.from(subcategoriesByCategoryId.value.get(categoryId)?.values() ?? []).sort(
      (a, b) => a.position - b.position || a.id - b.id,
    )
  }

  function getPromptsByCategoryAndSubcategory(
    categoryId: number | null,
    subcategoryId: number | null,
  ): PromptDto[] {
    return getPromptsByCategory(categoryId).filter((prompt) => {
      if (subcategoryId === null) {
        return true
      }

      return prompt.subcategory?.id === subcategoryId
    })
  }

  function getPromptById(id: number): PromptDto | undefined {
    return prompts.value.find((p) => p.id === id)
  }

  function getExampleImageUrl(filename: string): string {
    return `/api/images/public/400/prompt-example-images/${filename}`
  }

  return {
    prompts,
    isLoading,
    error,
    categories,
    sortedPrompts,
    fetchPrompts,
    getPromptsByCategory,
    getSubcategoriesByCategory,
    getPromptsByCategoryAndSubcategory,
    getPromptById,
    formatPrice,
    getExampleImageUrl,
  }
})
