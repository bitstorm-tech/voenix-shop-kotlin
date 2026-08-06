import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { fetchJson } from '@/lib/api'

/**
 * One subcategory of the storefront navigation, nested inside its category.
 *
 * The backend only lists subcategories a visible mug actually sits in, so there is no `active`
 * flag and no empty entry to hide here.
 */
export interface ArticleSubcategoryDto {
  id: number
  name: string
  exampleImageFilename: string | null
  position: number
}

/** One category of the storefront navigation, with its subcategories nested inside it. */
export interface CategoryDto {
  id: number
  name: string
  position: number
  subcategories: ArticleSubcategoryDto[]
}

export const useArticleCategoriesStore = defineStore('articleCategories', () => {
  const mugCategories = ref<CategoryDto[]>([])
  const isLoading = shallowRef(false)
  const error = shallowRef<string | null>(null)
  const hasFetched = shallowRef(false)

  /**
   * Loads the mug navigation. The route path names the article type, so the answer is the bare
   * array of mug categories — there is no map from article type to look a `"MUG"` key up in.
   */
  async function fetchCategories() {
    if (hasFetched.value || isLoading.value) {
      return
    }

    isLoading.value = true
    error.value = null

    try {
      mugCategories.value = await fetchJson<CategoryDto[]>('/api/articles/mugs/categories')
      hasFetched.value = true
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error occurred'
    } finally {
      isLoading.value = false
    }
  }

  return {
    mugCategories,
    isLoading,
    error,
    hasFetched,
    fetchCategories,
  }
})
