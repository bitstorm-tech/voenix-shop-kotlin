import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import {
  CountryConflictError,
  CountryNotFoundError,
  useAdminCountriesStore,
} from '@/stores/admin/countries'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

function antiforgeryOr(
  handler: (input: RequestInfo | URL, init?: RequestInit) => Response | Promise<Response>,
) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (input === '/api/antiforgery/token') {
      return jsonResponse({ requestToken: 'csrf-token' })
    }

    return handler(input, init)
  })
}

describe('admin countries store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('fetches, creates, updates, and deletes countries while keeping them sorted', async () => {
    const germany = {
      id: 1,
      name: 'Germany',
      countryCode: 'DE',
      shippingCostCents: 490,
      freeShippingThresholdCents: 5000,
    }
    const austria = {
      id: 2,
      name: 'Austria',
      countryCode: 'AT',
      shippingCostCents: 890,
      freeShippingThresholdCents: null,
    }
    const austriaUpdated = {
      ...austria,
      shippingCostCents: 790,
      freeShippingThresholdCents: 7500,
    }

    vi.stubGlobal(
      'fetch',
      antiforgeryOr((input, init) => {
        if (input === '/api/admin/countries' && !init?.method) {
          return jsonResponse([germany])
        }
        if (input === '/api/admin/countries' && init?.method === 'POST') {
          return jsonResponse(austria, { status: 201 })
        }
        if (input === '/api/admin/countries/2' && init?.method === 'PUT') {
          return jsonResponse(austriaUpdated)
        }
        if (input === '/api/admin/countries/2' && init?.method === 'DELETE') {
          return new Response(null, { status: 204 })
        }
        throw new Error(`Unexpected request: ${String(input)}`)
      }),
    )

    const store = useAdminCountriesStore()
    await store.fetchAll()
    expect(store.countries).toEqual([germany])

    await store.createCountry({
      name: 'Austria',
      countryCode: 'AT',
      shippingCostCents: 890,
      freeShippingThresholdCents: null,
    })
    expect(store.countries.map((c) => c.countryCode)).toEqual(['AT', 'DE'])

    await store.updateCountry(2, {
      name: 'Austria',
      countryCode: 'AT',
      shippingCostCents: 790,
      freeShippingThresholdCents: 7500,
    })
    expect(store.countries[0]).toEqual(austriaUpdated)

    await store.deleteCountry(2)
    expect(store.countries).toEqual([germany])
  })

  it('maps 409 conflicts and 404 not-found answers to domain errors', async () => {
    vi.stubGlobal(
      'fetch',
      antiforgeryOr((input, init) => {
        if (input === '/api/admin/countries' && init?.method === 'POST') {
          return jsonResponse({ message: 'Country name or code already exists' }, { status: 409 })
        }
        if (input === '/api/admin/countries/99' && init?.method === 'DELETE') {
          return jsonResponse({ message: 'Country not found' }, { status: 404 })
        }
        throw new Error(`Unexpected request: ${String(input)}`)
      }),
    )

    const store = useAdminCountriesStore()
    await expect(
      store.createCountry({
        name: 'Germany',
        countryCode: 'DE',
        shippingCostCents: 490,
        freeShippingThresholdCents: 5000,
      }),
    ).rejects.toBeInstanceOf(CountryConflictError)

    await expect(store.deleteCountry(99)).rejects.toBeInstanceOf(CountryNotFoundError)
  })
})
