import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { type AdminJob, useAdminFulfillmentStore } from '@/stores/admin/fulfillment'
import { ApiError, resetApiClientForTests } from '@/lib/api'
import {
  JobAlreadyShippedError,
  JobNotFoundError,
  JobNotReadyError,
  JobPdfUnavailableError,
} from '@/lib/fulfillment'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

function errorResponse(status: number, message: string, code?: string) {
  return jsonResponse(code === undefined ? { message } : { message, code }, { status })
}

function tokenResponse() {
  return jsonResponse({ requestToken: 'token' })
}

/**
 * A `fetch` stub that answers nothing on its own: the test resolves each call by hand, which is how
 * two overlapping list loads can be made to answer in the wrong order.
 */
function deferredFetch() {
  const answer: ((response: Response) => void)[] = []
  const fetchMock = vi.fn(
    () =>
      new Promise<Response>((resolve) => {
        answer.push(resolve)
      }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return { fetchMock, answer }
}

const openJob: AdminJob = {
  jobId: 5,
  orderId: 42,
  orderDate: '2026-08-13',
  supplier: { id: 3, name: 'Acme' },
  customerFirstName: 'Ada',
  customerLastName: 'Lovelace',
  shippingStreet: 'Hauptstrasse',
  shippingHouseNumber: '7',
  shippingPostalCode: '10115',
  shippingCity: 'Berlin',
  shippingCountry: 'Germany',
  items: [{ articleName: 'Mug', variantName: 'White', supplierArticleNumber: 'M-1', quantity: 2 }],
  fulfillmentChannel: 'SFTP',
  pdfAvailable: true,
  generationAttemptCount: 1,
  lastGenerationErrorCode: null,
  externalReference: null,
  remoteState: null,
  shippedAt: null,
  shippedByUserId: null,
  shippedByChannel: null,
  shippingCarrier: null,
  shippingCarrierReported: null,
  trackingNumber: null,
}

describe('admin fulfillment store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it.each(['OPEN', 'SHIPPED'] as const)(
    'loads the %s list of every supplier as a bare array',
    async (status) => {
      const fetchMock = vi.fn(async () => jsonResponse([openJob]))
      vi.stubGlobal('fetch', fetchMock)
      const store = useAdminFulfillmentStore()

      const jobs = await store.fetchJobs(status)

      expect(fetchMock).toHaveBeenCalledWith(`/api/admin/production/jobs?status=${status}`)
      expect(jobs).toEqual([openJob])
      expect(store.loadedStatus).toBe(status)
      expect(store.error).toBeNull()
    },
  )

  it('sends the supplier filter only when one is selected', async () => {
    const fetchMock = vi.fn(async () => jsonResponse([]))
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminFulfillmentStore()

    await store.fetchJobs('OPEN', 3)

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/production/jobs?status=OPEN&supplierId=3')
  })

  it('drops the previous list when a load fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => errorResponse(500, 'Boom')),
    )
    const store = useAdminFulfillmentStore()
    store.jobs = [openJob]

    await store.fetchJobs('OPEN')

    expect(store.jobs).toEqual([])
    expect(store.error?.message).toBe('Boom')
  })

  it('keeps the newer filter when two loads answer in the wrong order', async () => {
    const { answer } = deferredFetch()
    const store = useAdminFulfillmentStore()
    const otherJob: AdminJob = { ...openJob, jobId: 6, supplier: { id: 4, name: 'Other' } }

    const everySupplier = store.fetchJobs('OPEN')
    const oneSupplier = store.fetchJobs('SHIPPED', 4)

    // The first request answers last, which is the whole point: it must write nothing at all —
    // not the list, not the status, and not the loading state the second request is still in.
    answer[1]?.(jsonResponse([otherJob]))
    await oneSupplier
    answer[0]?.(jsonResponse([openJob]))
    await everySupplier

    expect(store.jobs).toEqual([otherJob])
    expect(store.loadedStatus).toBe('SHIPPED')
    expect(store.error).toBeNull()
    expect(store.isLoading).toBe(false)
  })

  it('stays loading until the newest request settles, even when an older one fails first', async () => {
    const { answer } = deferredFetch()
    const store = useAdminFulfillmentStore()

    const first = store.fetchJobs('OPEN')
    const second = store.fetchJobs('SHIPPED')

    answer[0]?.(errorResponse(500, 'Boom'))
    await first

    expect(store.error).toBeNull()
    expect(store.isLoading).toBe(true)

    answer[1]?.(jsonResponse([]))
    await second

    expect(store.loadedStatus).toBe('SHIPPED')
    expect(store.isLoading).toBe(false)
  })

  it('ships on behalf of a supplier through the admin route', async () => {
    const fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
      void init
      return input === '/api/antiforgery/token'
        ? tokenResponse()
        : jsonResponse({ ...openJob, shippedAt: '2026-08-13T10:00:00Z', shippedByUserId: 9 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminFulfillmentStore()

    const shipped = await store.ship(5, { carrier: 'DHL', trackingNumber: '  1234  ' })

    const shipCall = fetchMock.mock.calls.find(
      ([path]) => path === '/api/admin/production/jobs/5/ship',
    )
    expect(shipCall?.[1]).toMatchObject({
      method: 'POST',
      body: JSON.stringify({ carrier: 'DHL', trackingNumber: '1234' }),
    })
    expect(shipped.shippedByUserId).toBe(9)
    expect(store.shippingJobId).toBeNull()
  })

  it('sends an empty JSON body when nothing about the shipment is known', async () => {
    const fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
      void init
      return input === '/api/antiforgery/token' ? tokenResponse() : jsonResponse(openJob)
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminFulfillmentStore()

    await store.ship(5)

    const shipCall = fetchMock.mock.calls.find(
      ([path]) => path === '/api/admin/production/jobs/5/ship',
    )
    expect(shipCall?.[1]).toMatchObject({ body: '{}' })
  })

  it.each([
    [409, 'ALREADY_SHIPPED', JobAlreadyShippedError],
    [409, 'NOT_READY', JobNotReadyError],
    [404, undefined, JobNotFoundError],
  ] as const)(
    'maps a %s %s answer of the ship route to its own error',
    async (status, code, expected) => {
      vi.stubGlobal(
        'fetch',
        vi.fn(async (input: string) =>
          input === '/api/antiforgery/token'
            ? tokenResponse()
            : errorResponse(status, 'Refused', code),
        ),
      )
      const store = useAdminFulfillmentStore()

      await expect(store.ship(5)).rejects.toBeInstanceOf(expected)
      expect(store.shippingJobId).toBeNull()
    },
  )

  it('passes a validation failure through unchanged, so its field errors reach the form', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: string) =>
        input === '/api/antiforgery/token'
          ? tokenResponse()
          : jsonResponse(
              { message: 'Validation failed', errors: { carrier: ['Unknown carrier'] } },
              { status: 400 },
            ),
      ),
    )
    const store = useAdminFulfillmentStore()

    const error = await store.ship(5).catch((err: unknown) => err)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).fieldErrors).toEqual({ carrier: ['Unknown carrier'] })
  })

  it('downloads the document as a blob named after the order', async () => {
    const fetchMock = vi.fn(async () => new Response('%PDF', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminFulfillmentStore()
    store.jobs = [openJob]

    const download = await store.downloadPdf(5)

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/production/jobs/5/pdf')
    expect(download.fileName).toBe('ORD-42.pdf')
    expect(store.downloadingJobId).toBeNull()
  })

  it('maps an unavailable document to the named error carrying its code', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => errorResponse(409, 'No document', 'ARTIFACT_MISSING')),
    )
    const store = useAdminFulfillmentStore()

    const error = await store.downloadPdf(5).catch((err: unknown) => err)

    expect(error).toBeInstanceOf(JobPdfUnavailableError)
    expect((error as JobPdfUnavailableError).code).toBe('ARTIFACT_MISSING')
  })
})
