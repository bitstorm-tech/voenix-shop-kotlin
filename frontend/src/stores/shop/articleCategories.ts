import { ref, computed } from 'vue'
import { defineStore } from 'pinia'

export interface CategoryDto {
  id: number
  name: string
  position: number
  subcategories?: ArticleSubcategoryDto[]
}

export interface ArticleSubcategoryDto {
  id: number
  name: string
  position: number
  exampleImageFilename?: string | null
}

interface CategoriesResponse {
  categories: Record<string, CategoryDto[]>
}

export const useArticleCategoriesStore = defineStore('articleCategories', () => {
  const allCategories = ref<Record<string, CategoryDto[]>>({})
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  const hasFetched = ref(false)

  const mugCategories = computed(() => allCategories.value['MUG'] ?? [])

  async function fetchCategories() {
    if (hasFetched.value || isLoading.value) {
      return
    }

    isLoading.value = true
    error.value = null

    try {
      const response = await fetch('/api/articles/categories')

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}))
        error.value = errorData.detail || errorData.message || `HTTP error ${response.status}`
        return
      }

      const data: CategoriesResponse = await response.json()
      allCategories.value = data.categories
      hasFetched.value = true
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error occurred'
    } finally {
      isLoading.value = false
    }
  }

  return {
    allCategories,
    mugCategories,
    isLoading,
    error,
    hasFetched,
    fetchCategories,
  }
})
