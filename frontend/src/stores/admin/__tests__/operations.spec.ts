import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import { type OperationsFinding, useAdminOperationsStore } from '@/stores/admin/operations'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

const stalledDelivery: OperationsFinding = {
  kind: 'DELIVERY_STALLED',
  subject: { type: 'DELIVERY', id: 2 },
  orderId: 42,
  since: '2026-10-16T10:00:00.000Z',
  attemptCount: 3,
  lastErrorCode: 'SFTP_CONNECT_FAILED',
  firstSeenAt: '2026-10-16T10:05:00.000Z',
  alertedAt: null,
}

describe('admin operations store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads the open findings as a bare array', async () => {
    const fetchMock = vi.fn(async () => jsonResponse([stalledDelivery]))
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminOperationsStore()

    await store.fetchFindings()

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/operations/findings')
    expect(store.findings).toEqual([stalledDelivery])
    expect(store.error).toBeNull()
    expect(store.isLoading).toBe(false)
  })

  it('asks for the resolved list with resolved=true', async () => {
    const resolved = { ...stalledDelivery, resolvedAt: '2026-10-16T11:00:00.000Z' }
    const fetchMock = vi.fn(async () => jsonResponse([resolved]))
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminOperationsStore()

    await store.fetchFindings(true)

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/operations/findings?resolved=true')
    expect(store.findings).toEqual([resolved])
  })

  it('drops the previous list when a load fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ message: 'Boom' }, { status: 500 })),
    )
    const store = useAdminOperationsStore()
    store.findings = [stalledDelivery]

    await store.fetchFindings()

    expect(store.findings).toEqual([])
    expect(store.error?.message).toBe('Boom')
    expect(store.isLoading).toBe(false)
  })

  it('keeps the newer list when two loads answer in the wrong order', async () => {
    const answer: ((response: Response) => void)[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn(
        () =>
          new Promise<Response>((resolve) => {
            answer.push(resolve)
          }),
      ),
    )
    const store = useAdminOperationsStore()
    const resolved = { ...stalledDelivery, resolvedAt: '2026-10-16T11:00:00.000Z' }

    const open = store.fetchFindings()
    const resolvedLoad = store.fetchFindings(true)
    answer[1]?.(jsonResponse([resolved]))
    await resolvedLoad
    answer[0]?.(jsonResponse([stalledDelivery]))
    await open

    expect(store.findings).toEqual([resolved])
    expect(store.isLoading).toBe(false)
  })

  it('loads the time of the last scan, null before the first one', async () => {
    const fetchMock = vi.fn(async () => jsonResponse({ lastScanAt: '2026-10-16T10:17:00.000Z' }))
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminOperationsStore()
    expect(store.lastScanAt).toBeUndefined()

    await store.fetchLastScan()
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/operations/last-scan')
    expect(store.lastScanAt).toBe('2026-10-16T10:17:00.000Z')

    fetchMock.mockResolvedValueOnce(jsonResponse({ lastScanAt: null }))
    await store.fetchLastScan()
    expect(store.lastScanAt).toBeNull()
  })

  it('forgets the time of the last scan when its load fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ message: 'Boom' }, { status: 500 })),
    )
    const store = useAdminOperationsStore()
    store.lastScanAt = '2026-10-16T10:17:00.000Z'

    await store.fetchLastScan()

    expect(store.lastScanAt).toBeUndefined()
    expect(store.error).toBeNull()
  })
})
