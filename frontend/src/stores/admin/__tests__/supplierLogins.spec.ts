import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError, resetApiClientForTests } from '@/lib/api'
import {
  type SupplierLogin,
  SupplierLoginEmailTakenError,
  SupplierLoginInvitationNotDeliveredError,
  SupplierLoginNotFoundError,
  SupplierLoginUnknownSupplierError,
  useAdminSupplierLoginsStore,
} from '@/stores/admin/supplierLogins'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

function tokenResponse() {
  return jsonResponse({ requestToken: 'token' })
}

/** Every write goes through the CSRF token call first, so the stub has to answer both. */
function stubWrite(response: (init?: RequestInit) => Response) {
  const fetchMock = vi.fn(async (input: string, init?: RequestInit) =>
    input === '/api/antiforgery/token' ? tokenResponse() : response(init),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const login: SupplierLogin = {
  userId: 7,
  email: 'packing@acme.example',
  supplierId: 3,
  createdAt: '2026-08-13T09:30:00Z',
}

describe('admin supplier logins store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads the logins of one supplier as a bare array', async () => {
    const fetchMock = vi.fn(async () => jsonResponse([login]))
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminSupplierLoginsStore()

    const logins = await store.fetchLogins(3)

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/supplier-logins?supplierId=3')
    expect(logins).toEqual([login])
    expect(store.loadedSupplierId).toBe(3)
    expect(store.error).toBeNull()
  })

  it('drops the previous list when a load fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ message: 'Boom' }, { status: 500 })),
    )
    const store = useAdminSupplierLoginsStore()
    store.logins = [login]

    await store.fetchLogins(3)

    expect(store.logins).toEqual([])
    expect(store.error?.message).toBe('Boom')
  })

  it('creates a login from the supplier id and the trimmed address and appends it', async () => {
    const fetchMock = stubWrite(() => jsonResponse(login, { status: 201 }))
    const store = useAdminSupplierLoginsStore()

    const created = await store.createLogin(3, '  packing@acme.example  ')

    const createCall = fetchMock.mock.calls.find(([path]) => path === '/api/admin/supplier-logins')
    expect(createCall?.[1]).toMatchObject({
      method: 'POST',
      body: JSON.stringify({ supplierId: 3, email: 'packing@acme.example' }),
    })
    expect(created).toEqual(login)
    expect(store.logins).toEqual([login])
    expect(store.isCreating).toBe(false)
  })

  it('names the taken address of a 409 instead of leaving it a generic failure', async () => {
    stubWrite(() => jsonResponse({ message: 'Email already exists' }, { status: 409 }))
    const store = useAdminSupplierLoginsStore()

    await expect(store.createLogin(3, 'taken@example.com')).rejects.toBeInstanceOf(
      SupplierLoginEmailTakenError,
    )
  })

  it('names the unknown supplier of a 400 supplierId field error', async () => {
    stubWrite(() =>
      jsonResponse(
        { message: 'Validation failed', errors: { supplierId: ['Supplier does not exist'] } },
        { status: 400 },
      ),
    )
    const store = useAdminSupplierLoginsStore()

    const error = await store.createLogin(3, 'new@example.com').catch((err: unknown) => err)

    expect(error).toBeInstanceOf(SupplierLoginUnknownSupplierError)
    expect((error as Error).message).toBe('Supplier does not exist')
  })

  it('passes an invalid e-mail address through unchanged, so its field error reaches the form', async () => {
    stubWrite(() =>
      jsonResponse(
        { message: 'Validation failed', errors: { email: ['Email is not valid'] } },
        { status: 400 },
      ),
    )
    const store = useAdminSupplierLoginsStore()

    const error = await store.createLogin(3, 'nope').catch((err: unknown) => err)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).fieldErrors).toEqual({ email: ['Email is not valid'] })
  })

  it('keeps the 502 apart: the login exists, only its invitation did not go out', async () => {
    stubWrite(() =>
      jsonResponse({ message: 'The supplier login was created, but…' }, { status: 502 }),
    )
    const store = useAdminSupplierLoginsStore()

    await expect(store.createLogin(3, 'new@example.com')).rejects.toBeInstanceOf(
      SupplierLoginInvitationNotDeliveredError,
    )
    expect(store.isCreating).toBe(false)
  })

  it('deletes a login and removes it from the list', async () => {
    const fetchMock = stubWrite(() => new Response(null, { status: 204 }))
    const store = useAdminSupplierLoginsStore()
    store.logins = [login]

    await store.deleteLogin(7)

    const deleteCall = fetchMock.mock.calls.find(
      ([path]) => path === '/api/admin/supplier-logins/7',
    )
    expect(deleteCall?.[1]).toMatchObject({ method: 'DELETE' })
    expect(store.logins).toEqual([])
    expect(store.deletingUserId).toBeNull()
  })

  it('maps the 404 of an unknown or non-supplier user to its own error', async () => {
    stubWrite(() => jsonResponse({ message: 'Supplier login not found' }, { status: 404 }))
    const store = useAdminSupplierLoginsStore()

    await expect(store.deleteLogin(7)).rejects.toBeInstanceOf(SupplierLoginNotFoundError)
  })
})
