import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCountriesStore } from '@/stores/shop/countries'
import { resetApiClientForTests } from '@/lib/api'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

describe('countries store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetApiClientForTests()
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads and normalizes the bare country array of the public API', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse([
        { name: ' Germany ', countryCode: 'de', dialCode: '49' },
        { name: 'France', countryCode: 'FR', dialCode: '+33' },
        { name: 'Invalid', countryCode: 'FRA', dialCode: '+999' },
      ]),
    )
    vi.stubGlobal('fetch', fetchMock)
    const store = useCountriesStore()

    await store.fetchCountries()

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/countries')
    expect(store.countries).toEqual([
      { name: 'Germany', countryCode: 'DE', dialCode: '+49' },
      { name: 'France', countryCode: 'FR', dialCode: '+33' },
    ])
    expect(store.defaultCountryCode).toBe('DE')
    expect(store.error).toBeNull()
  })

  it('uses the first backend country as default when Germany is not available', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(jsonResponse([{ name: 'France', countryCode: 'FR', dialCode: '+33' }])),
    )
    const store = useCountriesStore()

    await store.fetchCountries()

    expect(store.defaultCountryCode).toBe('FR')
    expect(store.resolveCountryCode('DE')).toBe('FR')
  })

  it('has no default country and keeps a value untouched while the list is empty', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])))
    const store = useCountriesStore()

    await store.fetchCountries()

    expect(store.defaultCountryCode).toBe('')
    expect(store.resolveCountryCode('de')).toBe('DE')
    expect(store.isSupportedCountry('DE')).toBe(false)
  })

  it('deduplicates concurrent country requests', async () => {
    let resolveResponse!: (response: Response) => void
    const fetchMock = vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          resolveResponse = resolve
        }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const store = useCountriesStore()

    const first = store.fetchCountries()
    const second = store.fetchCountries()
    resolveResponse(jsonResponse([{ name: 'Germany', countryCode: 'DE', dialCode: '+49' }]))
    await Promise.all([first, second])

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(store.countries).toHaveLength(1)
  })

  it('refetches countries when force is true', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse([{ name: 'Germany', countryCode: 'DE', dialCode: '+49' }]),
      )
      .mockResolvedValueOnce(jsonResponse([{ name: 'France', countryCode: 'FR', dialCode: '+33' }]))
    vi.stubGlobal('fetch', fetchMock)
    const store = useCountriesStore()

    await store.fetchCountries()
    await store.fetchCountries()
    await store.fetchCountries({ force: true })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(store.countries).toEqual([{ name: 'France', countryCode: 'FR', dialCode: '+33' }])
  })

  it('records an error when the API fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(jsonResponse({ message: 'Internal server error' }, { status: 503 })),
    )
    const store = useCountriesStore()

    await store.fetchCountries()

    expect(store.countries).toEqual([])
    expect(store.error).toBe('Internal server error')
    expect(store.hasLoaded).toBe(false)
    expect(store.defaultCountryCode).toBe('')
  })
})
