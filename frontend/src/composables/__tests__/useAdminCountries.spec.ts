import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAdminCountries } from '../useAdminCountries'
import { resetApiClientForTests } from '@/lib/api'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

/** The admin `Country` values of `docs/dev/backend/country-package.md`, ordered by country code. */
const countries = [
  { id: 2, name: 'Austria', countryCode: 'AT' },
  { id: 1, name: 'Germany', countryCode: 'DE' },
]

describe('useAdminCountries', () => {
  beforeEach(() => {
    resetApiClientForTests()
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads the bare country array through the API client', async () => {
    const fetchMock = vi.fn(async () => jsonResponse(countries))
    vi.stubGlobal('fetch', fetchMock)
    const { countries: loaded, error, isLoading, loadCountries } = useAdminCountries()

    await loadCountries()

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/countries')
    expect(loaded.value).toEqual(countries)
    expect(error.value).toBeNull()
    expect(isLoading.value).toBe(false)
  })

  it('loads the list only once', async () => {
    const fetchMock = vi.fn(async () => jsonResponse(countries))
    vi.stubGlobal('fetch', fetchMock)
    const { loadCountries } = useAdminCountries()

    await loadCountries()
    await loadCountries()

    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('reports the backend message when the request fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ message: 'Internal server error' }, { status: 500 })),
    )
    const { countries: loaded, error, loadCountries } = useAdminCountries()

    await loadCountries()

    expect(loaded.value).toEqual([])
    expect(error.value).toBe('Internal server error')
  })
})
