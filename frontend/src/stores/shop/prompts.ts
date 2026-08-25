import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { fetchJson } from '@/lib/api'

/**
 * A prompt category or subcategory as the storefront sees it. One type serves both levels: the
 * storefront list is the only source for either name, and a subcategory's `position` is the one
 * inside its category.
 */
export interface PromptCategoryDto {
  id: number
  name: string
  position: number
}

/** What a storefront row shows of a prompt's price. The amounts are integer cents. */
export interface PromptPriceDto {
  salesTotalNet: number
  salesTotalGross: number
  salesTotalTax: number
  /**
   * The gross total before the discount, and `null` for a prompt whose price has none. The three
   * amounts above are always what the customer pays.
   */
  regularSalesTotalGross: number | null
  /** A whole-number percentage, never a decimal. */
  salesVatRatePercent: number
}

/**
 * One prompt as the storefront sees it. There is no `promptText` — the composed generation text is
 * what the shop sells — and no `active`/`archived` flags, because only visible prompts are listed.
 *
 * `price` is `null` when the prompt has no price row. A `0` is a real price, not a placeholder.
 */
export interface PromptDto {
  id: number
  position: number
  title: string
  category: PromptCategoryDto
  subcategory: PromptCategoryDto | null
  exampleImageFilename: string | null
  llm: string | null
  price: PromptPriceDto | null
}

export const usePromptsStore = defineStore('prompts', () => {
  const prompts = ref<PromptDto[]>([])
  const isLoading = shallowRef(false)
  const error = shallowRef<string | null>(null)
  const hasFetched = shallowRef(false)

  const STALE_MS = 5 * 60 * 1000
  let lastFetchedAt = 0
  let lastFetchedCategoryId: number | null = null

  /**
   * Loads the storefront prompts, optionally filtered by category.
   *
   * The backend answers `(position, id)` order with and without the filter — the one global order
   * an admin arranged — so the array is kept exactly as it arrives. Note that [categories] and
   * [getSubcategoriesByCategory] are derived from what was loaded: a filtered load leaves the
   * navigation with only the categories of that slice.
   */
  async function fetchPrompts(categoryId: number | null = null) {
    if (isLoading.value) return

    const now = Date.now()
    const isStale = now - lastFetchedAt > STALE_MS
    const isDifferentFilter = categoryId !== lastFetchedCategoryId
    // A filter change replaces the list, so it shows its loading state and reports its failure
    // like the first load does.
    const isVisibleLoad = !hasFetched.value || isDifferentFilter

    if (hasFetched.value && !isDifferentFilter && !isStale) return

    if (isVisibleLoad) {
      isLoading.value = true
      error.value = null
    }

    try {
      const query = categoryId === null ? '' : `?categoryId=${categoryId}`
      prompts.value = await fetchJson<PromptDto[]>(`/api/prompts${query}`)
      hasFetched.value = true
      lastFetchedAt = now
      lastFetchedCategoryId = categoryId
      error.value = null
    } catch (err) {
      if (isVisibleLoad) error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      if (isVisibleLoad) isLoading.value = false
    }
  }

  const categories = computed(() => {
    const seen = new Map<number, PromptCategoryDto>()
    for (const prompt of prompts.value) {
      if (!seen.has(prompt.category.id)) {
        seen.set(prompt.category.id, prompt.category)
      }
    }
    return Array.from(seen.values()).sort((a, b) => a.position - b.position || a.id - b.id)
  })

  const subcategoriesByCategoryId = computed(() => {
    const groups = new Map<number, Map<number, PromptCategoryDto>>()
    for (const prompt of prompts.value) {
      if (prompt.subcategory === null) {
        continue
      }

      const categorySubcategories =
        groups.get(prompt.category.id) ?? new Map<number, PromptCategoryDto>()
      if (!categorySubcategories.has(prompt.subcategory.id)) {
        categorySubcategories.set(prompt.subcategory.id, prompt.subcategory)
      }
      groups.set(prompt.category.id, categorySubcategories)
    }

    return groups
  })

  function getPromptsByCategory(categoryId: number | null): PromptDto[] {
    if (categoryId === null) {
      return [...prompts.value]
    }
    return prompts.value.filter((prompt) => prompt.category.id === categoryId)
  }

  function getSubcategoriesByCategory(categoryId: number): PromptCategoryDto[] {
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

  return {
    prompts,
    isLoading,
    error,
    categories,
    fetchPrompts,
    getPromptsByCategory,
    getSubcategoriesByCategory,
    getPromptsByCategoryAndSubcategory,
    getPromptById,
  }
})
