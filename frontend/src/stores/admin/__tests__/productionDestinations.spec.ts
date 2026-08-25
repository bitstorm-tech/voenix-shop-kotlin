import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import {
  type AdminProductionDestinationDto,
  DestinationInUseError,
  DestinationNotFoundError,
  InvalidDestinationRequestError,
  type SaveProductionDestinationRequest,
  type TshirtSyncReport,
  useAdminProductionDestinationsStore,
} from '@/stores/admin/productionDestinations'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

/** Answers the antiforgery token for every unsafe request and delegates the rest to `handler`. */
function stubFetch(handler: (input: RequestInfo | URL, init?: RequestInit) => unknown) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (input === '/api/antiforgery/token') {
      return jsonResponse({ requestToken: 'token-1' })
    }

    return handler(input, init)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function spodDestination(
  overrides: Partial<AdminProductionDestinationDto> = {},
): AdminProductionDestinationDto {
  return {
    id: 1,
    supplierId: 3,
    channel: 'SPOD',
    label: 'Acme print-on-demand',
    enabled: true,
    notificationEmail: null,
    notificationName: null,
    spod: { environment: 'STAGING', timeoutSeconds: 30 },
    ...overrides,
  }
}

function syncReport(overrides: Partial<TshirtSyncReport> = {}): TshirtSyncReport {
  return {
    destinationId: 1,
    supplierId: 3,
    environment: 'STAGING',
    status: 'COMPLETED',
    failure: null,
    startedAt: '2026-08-24T10:00:00Z',
    finishedAt: '2026-08-24T10:00:12Z',
    fetchedArticles: 2,
    created: [
      {
        articleId: 11,
        spodArticleId: 'spod-1',
        name: 'Classic Tee',
        variantsCreated: 4,
        variantsUpdated: 0,
        variantsDeactivated: 0,
      },
    ],
    updated: [],
    unchanged: [],
    deactivated: [],
    failed: [],
    warnings: [],
    ...overrides,
  }
}

const spodPayload: SaveProductionDestinationRequest = {
  supplierId: 3,
  channel: 'SPOD',
  label: 'Acme print-on-demand',
  enabled: true,
  spod: { environment: 'STAGING', accessToken: 'secret-token', timeoutSeconds: 30 },
}

