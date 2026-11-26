import { ref } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchJson } from '@/lib/api'

/** The admin `Country` representation (`docs/dev/backend/packages/country-package.md`). */
export interface AdminCountryDto {
  id: number
  name: string
  countryCode: string
  shippingCostCents: number
  freeShippingThresholdCents: number | null
}

export interface UpsertAdminCountryRequest {
  name: string
  countryCode: string
  shippingCostCents: number
  freeShippingThresholdCents: number | null
}

export class CountryNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'CountryNotFoundError'
  }
}

export class CountryConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'CountryConflictError'
  }
}

function sortCountries(items: AdminCountryDto[]): AdminCountryDto[] {
  return [...items].sort((a, b) => a.countryCode.localeCompare(b.countryCode) || a.id - b.id)
}

export const useAdminCountriesStore = defineStore('admin-countries', () => {
  const countries = ref<AdminCountryDto[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  function syncCountry(country: AdminCountryDto) {
    const index = countries.value.findIndex((item) => item.id === country.id)
    if (index === -1) {
      countries.value = sortCountries([...countries.value, country])
      return
    }

    const updated = [...countries.value]
    updated[index] = country
    countries.value = sortCountries(updated)
  }

  function removeCountry(id: number) {
    countries.value = countries.value.filter((country) => country.id !== id)
  }

  async function fetchAll() {
    if (isLoading.value) {
      return
    }

    isLoading.value = true
    error.value = null

    try {
      const data = await fetchJson<AdminCountryDto[]>('/api/admin/countries')
      countries.value = sortCountries(data)
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      isLoading.value = false
    }
  }

  async function createCountry(payload: UpsertAdminCountryRequest): Promise<AdminCountryDto> {
    try {
      const country = await fetchJson<AdminCountryDto>('/api/admin/countries', {
        method: 'POST',
        body: payload,
      })
      syncCountry(country)
      return country
    } catch (err) {
      throw toCountryError(err)
    }
  }

  async function updateCountry(
    id: number,
    payload: UpsertAdminCountryRequest,
  ): Promise<AdminCountryDto> {
    try {
      const country = await fetchJson<AdminCountryDto>(`/api/admin/countries/${id}`, {
        method: 'PUT',
        body: payload,
      })
      syncCountry(country)
      return country
    } catch (err) {
      throw toCountryError(err)
    }
  }

  async function deleteCountry(id: number): Promise<void> {
    try {
      await fetchJson<void>(`/api/admin/countries/${id}`, {
        method: 'DELETE',
        responseType: 'void',
      })
      removeCountry(id)
    } catch (err) {
      throw toCountryError(err)
    }
  }

  return {
    countries,
    isLoading,
    error,
    fetchAll,
    createCountry,
    updateCountry,
    deleteCountry,
  }
})

function toCountryError(error: unknown): Error {
  const message = error instanceof Error ? error.message : 'Unknown error'

  if (error instanceof ApiError && error.status === 404) {
    return new CountryNotFoundError(message)
  }

  if (error instanceof ApiError && error.status === 409) {
    return new CountryConflictError(message)
  }

  return error instanceof Error ? error : new Error(message)
}
