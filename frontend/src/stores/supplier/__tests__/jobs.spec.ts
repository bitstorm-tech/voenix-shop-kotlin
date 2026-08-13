import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError, resetApiClientForTests } from '@/lib/api'
import {
  JobAlreadyShippedError,
  JobNotFoundError,
  JobNotReadyError,
  JobPdfUnavailableError,
  useSupplierJobsStore,
  type SupplierJob,
} from '@/stores/supplier/jobs'

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

const openJob: SupplierJob = {
  jobId: 5,
  orderId: 42,
  orderDate: '2026-08-13',
  customerFirstName: 'Ada',
  customerLastName: 'Lovelace',
  shippingStreet: 'Hauptstrasse',
  shippingHouseNumber: '7',
  shippingPostalCode: '10115',
  shippingCity: 'Berlin',
  shippingCountry: 'Germany',
  items: [{ articleName: 'Mug', variantName: 'White', supplierArticleNumber: 'M-1', quantity: 2 }],
  pdfAvailable: true,
  shippedAt: null,
  shippingCarrier: null,
  trackingNumber: null,
}

describe('supplier jobs store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('reads the supplier identity for the layout header', async () => {
    const fetchMock = vi.fn(async () => jsonResponse({ supplierId: 3, supplierName: 'Acme' }))
    vi.stubGlobal('fetch', fetchMock)
    const store = useSupplierJobsStore()

    const identity = await store.fetchIdentity()

    expect(fetchMock).toHaveBeenCalledWith('/api/supplier/me')
    expect(identity).toEqual({ supplierId: 3, supplierName: 'Acme' })
    expect(store.identity).toEqual({ supplierId: 3, supplierName: 'Acme' })
  })

  it('keeps a failed identity lookup silent, because nothing is gated on it', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => errorResponse(500, 'Boom')),
    )
    const store = useSupplierJobsStore()

    expect(await store.fetchIdentity()).toBeNull()
    expect(store.identity).toBeNull()
  })

  it.each(['OPEN', 'SHIPPED'] as const)('loads the %s list as a bare array', async (status) => {
    const fetchMock = vi.fn(async () => jsonResponse([openJob]))
    vi.stubGlobal('fetch', fetchMock)
    const store = useSupplierJobsStore()

    const jobs = await store.fetchJobs(status)

    expect(fetchMock).toHaveBeenCalledWith(`/api/supplier/production-jobs?status=${status}`)
    expect(jobs).toEqual([openJob])
    expect(store.jobs).toEqual([openJob])
    expect(store.loadedStatus).toBe(status)
    expect(store.isLoading).toBe(false)
    expect(store.error).toBeNull()
  })

  it('drops the previous list when a load fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => errorResponse(500, 'Boom')),
    )
    const store = useSupplierJobsStore()
    store.jobs = [openJob]

    await store.fetchJobs('OPEN')

    expect(store.jobs).toEqual([])
    expect(store.error?.message).toBe('Boom')
  })

  it('sends an empty JSON body when the supplier states neither carrier nor number', async () => {
    const fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
      void init
      return input === '/api/antiforgery/token'
        ? tokenResponse()
        : jsonResponse({ ...openJob, shippedAt: '2026-08-13T10:00:00Z' })
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useSupplierJobsStore()

    const shipped = await store.ship(5)

    const shipCall = fetchMock.mock.calls.find(
      ([path]) => path === '/api/supplier/production-jobs/5/ship',
    )
    expect(shipCall?.[1]).toMatchObject({ method: 'POST', body: '{}' })
    expect(shipped.shippedAt).toBe('2026-08-13T10:00:00Z')
    expect(store.shippingJobId).toBeNull()
  })

  it('sends only the fields the supplier filled in and trims the tracking number', async () => {
    const fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
      void init
      return input === '/api/antiforgery/token' ? tokenResponse() : jsonResponse(openJob)
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useSupplierJobsStore()

    await store.ship(5, { carrier: 'DHL', trackingNumber: '  1234  ' })

    const shipCall = fetchMock.mock.calls.find(
      ([path]) => path === '/api/supplier/production-jobs/5/ship',
    )
    expect(shipCall?.[1]).toMatchObject({
      body: JSON.stringify({ carrier: 'DHL', trackingNumber: '1234' }),
    })
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
      const store = useSupplierJobsStore()

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
              {
                message: 'Validation failed',
                errors: { carrier: ['Carrier must be one of: DHL'] },
              },
              { status: 400 },
            ),
      ),
    )
    const store = useSupplierJobsStore()

    const error = await store.ship(5, { carrier: 'DHL' }).catch((err: unknown) => err)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).fieldErrors).toEqual({ carrier: ['Carrier must be one of: DHL'] })
  })

  it('downloads the document as a blob named after the order', async () => {
    const fetchMock = vi.fn(async () => new Response('%PDF', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const store = useSupplierJobsStore()
    store.jobs = [openJob]

    const download = await store.downloadPdf(5)

    expect(fetchMock).toHaveBeenCalledWith('/api/supplier/production-jobs/5/pdf')
    expect(download.fileName).toBe('ORD-42.pdf')
    expect(download.blob).toBeInstanceOf(Blob)
    expect(download.blob.size).toBe(4)
    expect(store.downloadingJobId).toBeNull()
  })

  it.each(['ARTIFACT_NOT_GENERATED', 'ARTIFACT_MISSING', 'ARTIFACT_DIGEST_MISMATCH'] as const)(
    'maps the %s conflict of the download route to a named error carrying the code',
    async (code) => {
      vi.stubGlobal(
        'fetch',
        vi.fn(async () => errorResponse(409, 'No document', code)),
      )
      const store = useSupplierJobsStore()

      const error = await store.downloadPdf(5).catch((err: unknown) => err)

      expect(error).toBeInstanceOf(JobPdfUnavailableError)
      expect((error as JobPdfUnavailableError).code).toBe(code)
    },
  )

  it('maps an unknown or foreign job of the download route to the not-found error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => errorResponse(404, 'Production job not found')),
    )
    const store = useSupplierJobsStore()

    await expect(store.downloadPdf(5)).rejects.toBeInstanceOf(JobNotFoundError)
  })
})
