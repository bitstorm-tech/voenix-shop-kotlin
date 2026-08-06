import { ref, shallowRef } from 'vue'

export interface AdminCountryDto {
  id: number
  name: string
  countryCode: string
}

/** Lazily loads the country list used by admin forms (e.g. the supplier dialog). */
export function useAdminCountries() {
  const countries = ref<AdminCountryDto[]>([])
  const error = shallowRef<string | null>(null)
  const isLoading = shallowRef(false)
  const isLoaded = shallowRef(false)

  async function readErrorMessage(response: Response) {
    const errorData = await response.json().catch(() => null)
    return errorData?.detail || errorData?.message || `HTTP error ${response.status}`
  }

  async function loadCountries() {
    if (isLoaded.value || isLoading.value) {
      return
    }

    isLoading.value = true
    error.value = null

    try {
      const response = await fetch('/api/admin/countries')

      if (!response.ok) {
        error.value = await readErrorMessage(response)
        return
      }

      const data: { items: AdminCountryDto[] } = await response.json()
      countries.value = data.items
      isLoaded.value = true
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load countries.'
    } finally {
      isLoading.value = false
    }
  }

  return { countries, error, isLoading, loadCountries }
}
