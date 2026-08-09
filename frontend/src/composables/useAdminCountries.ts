import { ref, shallowRef } from 'vue'
import { fetchJson } from '@/lib/api'

/** The admin `Country` representation (`docs/dev/backend/country-package.md`). */
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

  async function loadCountries() {
    if (isLoaded.value || isLoading.value) {
      return
    }

    isLoading.value = true
    error.value = null

    try {
      // `GET /api/admin/countries` answers a bare JSON array, ordered by country code, then id.
      countries.value = await fetchJson<AdminCountryDto[]>('/api/admin/countries')
      isLoaded.value = true
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load countries.'
    } finally {
      isLoading.value = false
    }
  }

  return { countries, error, isLoading, loadCountries }
}