describe('admin production destinations store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('reads the list as a bare array and keeps the secret-free detail block', async () => {
    const fetchMock = stubFetch(() =>
      jsonResponse([
        spodDestination({ id: 2, supplierId: 4, label: 'Zulu' }),
        spodDestination({
          id: 1,
          supplierId: 3,
          channel: 'SFTP',
          label: 'Alpha',
          spod: null,
          sftp: {
            host: 'sftp.acme.test',
            port: 22,
            username: 'acme',
            hostKeyFingerprint: 'SHA256:abc',
            remotePath: '/in',
            timeoutSeconds: 30,
          },
        }),
      ]),
    )
    const store = useAdminProductionDestinationsStore()

    await store.fetchDestinations()

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/production/destinations')
    expect(store.destinations.map(({ id }) => id)).toEqual([1, 2])
    expect(store.destinations[0]?.sftp).toMatchObject({ host: 'sftp.acme.test', port: 22 })
    expect(store.error).toBeNull()
  })

  it('reads one destination from the item route', async () => {
    const fetchMock = stubFetch(() => jsonResponse(spodDestination({ id: 7 })))
    const store = useAdminProductionDestinationsStore()

    const destination = await store.fetchDestination(7)

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/production/destinations/7')
    expect(destination.id).toBe(7)
    expect(store.destinations.map(({ id }) => id)).toEqual([7])
  })

  it('sends a create to the collection and an update to the item route', async () => {
    const fetchMock = stubFetch(() => jsonResponse(spodDestination({ id: 9 }), { status: 201 }))
    const store = useAdminProductionDestinationsStore()

    await store.createDestination(spodPayload)
    await store.updateDestination(9, spodPayload)

    expect(fetchMock.mock.calls[1]![0]).toBe('/api/admin/production/destinations')
    expect(fetchMock.mock.calls[1]![1]).toMatchObject({ method: 'POST' })
    expect(fetchMock.mock.calls[2]![0]).toBe('/api/admin/production/destinations/9')
    expect(fetchMock.mock.calls[2]![1]).toMatchObject({ method: 'PUT' })
    expect(String(fetchMock.mock.calls[1]![1]?.body)).toContain('secret-token')
  })

  it('reports a rejected write on the JSON path of the offending value', async () => {
    stubFetch(() =>
      jsonResponse(
        {
          message: 'Validation failed',
          errors: {
            supplierId: ['Supplier not found'],
            'spod.accessToken': ['AccessToken is required'],
            'spod.timeoutSeconds': ['TimeoutSeconds must be between 1 and 3600'],
          },
        },
        { status: 400 },
      ),
    )
    const store = useAdminProductionDestinationsStore()

    const error = await store.createDestination(spodPayload).catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(InvalidDestinationRequestError)
    const invalid = error as InvalidDestinationRequestError
    expect(invalid.fieldError('supplierId')).toBe('Supplier not found')
    expect(invalid.fieldError('spod.accessToken')).toBe('AccessToken is required')
    expect(invalid.fieldError('sftp.host')).toBeNull()
  })

  it('reports the second enabled SPOD destination of a supplier on the channel field', async () => {
    stubFetch(() =>
      jsonResponse(
        {
          message: 'Validation failed',
          errors: {
            channel: ['Supplier already has an enabled SPOD destination; disable it first'],
          },
        },
        { status: 400 },
      ),
    )
    const store = useAdminProductionDestinationsStore()

    const error = await store.createDestination(spodPayload).catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(InvalidDestinationRequestError)
    expect((error as InvalidDestinationRequestError).fieldError('channel')).toBe(
      'Supplier already has an enabled SPOD destination; disable it first',
    )
  })

  it('translates an unknown destination into a not-found error', async () => {
    stubFetch(() => jsonResponse({ message: 'Production destination not found' }, { status: 404 }))
    const store = useAdminProductionDestinationsStore()

    await expect(store.fetchDestination(99)).rejects.toBeInstanceOf(DestinationNotFoundError)
  })

  it('deletes a destination and drops it from the collection', async () => {
    const fetchMock = stubFetch(() => new Response(null, { status: 204 }))
    const store = useAdminProductionDestinationsStore()
    store.destinations = [spodDestination({ id: 5 })]

    await store.deleteDestination(5)

    expect(fetchMock.mock.calls[1]![0]).toBe('/api/admin/production/destinations/5')
    expect(fetchMock.mock.calls[1]![1]).toMatchObject({ method: 'DELETE' })
    expect(store.destinations).toEqual([])
  })

  it('posts a sync, marks the destination busy while it runs, and keeps the report', async () => {
    let releaseSync: (response: Response) => void = () => {}
    const syncResponse = new Promise<Response>((resolve) => {
      releaseSync = resolve
    })
    const fetchMock = stubFetch(() => syncResponse)
    const store = useAdminProductionDestinationsStore()

    const pending = store.syncArticles(1)
    expect(store.isSyncing(1)).toBe(true)
    expect(store.isSyncing(2)).toBe(false)
    expect(store.syncReport(1)).toBeNull()

    releaseSync(jsonResponse(syncReport()))
    const report = await pending

    expect(fetchMock.mock.calls[1]![0]).toBe('/api/admin/production/destinations/1/sync-articles')
    expect(fetchMock.mock.calls[1]![1]).toMatchObject({ method: 'POST' })
    expect(report.created).toHaveLength(1)
    expect(store.syncReport(1)).toEqual(report)
    expect(store.isSyncing(1)).toBe(false)
  })

  it('keeps a failed report like any other answer', async () => {
    stubFetch(() =>
      jsonResponse(syncReport({ status: 'FAILED', failure: 'PROVIDER_UNAVAILABLE', created: [] })),
    )
    const store = useAdminProductionDestinationsStore()

    await store.syncArticles(1)

    expect(store.syncReport(1)).toMatchObject({
      status: 'FAILED',
      failure: 'PROVIDER_UNAVAILABLE',
    })
  })

  it('drops the previous report when the next run starts, even if that run is refused', async () => {
    stubFetch(() => jsonResponse(syncReport()))
    const store = useAdminProductionDestinationsStore()
    await store.syncArticles(1)
    expect(store.syncReport(1)).not.toBeNull()

    stubFetch(() =>
      jsonResponse(
        { message: 'This destination is already syncing', code: 'SYNC_RUNNING' },
        { status: 409 },
      ),
    )
    await expect(store.syncArticles(1)).rejects.toThrow()

    expect(store.syncReport(1)).toBeNull()
  })

  it('names the reason a sync was refused, in this shop own words, per conflict code', async () => {
    const store = useAdminProductionDestinationsStore()

    stubFetch(() =>
      jsonResponse(
        { message: 'This destination is already syncing', code: 'SYNC_RUNNING' },
        { status: 409 },
      ),
    )
    await expect(store.syncArticles(1)).rejects.toThrow(
      'A sync is already running for this destination.',
    )

    stubFetch(() =>
      jsonResponse(
        {
          message: 'Only print-on-demand destinations have a t-shirt catalog to sync',
          code: 'CHANNEL_WITHOUT_CATALOG',
        },
        { status: 409 },
      ),
    )
    await expect(store.syncArticles(1)).rejects.toThrow(
      'Only Spreadconnect destinations can be synced.',
    )
    expect(store.isSyncing(1)).toBe(false)
  })

  it('translates an unknown destination on the sync route into a not-found error', async () => {
    stubFetch(() => jsonResponse({ message: 'Production destination not found' }, { status: 404 }))
    const store = useAdminProductionDestinationsStore()

    await expect(store.syncArticles(99)).rejects.toBeInstanceOf(DestinationNotFoundError)
  })

  it('translates a referenced destination into an in-use error and keeps the row', async () => {
    stubFetch(() =>
      jsonResponse(
        {
          message: 'Production destination is in use and cannot be deleted; disable it instead',
        },
        { status: 409 },
      ),
    )
    const store = useAdminProductionDestinationsStore()
    store.destinations = [spodDestination({ id: 5 })]

    await expect(store.deleteDestination(5)).rejects.toBeInstanceOf(DestinationInUseError)
    expect(store.destinations.map(({ id }) => id)).toEqual([5])
  })
})
