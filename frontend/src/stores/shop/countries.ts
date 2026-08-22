import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { fetchJson } from '@/lib/api'

/**
 * The public country representation (`PublicCountry` in
 * `docs/dev/backend/packages/country-package.md`). The list contains exactly the countries the shop ships
 * to, so it feeds the shipping-country dropdown — never the billing country, which the backend
 * deliberately does not restrict.
 */
export interface Country {
  name: string
  countryCode: string
  dialCode: string | null
}

interface FetchCountriesOptions {
  force?: boolean
}

function normalizeCountry(country: Country): Country | null {
  const countryCode = country.countryCode?.trim().toUpperCase() ?? ''
  if (!country.name?.trim() || !/^[A-Z]{2}$/.test(countryCode)) {
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

  /** Empty until the list has loaded: without the list there is no shippable default to offer. */
  const defaultCountryCode = computed(() => {
    if (countries.value.some((country) => country.countryCode === 'DE')) {
      return 'DE'
    }

    return countries.value[0]?.countryCode ?? ''
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
      // `GET /api/countries` answers a bare JSON array, ordered by country code, then id.
      const data = await fetchJson<Country[]>('/api/countries')
      countries.value = (Array.isArray(data) ? data : []).flatMap((country) => {
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

  /** Answers the shipping question only: is this a country the shop ships to? */
  function isSupportedCountry(countryCode: string | null | undefined): boolean {
    const normalizedCountryCode = countryCode?.trim().toUpperCase()
    return countries.value.some((country) => country.countryCode === normalizedCountryCode)
  }

  /**
   * Resolves a shipping country against the loaded list. Without a list the value is kept as it
   * is, because an empty list is a loading failure, not the answer "we ship nowhere".
   */
  function resolveCountryCode(countryCode: string | null | undefined): string {
    const normalizedCountryCode = countryCode?.trim().toUpperCase() ?? ''
    if (countries.value.length === 0) {
      return normalizedCountryCode
    }

    return isSupportedCountry(normalizedCountryCode)
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
