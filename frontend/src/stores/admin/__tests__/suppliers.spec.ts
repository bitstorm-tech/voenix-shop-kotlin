import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  formatContactPerson,
  SupplierCountryNotFoundError,
  SupplierInUseError,
  SupplierNotFoundError,
  useAdminSuppliersStore,
  type AdminSupplierDto,
} from '@/stores/admin/suppliers'
import { ApiError, resetApiClientForTests } from '@/lib/api'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

/**
 * The one `Supplier` representation of `docs/dev/backend/supplier-package.md`: list, detail,
 * create, and update all answer these fields, including the nested country.
 */
const acme: AdminSupplierDto = {
  id: 1,
  name: 'ACME',
  title: 'Ms.',
  firstName: 'Ada',
  lastName: 'Lovelace',
  street: 'Main St',
  houseNumber: '1',
  city: 'Berlin',
  postalCode: '10115',
  countryId: 1,
  country: { id: 1, name: 'Germany', countryCode: 'DE' },
  phoneNumber1: '+49 30 1234',
  phoneNumber2: null,
  phoneNumber3: null,
  email: 'info@acme.test',
  website: 'https://acme.test',
}

const globex: AdminSupplierDto = {
  ...acme,
  id: 2,
  name: 'Globex',
  title: null,
  firstName: null,
  lastName: null,
  city: null,
  countryId: null,
  country: null,
  email: null,
}

describe('admin suppliers store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetApiClientForTests()
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads the bare supplier array of the admin API', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        expect(input).toBe('/api/admin/suppliers')
        return jsonResponse([acme, globex])
      }),
    )
    const store = useAdminSuppliersStore()

    await store.fetchSuppliers()

    expect(store.suppliers).toEqual([acme, globex])
    expect(store.error).toBeNull()
  })

  it('syncs a created supplier into the list in name order', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      expect(input).toBe('/api/admin/suppliers')
      expect(init?.method).toBe('POST')
      return jsonResponse(acme, { status: 201 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminSuppliersStore()
    store.suppliers = [globex]

    const created = await store.createSupplier({ name: 'ACME' })

    expect(created).toEqual(acme)
    expect(store.suppliers).toEqual([acme, globex])
  })

  it('builds the displayed contact person from the three name parts', () => {
    expect(formatContactPerson(acme)).toBe('Ms. Ada Lovelace')
    expect(formatContactPerson(globex)).toBeNull()
    expect(formatContactPerson({ ...acme, title: null, firstName: '  ' })).toBe('Lovelace')
  })

  it('maps a conflict on delete to a SupplierInUseError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (input === '/api/antiforgery/token') {
          return jsonResponse({ requestToken: 'token-1' })
        }

        return jsonResponse(
          { message: 'Supplier is in use and cannot be deleted' },
          { status: 409 },
        )
      }),
    )
    const store = useAdminSuppliersStore()

    await expect(store.deleteSupplier(1)).rejects.toThrow(SupplierInUseError)
  })

  it('maps a missing supplier to a SupplierNotFoundError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ message: 'Supplier not found' }, { status: 404 })),
    )
    const store = useAdminSuppliersStore()

    await expect(store.fetchSupplier(99)).rejects.toThrow(SupplierNotFoundError)
  })

  it('maps the countryId field error to a SupplierCountryNotFoundError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (input === '/api/antiforgery/token') {
          return jsonResponse({ requestToken: 'token-1' })
        }

        return jsonResponse(
          { message: 'Validation failed', errors: { countryId: ['Country not found'] } },
          { status: 400 },
        )
      }),
    )
    const store = useAdminSuppliersStore()

    const error = await store
      .createSupplier({ name: 'ACME', countryId: 404 })
      .catch((err: unknown) => err)

    expect(error).toBeInstanceOf(SupplierCountryNotFoundError)
    expect((error as Error).message).toBe('Country not found')
  })

  it('keeps other validation failures as an ApiError with its field errors', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (input === '/api/antiforgery/token') {
          return jsonResponse({ requestToken: 'token-1' })
        }

        return jsonResponse(
          { message: 'Validation failed', errors: { name: ['Name is required'] } },
          { status: 400 },
        )
      }),
    )
    const store = useAdminSuppliersStore()

    const error = await store.createSupplier({ name: ' ' }).catch((err: unknown) => err)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).fieldErrors).toEqual({ name: ['Name is required'] })
  })

  it('reports a failed list load through the store error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ message: 'Internal server error' }, { status: 500 })),
    )
    const store = useAdminSuppliersStore()

    await store.fetchSuppliers()

    expect(store.suppliers).toEqual([])
    expect(store.error).toBe('Internal server error')
  })
})
