import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'

export interface Country {
  name: string
  countryCode: string
  dialCode: string | null
}

interface CountryListResponse {
  items: Country[]
}

interface FetchCountriesOptions {
  force?: boolean
}

function normalizeCountry(country: Country): Country | null {
  const countryCode = country.countryCode.trim().toUpperCase()
  if (!country.name.trim() || !/^[A-Z]{2}$/.test(countryCode)) {
    return null
  }

  return {
    name: country.name.trim(),
    countryCode,
    dialCode: normalizeDialCode(country.dialCode),
  }
}

function normalizeDialCode(dialCode: string | null | undefined): string | null {
  const normalized = dialCode?.trim() ?? ''
  if (!normalized) {
    return null
  }

  if (/^\+\d+$/.test(normalized)) {
    return normalized
  }

  if (/^\d+$/.test(normalized)) {
    return `+${normalized}`
  }

  return null
}

export const useCountriesStore = defineStore('countries', () => {
  const countries = ref<Country[]>([])
  const isLoading = shallowRef(false)
  const error = shallowRef<string | null>(null)
  const hasLoaded = shallowRef(false)
  let pendingRequest: Promise<void> | null = null

  const defaultCountryCode = computed(() => {
    if (countries.value.some((country) => country.countryCode === 'DE')) {
      return 'DE'
    }

    return countries.value[0]?.countryCode ?? 'DE'
  })

  async function fetchCountries(options: FetchCountriesOptions = {}): Promise<void> {
    if (hasLoaded.value && !options.force) {
      return
    }

    if (pendingRequest && !options.force) {
      return pendingRequest
    }

    pendingRequest = loadCountries()
    try {
      await pendingRequest
    } finally {
      pendingRequest = null
    }
  }

  async function loadCountries(): Promise<void> {
    isLoading.value = true
    error.value = null

    try {
      const response = await fetch('/api/countries')
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }

      const data = (await response.json()) as CountryListResponse
      countries.value = data.items.flatMap((country) => {
        const normalized = normalizeCountry(country)
        return normalized ? [normalized] : []
      })
      hasLoaded.value = true
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load countries'
    } finally {
      isLoading.value = false
    }
  }

  function isSupportedCountry(countryCode: string | null | undefined): boolean {
    const normalizedCountryCode = countryCode?.trim().toUpperCase()
    return countries.value.some((country) => country.countryCode === normalizedCountryCode)
  }

  function resolveCountryCode(countryCode: string | null | undefined): string {
    const normalizedCountryCode = countryCode?.trim().toUpperCase()
    return normalizedCountryCode && isSupportedCountry(normalizedCountryCode)
      ? normalizedCountryCode
      : defaultCountryCode.value
  }

  return {
    countries,
    isLoading,
    error,
    hasLoaded,
    defaultCountryCode,
    fetchCountries,
    isSupportedCountry,
    resolveCountryCode,
  }
})
