import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  useAdminVatStore,
  VatInUseError,
  VatNameConflictError,
  VatNotFoundError,
  type AdminVatDto,
} from '@/stores/admin/vat'
import { resetApiClientForTests } from '@/lib/api'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

/** Copied from the response body in `docs/dev/backend/packages/vat-package.md`. */
const standardVat: AdminVatDto = {
  id: 1,
  name: 'Standard',
  percent: 19,
  description: 'German standard rate',
  isDefault: true,
}

describe('admin vat store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetApiClientForTests()
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads the bare VAT array of the admin API', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        expect(input).toBe('/api/admin/vat')
        return jsonResponse([standardVat])
      }),
    )
    const store = useAdminVatStore()

    await store.fetchAll()

    expect(store.vats).toEqual([standardVat])
    expect(store.error).toBeNull()
  })

  /** The backend answers `409 "VAT entry already exists"` for a create or update. */
  it('maps a duplicate name to a VatNameConflictError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (input === '/api/antiforgery/token') {
          return jsonResponse({ requestToken: 'token-1' })
        }

        return jsonResponse({ message: 'VAT name already exists' }, { status: 409 })
      }),
    )
    const store = useAdminVatStore()

    await expect(
      store.createVat({ name: 'Standard', percent: 19, isDefault: false }),
    ).rejects.toThrow(VatNameConflictError)
  })

  /** The backend answers `409 "VAT is in use"` for a delete, which is a different refusal. */
  it('maps a delete conflict to a VatInUseError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (input === '/api/antiforgery/token') {
          return jsonResponse({ requestToken: 'token-1' })
        }

        return jsonResponse({ message: 'VAT is in use' }, { status: 409 })
      }),
    )
    const store = useAdminVatStore()

    const rejection = store.deleteVat(1)

    await expect(rejection).rejects.toThrow(VatInUseError)
    await expect(rejection).rejects.not.toThrow(VatNameConflictError)
  })

  it('maps an update conflict to a VatNameConflictError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (input === '/api/antiforgery/token') {
          return jsonResponse({ requestToken: 'token-1' })
        }

        return jsonResponse({ message: 'VAT entry already exists' }, { status: 409 })
      }),
    )
    const store = useAdminVatStore()

    await expect(
      store.updateVat(1, { name: 'Standard', percent: 19, isDefault: false }),
    ).rejects.toThrow(VatNameConflictError)
  })

  it('maps a missing entry to a VatNotFoundError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ message: 'VAT not found' }, { status: 404 })),
    )
    const store = useAdminVatStore()

    await expect(store.fetchById(99)).rejects.toThrow(VatNotFoundError)
  })

  it('reports a failed list load through the store error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ message: 'Internal server error' }, { status: 500 })),
    )
    const store = useAdminVatStore()

    await store.fetchAll()

    expect(store.vats).toEqual([])
    expect(store.error).toBe('Internal server error')
  })
})
